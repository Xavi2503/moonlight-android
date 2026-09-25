package com.limelight.binding.input.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbRequest;
import android.os.Build;
import android.os.SystemClock;

import com.limelight.LimeLog;
import com.limelight.nvstream.jni.MoonBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

/**
 * Dedicated Sensa HD haptics companion for the Razer Kishi V3 Pro XL in HID mode.
 *
 * Normal gamepad input remains owned by Android. This class opens only interface 4
 * (EP04 OUT / EP84 IN) and implements the controller's acknowledged Sensa protocol.
 *
 * Protocol details are independently implemented from public interoperability work
 * validated on the same VID/PID (1532:0727).
 */
public final class RazerKishiHapticsController extends AbstractController {
    private static final int RAZER_VID = 0x1532;
    private static final int KISHI_V3_PRO_XL_HID_PID = 0x0727;

    private static final int SENSA_INTERFACE_ID = 4;
    private static final int SENSA_OUT_ADDRESS = 0x04;
    private static final int SENSA_IN_ADDRESS = 0x84;
    private static final int REPORT_SIZE = 64;

    private static final int CMD_SET_MODE = 0x07;
    private static final int CMD_STREAM = 0x0E;
    private static final int CMD_GET_MODE = 0x87;
    private static final int CMD_GET_METADATA_SIZE = 0x90;
    private static final int CMD_GET_METADATA_CHUNK = 0x91;

    private static final int MAX_METADATA_BYTES = 4096;
    private static final int METADATA_CHUNK_BYTES = 50;
    private static final long TRANSFER_TIMEOUT_MS = 150;
    private static final long FRAME_PERIOD_NS = 10_000_000L; // one 10 ms Sensa frame

    private static final double DEFAULT_RUMBLE_FREQUENCY_HZ = 100.0;

    private final UsbDevice device;
    private final UsbDeviceConnection connection;
    private final double rumbleGain;

    private UsbInterface sensaInterface;
    private UsbEndpoint sensaOut;
    private UsbEndpoint sensaIn;
    private UsbRequest inputRequest;
    private UsbRequest outputRequest;

    private Thread outputThread;
    private volatile boolean running;
    private volatile boolean cancelRequested;
    private volatile short lowMotor;
    private volatile short highMotor;

    private Integer originalMode;
    private boolean modeChanged;

    // Previous amplitudes are used to ramp motor changes across the four points
    // in each 10 ms Sensa envelope.
    private final double[] previousAmplitude = new double[] {0.0, 0.0};

    public static boolean canClaimDevice(UsbDevice device) {
        if (device == null ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                device.getVendorId() != RAZER_VID ||
                device.getProductId() != KISHI_V3_PRO_XL_HID_PID) {
            return false;
        }

        UsbInterface iface = findSensaInterface(device);
        return iface != null;
    }

    private static UsbInterface findSensaInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);

            if (iface.getId() != SENSA_INTERFACE_ID ||
                    iface.getAlternateSetting() != 0 ||
                    iface.getInterfaceClass() != UsbConstants.USB_CLASS_HID ||
                    iface.getEndpointCount() != 2) {
                continue;
            }

            UsbEndpoint out = null;
            UsbEndpoint in = null;

            for (int e = 0; e < iface.getEndpointCount(); e++) {
                UsbEndpoint endpoint = iface.getEndpoint(e);

                if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_INT ||
                        endpoint.getMaxPacketSize() != REPORT_SIZE) {
                    continue;
                }

                if (endpoint.getAddress() == SENSA_OUT_ADDRESS &&
                        endpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
                    out = endpoint;
                }
                else if (endpoint.getAddress() == SENSA_IN_ADDRESS &&
                        endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                    in = endpoint;
                }
            }

            if (out != null && in != null) {
                return iface;
            }
        }

        return null;
    }

    public RazerKishiHapticsController(UsbDevice device,
                                      UsbDeviceConnection connection,
                                      int deviceId,
                                      UsbDriverListener listener,
                                      int rumbleStrengthPercent) {
        super(deviceId, listener, device.getVendorId(), device.getProductId());
        this.device = device;
        this.connection = connection;
        this.rumbleGain = clamp(rumbleStrengthPercent / 100.0, 0.0, 2.0);

        this.type = MoonBridge.LI_CTYPE_XBOX;
        this.capabilities = MoonBridge.LI_CCAP_RUMBLE;
        this.supportedButtonFlags = 0;
    }

    @Override
    public boolean start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false;
        }

        try {
            sensaInterface = findSensaInterface(device);
            if (sensaInterface == null) {
                throw new IllegalStateException("Sensa interface 4 not found");
            }

            for (int e = 0; e < sensaInterface.getEndpointCount(); e++) {
                UsbEndpoint endpoint = sensaInterface.getEndpoint(e);
                if (endpoint.getAddress() == SENSA_OUT_ADDRESS) {
                    sensaOut = endpoint;
                }
                else if (endpoint.getAddress() == SENSA_IN_ADDRESS) {
                    sensaIn = endpoint;
                }
            }

            // First try without detaching any kernel driver. If Android owns this
            // dedicated haptics interface, force-claim only this interface.
            if (!connection.claimInterface(sensaInterface, false) &&
                    !connection.claimInterface(sensaInterface, true)) {
                throw new IllegalStateException("Sensa interface is busy");
            }

            inputRequest = new UsbRequest();
            outputRequest = new UsbRequest();

            if (!inputRequest.initialize(connection, sensaIn) ||
                    !outputRequest.initialize(connection, sensaOut)) {
                throw new IllegalStateException("Unable to initialize Sensa USB requests");
            }

            initializeSensaProtocol();

            running = true;
            outputThread = new Thread(this::runOutputLoop, "KishiV3XL-Sensa");
            outputThread.setDaemon(true);
            outputThread.start();

            LimeLog.info("Kishi V3 Pro XL Sensa HD haptics ready on IF" +
                    sensaInterface.getId() + " EP04/EP84");

            // This context exists only so Moonlight's ordinary host rumble callback can
            // reach the Sensa companion. Android remains responsible for controller input.
            notifyDeviceAdded();
            return true;
        }
        catch (Throwable t) {
            LimeLog.warning("Kishi V3 Pro XL Sensa startup failed: " + t);
            cleanupAfterFailedStart();
            return false;
        }
    }

    private void initializeSensaProtocol() throws Exception {
        byte[] sizeReply = exchange(CMD_GET_METADATA_SIZE, new byte[] {0x00, 0x00});
        if (sizeReply.length != 2) {
            throw new IllegalStateException("Invalid Sensa metadata-size reply");
        }

        int metadataLength = ((sizeReply[0] & 0xFF) << 8) | (sizeReply[1] & 0xFF);
        if (metadataLength < 1 || metadataLength > MAX_METADATA_BYTES) {
            throw new IllegalStateException("Invalid Sensa metadata length: " + metadataLength);
        }

        byte[] metadata = new byte[metadataLength];
        int offset = 0;

        while (offset < metadataLength) {
            if (cancelRequested) {
                throw new IllegalStateException("Sensa startup cancelled");
            }

            int count = Math.min(METADATA_CHUNK_BYTES, metadataLength - offset);
            byte[] reply = exchange(CMD_GET_METADATA_CHUNK, new byte[] {
                    (byte) (offset >>> 8),
                    (byte) offset,
                    (byte) count,
                    0x00,
                    0x00
            });

            if (reply.length != count + 3 ||
                    reply[0] != (byte) (offset >>> 8) ||
                    reply[1] != (byte) offset ||
                    reply[2] != (byte) count) {
                throw new IllegalStateException("Invalid Sensa metadata chunk at " + offset);
            }

            System.arraycopy(reply, 3, metadata, offset, count);
            offset += count;
        }

        validateMetadata(metadata);

        byte[] modeReply = exchange(CMD_GET_MODE, new byte[] {0x00});
        if (modeReply.length != 1) {
            throw new IllegalStateException("Invalid Sensa mode reply");
        }

        int mode = modeReply[0] & 0xFF;
        if (mode != 0 && mode != 2) {
            throw new IllegalStateException("Unsupported Sensa mode: " + mode);
        }

        originalMode = mode;

        // Design mode (0) accepts Sensa stream frames. Mode 2 is the controller's
        // conventional ERM mode.
        if (mode != 0) {
            modeChanged = true;
            byte[] setModeReply = exchange(CMD_SET_MODE, new byte[] {0x00});
            if (!Arrays.equals(setModeReply, new byte[] {0x00})) {
                throw new IllegalStateException("Unable to enter Sensa design mode");
            }
        }

        writeStream(buildSilenceReport());
    }

    private void validateMetadata(byte[] metadata) throws Exception {
        int end = metadata.length;
        while (end > 0 && metadata[end - 1] == 0) {
            end--;
        }

        JSONObject json = new JSONObject(new String(metadata, 0, end, "UTF-8"));
        if (json.getInt("StreamBodyType") != 1) {
            throw new IllegalStateException("Unsupported Sensa StreamBodyType");
        }

        JSONArray bodies = json.getJSONArray("Bodypart");
        if (bodies.length() != 2) {
            throw new IllegalStateException("Expected two Sensa actuators");
        }

        int[] expectedBodyIds = new int[] {216, 116};

        for (int i = 0; i < 2; i++) {
            JSONObject body = bodies.getJSONObject(i);
            if (body.getInt("BodypartID") != expectedBodyIds[i]) {
                throw new IllegalStateException("Unexpected Sensa actuator order");
            }

            JSONObject stream = body.getJSONObject("StreamCharacteristics");
            if (stream.getInt("Bands") != 3 ||
                    stream.getInt("Points") != 4 ||
                    stream.getInt("Transients") != 2) {
                throw new IllegalStateException("Unsupported Sensa stream layout");
            }

            JSONArray valueReports = body.getJSONObject("Characteristics")
                    .getJSONArray("ValueReport");
            if (valueReports.length() != 1) {
                throw new IllegalStateException("Unsupported Sensa value report");
            }

            JSONObject value = valueReports.getJSONObject(0);
            if (value.getInt("FrequencyMin") != 30 ||
                    value.getInt("FrequencyMax") != 400) {
                throw new IllegalStateException("Unsupported Sensa frequency range");
            }
        }

        LimeLog.info("Kishi V3 Pro XL Sensa metadata validated (" + metadata.length + " bytes)");
    }

    private byte[] exchange(int command, byte[] payload) throws Exception {
        return transfer(buildReport(command, payload));
    }

    private void writeStream(byte[] report) throws Exception {
        byte[] reply = transfer(report);
        int reportLength = report[1] & 0xFF;
        byte[] expected = Arrays.copyOfRange(report, 5, reportLength + 1);

        if (!Arrays.equals(reply, expected)) {
            throw new IllegalStateException("Sensa stream acknowledgement mismatch");
        }
    }

    private byte[] transfer(byte[] report) throws Exception {
        if (cancelRequested) {
            throw new IllegalStateException("Sensa transfer cancelled");
        }

        ByteBuffer incoming = ByteBuffer.allocateDirect(REPORT_SIZE);
        ByteBuffer outgoing = ByteBuffer.allocateDirect(REPORT_SIZE);
        outgoing.put(report);
        outgoing.flip();

        if (!inputRequest.queue(incoming)) {
            throw new IllegalStateException("Unable to queue Sensa IN request");
        }
        if (!outputRequest.queue(outgoing)) {
            inputRequest.cancel();
            throw new IllegalStateException("Unable to queue Sensa OUT request");
        }

        long deadline = SystemClock.elapsedRealtime() + TRANSFER_TIMEOUT_MS;
        boolean sent = false;
        byte[] reply = null;

        while (!sent || reply == null) {
            if (cancelRequested) {
                throw new IllegalStateException("Sensa transfer cancelled");
            }

            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) {
                throw new TimeoutException("Sensa response timeout");
            }

            UsbRequest completed;
            try {
                completed = connection.requestWait(remaining);
            }
            catch (TimeoutException e) {
                throw new TimeoutException("Sensa response timeout");
            }

            if (completed == outputRequest) {
                if (outgoing.position() != REPORT_SIZE) {
                    throw new IllegalStateException("Short Sensa USB write");
                }
                sent = true;
            }
            else if (completed == inputRequest) {
                int count = incoming.position();
                incoming.flip();

                byte[] bytes = new byte[count];
                incoming.get(bytes);

                if (count < 6 ||
                        bytes[0] != 0x01 ||
                        bytes[2] != 0x00 ||
                        bytes[3] != 0x01) {
                    throw new IllegalStateException("Malformed Sensa reply");
                }

                int length = bytes[1] & 0xFF;
                if (length < 5 || length >= count) {
                    throw new IllegalStateException("Invalid Sensa reply length");
                }

                if (bytes[4] == report[4]) {
                    reply = Arrays.copyOfRange(bytes, 5, length + 1);
                }
                else {
                    // Ignore an unrelated queued reply, but keep the same overall
                    // transfer deadline.
                    incoming.clear();
                    if (!inputRequest.queue(incoming)) {
                        throw new IllegalStateException("Unable to requeue Sensa IN request");
                    }
                }
            }
            else {
                throw new IllegalStateException("Sensa USB request failed");
            }
        }

        return reply;
    }

    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        lowMotor = lowFreqMotor;
        highMotor = highFreqMotor;

        Thread thread = outputThread;
        if (thread != null) {
            LockSupport.unpark(thread);
        }
    }

    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        // Sensa output is driven by the standard low/high GameStream rumble values.
    }

    private void runOutputLoop() {
        boolean outputWasActive = false;
        long nextFrameTime = System.nanoTime();

        while (running && !Thread.currentThread().isInterrupted()) {
            int low = lowMotor & 0xFFFF;
            int high = highMotor & 0xFFFF;

            if (low == 0 && high == 0) {
                if (outputWasActive) {
                    try {
                        writeStream(buildRumbleReport(0.0, 0.0));
                    }
                    catch (Throwable t) {
                        LimeLog.warning("Kishi Sensa stop frame failed: " + t);
                        break;
                    }
                    outputWasActive = false;
                }

                LockSupport.park();
                nextFrameTime = System.nanoTime();
                continue;
            }

            outputWasActive = true;

            try {
                writeStream(buildRumbleReport(
                        (low / 65535.0) * rumbleGain,
                        (high / 65535.0) * rumbleGain));
            }
            catch (Throwable t) {
                LimeLog.warning("Kishi Sensa output failed: " + t);
                break;
            }

            nextFrameTime += FRAME_PERIOD_NS;
            long waitNs = nextFrameTime - System.nanoTime();
            if (waitNs > 0) {
                LockSupport.parkNanos(waitNs);
            }
            else {
                nextFrameTime = System.nanoTime();
            }
        }

        // Do not leave a latched effect if output stops because of an error.
        try {
            if (sensaOut != null) {
                connection.bulkTransfer(sensaOut, buildSilenceReport(), REPORT_SIZE, 150);
            }
        }
        catch (Throwable ignored) {
        }
    }

    private byte[] buildRumbleReport(double leftAmplitude, double rightAmplitude) {
        double[] target = new double[] {
                clamp01(leftAmplitude),
                clamp01(rightAmplitude)
        };

        double[][] amplitudePoints = new double[2][4];

        for (int channel = 0; channel < 2; channel++) {
            double start = target[channel] == 0.0 ? 0.0 : previousAmplitude[channel];

            for (int point = 0; point < 4; point++) {
                amplitudePoints[channel][point] =
                        start + (target[channel] - start) * (point + 1) / 4.0;
            }

            previousAmplitude[channel] = target[channel];
        }

        return buildReport(CMD_STREAM,
                buildSensaFrame(amplitudePoints, DEFAULT_RUMBLE_FREQUENCY_HZ));
    }

    private static byte[] buildSilenceReport() {
        return buildReport(CMD_STREAM,
                buildSensaFrame(new double[][] {
                        {0.0, 0.0, 0.0, 0.0},
                        {0.0, 0.0, 0.0, 0.0}
                }, 30.0));
    }

    /**
     * Encodes one 10 ms Sensa frame. One spectral band per physical actuator is
     * sufficient for ordinary rumble. The wire format is MSB-first.
     */
    private static byte[] buildSensaFrame(double[][] amplitudes, double frequencyHz) {
        if (amplitudes.length != 2 ||
                amplitudes[0].length != 4 ||
                amplitudes[1].length != 4) {
            throw new IllegalArgumentException("Invalid Sensa envelope");
        }

        BitWriter writer = new BitWriter(42);

        // Duration uses quarter-millisecond units: 40 = 10 ms.
        writer.put(40, 7);

        for (int actuator = 0; actuator < 2; actuator++) {
            // One band follows.
            writer.put(1, 1);

            for (int point = 0; point < 4; point++) {
                int amplitude = (int) Math.round(clamp01(amplitudes[actuator][point]) * 63.0);
                int frequency = (int) (((clamp(frequencyHz, 30.0, 400.0) - 30.0) * 127.0) / 370.0);

                writer.put(amplitude, 6);
                writer.put(frequency, 7);
            }

            // Fewer than three bands: terminate the band list.
            writer.put(0, 1);
            // No transient events.
            writer.put(0, 1);
        }

        return writer.toByteArray();
    }

    private static byte[] buildReport(int command, byte[] payload) {
        if (command < 0 || command > 0xFF ||
                payload == null ||
                payload.length < 1 ||
                payload.length > 58) {
            throw new IllegalArgumentException("Invalid Sensa report");
        }

        byte[] report = new byte[REPORT_SIZE];
        report[0] = 0x02; // OUT report ID
        report[1] = (byte) (payload.length + 4);
        report[2] = 0x00;
        report[3] = 0x01; // Sensa protocol version
        report[4] = (byte) command;
        System.arraycopy(payload, 0, report, 5, payload.length);
        return report;
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0, 1.0);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class BitWriter {
        private final byte[] buffer;
        private int bitPosition;

        BitWriter(int maxBytes) {
            buffer = new byte[maxBytes];
        }

        void put(int value, int width) {
            if (width <= 0 || width >= 31 || value < 0 || value >= (1 << width)) {
                throw new IllegalArgumentException("Invalid Sensa bit field");
            }

            for (int i = 0; i < width; i++) {
                if ((value & (1 << (width - i - 1))) != 0) {
                    int byteIndex = bitPosition / 8;
                    int bitIndex = 7 - (bitPosition % 8);
                    buffer[byteIndex] = (byte) (buffer[byteIndex] | (1 << bitIndex));
                }
                bitPosition++;
            }
        }

        byte[] toByteArray() {
            return Arrays.copyOf(buffer, (bitPosition + 7) / 8);
        }
    }

    private void cleanupAfterFailedStart() {
        cancelRequested = true;

        try {
            if (inputRequest != null) {
                inputRequest.cancel();
                inputRequest.close();
            }
        }
        catch (Throwable ignored) {
        }

        try {
            if (outputRequest != null) {
                outputRequest.cancel();
                outputRequest.close();
            }
        }
        catch (Throwable ignored) {
        }

        try {
            if (sensaInterface != null) {
                connection.releaseInterface(sensaInterface);
            }
        }
        catch (Throwable ignored) {
        }

        inputRequest = null;
        outputRequest = null;
        sensaInterface = null;
        sensaOut = null;
        sensaIn = null;
    }

    @Override
    public void stop() {
        if (cancelRequested && sensaInterface == null) {
            return;
        }

        lowMotor = 0;
        highMotor = 0;
        running = false;
        cancelRequested = true;

        if (outputThread != null) {
            LockSupport.unpark(outputThread);
            outputThread.interrupt();
            try {
                outputThread.join(500);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            outputThread = null;
        }

        if (inputRequest != null) {
            try {
                inputRequest.cancel();
                inputRequest.close();
            }
            catch (Throwable ignored) {
            }
            inputRequest = null;
        }

        if (outputRequest != null) {
            try {
                outputRequest.cancel();
                outputRequest.close();
            }
            catch (Throwable ignored) {
            }
            outputRequest = null;
        }

        // After asynchronous requests are closed, use bounded best-effort bulk
        // writes for final silence and restoring the mode another app was using.
        try {
            if (sensaOut != null && sensaInterface != null) {
                connection.bulkTransfer(sensaOut, buildSilenceReport(), REPORT_SIZE, 150);

                if (modeChanged && originalMode != null) {
                    byte[] restoreMode = buildReport(CMD_SET_MODE,
                            new byte[] {(byte) (originalMode & 0xFF)});
                    connection.bulkTransfer(sensaOut, restoreMode, REPORT_SIZE, 150);
                }
            }
        }
        catch (Throwable t) {
            LimeLog.warning("Kishi Sensa cleanup write failed: " + t);
        }

        try {
            if (sensaInterface != null) {
                connection.releaseInterface(sensaInterface);
            }
        }
        catch (Throwable t) {
            LimeLog.warning("Kishi Sensa interface release failed: " + t);
        }

        connection.close();

        sensaInterface = null;
        sensaOut = null;
        sensaIn = null;

        notifyDeviceRemoved();
    }
}
