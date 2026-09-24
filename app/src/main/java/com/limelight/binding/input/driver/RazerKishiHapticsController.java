package com.limelight.binding.input.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;

import com.limelight.LimeLog;
import com.limelight.nvstream.jni.MoonBridge;

import java.util.concurrent.locks.LockSupport;

/**
 * Haptics-only USB companion for the Razer Kishi V3 Pro XL in HID mode.
 *
 * Android keeps ownership of the gamepad interface so the normal buttons and
 * the four extra Kishi buttons remain available through InputDevice. We claim
 * only the 64-byte interrupt OUT interface used by the haptic transport.
 */
public final class RazerKishiHapticsController extends AbstractController {
    private static final int RAZER_VID = 0x1532;
    private static final int KISHI_V3_PRO_XL_HID_PID = 0x0727;

    private static final int HAPTIC_FRAME_SIZE = 64;
    private static final int HAPTIC_HEADER_SIZE = 10;
    private static final int HAPTIC_PCM_BYTES = 48;
    private static final int HAPTIC_SAMPLE_RATE = 4000;
    private static final int STEREO_SAMPLES_PER_FRAME = 12;
    private static final long FRAME_PERIOD_NS = 3_000_000L;

    private static final double LOW_RUMBLE_HZ = 80.0;
    private static final double HIGH_RUMBLE_HZ = 180.0;

    private static final byte[] HAPTIC_HEADER = {
            (byte) 0x55, (byte) 0xAA,
            0x00, 0x00, 0x00, 0x00, 0x00,
            0x30,
            (byte) 0xFE, 0x79
    };

    private final UsbDevice device;
    private final UsbDeviceConnection connection;
    private final Object rumbleSignal = new Object();

    private UsbInterface hapticInterface;
    private UsbEndpoint hapticOutEndpoint;
    private Thread hapticThread;

    private volatile boolean running;
    private volatile short lowMotor;
    private volatile short highMotor;

    private double lowPhase;
    private double highPhase;

    public static boolean canClaimDevice(UsbDevice device) {
        return device != null &&
                device.getVendorId() == RAZER_VID &&
                device.getProductId() == KISHI_V3_PRO_XL_HID_PID;
    }

    public RazerKishiHapticsController(UsbDevice device,
                                      UsbDeviceConnection connection,
                                      int deviceId,
                                      UsbDriverListener listener) {
        super(deviceId, listener, device.getVendorId(), device.getProductId());
        this.device = device;
        this.connection = connection;
        this.type = MoonBridge.LI_CTYPE_XBOX;
        this.capabilities = MoonBridge.LI_CCAP_RUMBLE;
        this.supportedButtonFlags = 0;
    }

    @Override
    public boolean start() {
        if (!locateHapticTransport()) {
            LimeLog.warning("Kishi V3 Pro XL: no 64-byte interrupt OUT haptics endpoint found");
            return false;
        }

        if (!connection.claimInterface(hapticInterface, true)) {
            LimeLog.warning("Kishi V3 Pro XL: failed to claim haptics interface " +
                    hapticInterface.getId());
            return false;
        }

        if (!setHapticState(true)) {
            LimeLog.warning("Kishi V3 Pro XL: haptic enable command failed");
            connection.releaseInterface(hapticInterface);
            return false;
        }

        // Full device intensity. Individual game strength is still controlled by
        // the low/high rumble values received from the streaming host.
        setHapticIntensity((byte) 0x64);

        running = true;
        hapticThread = new Thread(this::hapticLoop, "KishiV3XL-Haptics");
        hapticThread.setDaemon(true);
        hapticThread.start();

        LimeLog.info("Kishi V3 Pro XL haptics ready on interface " +
                hapticInterface.getId() + " endpoint 0x" +
                Integer.toHexString(hapticOutEndpoint.getAddress()));

        // Register a haptics companion context. Controller input itself remains
        // on Android's native InputDevice path.
        notifyDeviceAdded();
        return true;
    }

    private boolean locateHapticTransport() {
        UsbInterface selectedInterface = null;
        UsbEndpoint selectedEndpoint = null;

        for (int interfaceIndex = 0; interfaceIndex < device.getInterfaceCount(); interfaceIndex++) {
            UsbInterface iface = device.getInterface(interfaceIndex);

            for (int endpointIndex = 0; endpointIndex < iface.getEndpointCount(); endpointIndex++) {
                UsbEndpoint endpoint = iface.getEndpoint(endpointIndex);

                if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT &&
                        endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_INT &&
                        endpoint.getMaxPacketSize() == HAPTIC_FRAME_SIZE) {
                    if (selectedEndpoint != null) {
                        LimeLog.warning("Kishi V3 Pro XL: multiple 64-byte interrupt OUT endpoints found");
                    }

                    selectedInterface = iface;
                    selectedEndpoint = endpoint;
                }
            }
        }

        hapticInterface = selectedInterface;
        hapticOutEndpoint = selectedEndpoint;
        return hapticInterface != null && hapticOutEndpoint != null;
    }

    private boolean sendFeatureValue(byte channel, byte value) {
        byte[] payload = {0x00, channel, value};

        // HID SET_REPORT (Feature, report ID 0) directed at the haptics interface.
        int result = connection.controlTransfer(
                UsbConstants.USB_DIR_OUT |
                        UsbConstants.USB_TYPE_CLASS |
                        UsbConstants.USB_RECIP_INTERFACE,
                0x09,
                0x0300,
                hapticInterface.getId(),
                payload,
                payload.length,
                1000);

        return result == payload.length;
    }

    private boolean setHapticState(boolean enabled) {
        byte value = enabled ? (byte) 0x01 : (byte) 0x00;
        boolean left = sendFeatureValue((byte) 0x01, value);
        boolean right = sendFeatureValue((byte) 0x02, value);
        return left && right;
    }

    private boolean setHapticIntensity(byte intensity) {
        boolean left = sendFeatureValue((byte) 0x01, intensity);
        boolean right = sendFeatureValue((byte) 0x02, intensity);
        return left && right;
    }

    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        lowMotor = lowFreqMotor;
        highMotor = highFreqMotor;

        synchronized (rumbleSignal) {
            rumbleSignal.notifyAll();
        }
    }

    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        // The GameStream XInput rumble path does not need trigger-specific haptics here.
    }

    private void hapticLoop() {
        boolean activeLastFrame = false;
        long nextFrameTime = System.nanoTime();

        while (running && !Thread.currentThread().isInterrupted()) {
            int low = lowMotor & 0xFFFF;
            int high = highMotor & 0xFFFF;

            if (low == 0 && high == 0) {
                if (activeLastFrame) {
                    // Flush a few silent frames so a stopped effect cannot remain latched.
                    byte[] silence = buildHapticFrame(0, 0);
                    for (int i = 0; i < 4 && running; i++) {
                        sendFrame(silence);
                    }
                    activeLastFrame = false;
                }

                synchronized (rumbleSignal) {
                    if (running && (lowMotor & 0xFFFF) == 0 && (highMotor & 0xFFFF) == 0) {
                        try {
                            rumbleSignal.wait();
                        }
                        catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                nextFrameTime = System.nanoTime();
                continue;
            }

            activeLastFrame = true;
            sendFrame(buildHapticFrame(low, high));

            nextFrameTime += FRAME_PERIOD_NS;
            long remaining = nextFrameTime - System.nanoTime();
            if (remaining > 0) {
                LockSupport.parkNanos(remaining);
            }
            else {
                nextFrameTime = System.nanoTime();
            }
        }
    }

    private byte[] buildHapticFrame(int low, int high) {
        byte[] frame = new byte[HAPTIC_FRAME_SIZE];
        System.arraycopy(HAPTIC_HEADER, 0, frame, 0, HAPTIC_HEADER.length);

        double lowAmplitude = low / 65535.0;
        double highAmplitude = high / 65535.0;

        double lowStep = (2.0 * Math.PI * LOW_RUMBLE_HZ) / HAPTIC_SAMPLE_RATE;
        double highStep = (2.0 * Math.PI * HIGH_RUMBLE_HZ) / HAPTIC_SAMPLE_RATE;

        int payloadOffset = HAPTIC_HEADER_SIZE;

        for (int i = 0; i < STEREO_SAMPLES_PER_FRAME; i++) {
            double wave = 0.75 * (
                    lowAmplitude * Math.sin(lowPhase) +
                    highAmplitude * Math.sin(highPhase));

            wave = Math.max(-1.0, Math.min(1.0, wave));
            short sample = (short) Math.round(wave * Short.MAX_VALUE);

            // The Kishi stream is stereo: drive both handle actuators equally for
            // ordinary XInput rumble. Spatial haptics can be added separately later.
            frame[payloadOffset++] = (byte) (sample & 0xFF);
            frame[payloadOffset++] = (byte) ((sample >>> 8) & 0xFF);
            frame[payloadOffset++] = (byte) (sample & 0xFF);
            frame[payloadOffset++] = (byte) ((sample >>> 8) & 0xFF);

            lowPhase += lowStep;
            highPhase += highStep;

            if (lowPhase >= 2.0 * Math.PI) {
                lowPhase -= 2.0 * Math.PI;
            }
            if (highPhase >= 2.0 * Math.PI) {
                highPhase -= 2.0 * Math.PI;
            }
        }

        byte checksum = 0;
        for (int i = 2; i < HAPTIC_HEADER_SIZE + HAPTIC_PCM_BYTES; i++) {
            checksum ^= frame[i];
        }
        frame[HAPTIC_HEADER_SIZE + HAPTIC_PCM_BYTES] = checksum;

        return frame;
    }

    private boolean sendFrame(byte[] frame) {
        int result = connection.bulkTransfer(
                hapticOutEndpoint,
                frame,
                frame.length,
                100);

        if (result != frame.length) {
            LimeLog.warning("Kishi V3 Pro XL: haptic frame transfer failed: " + result);
            return false;
        }

        return true;
    }

    @Override
    public void stop() {
        if (!running && hapticInterface == null) {
            return;
        }

        lowMotor = 0;
        highMotor = 0;
        running = false;

        synchronized (rumbleSignal) {
            rumbleSignal.notifyAll();
        }

        if (hapticThread != null) {
            hapticThread.interrupt();
            try {
                hapticThread.join(250);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            hapticThread = null;
        }

        if (hapticInterface != null) {
            setHapticState(false);
            connection.releaseInterface(hapticInterface);
        }

        connection.close();
        notifyDeviceRemoved();

        hapticInterface = null;
        hapticOutEndpoint = null;
    }
}
