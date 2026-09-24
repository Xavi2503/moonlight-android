package com.limelight.binding.input.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbRequest;

import com.limelight.LimeLog;
import com.limelight.nvstream.input.ControllerPacket;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class Xbox360Controller extends AbstractXboxController {
    private String lastDiagnosticSignature = "";
    private final List<UsbRequest> kishiAuxDiagnosticRequests = new ArrayList<>();
    private Thread kishiAuxDiagnosticThread;
    private volatile boolean kishiAuxDiagnosticsRunning;

    private static final int XB360_IFACE_SUBCLASS = 93;
    private static final int XB360_IFACE_PROTOCOL = 1; // Wired only

    private static final int[] SUPPORTED_VENDORS = {
            0x0079, // GPD Win 2
            0x044f, // Thrustmaster
            0x045e, // Microsoft
            0x046d, // Logitech
            0x056e, // Elecom
            0x06a3, // Saitek
            0x0738, // Mad Catz
            0x07ff, // Mad Catz
            0x0e6f, // Unknown
            0x0f0d, // Hori
            0x1038, // SteelSeries
            0x11c9, // Nacon
            0x1209, // Ardwiino
            0x12ab, // Unknown
            0x1430, // RedOctane
            0x146b, // BigBen
            0x1532, // Razer Sabertooth
            0x15e4, // Numark
            0x162e, // Joytech
            0x1689, // Razer Onza
            0x1949, // Lab126 (Amazon Luna)
            0x1bad, // Harmonix
            0x20d6, // PowerA
            0x24c6, // PowerA
            0x2f24, // GameSir
            0x2dc8, // 8BitDo
            0x413d, // 小鸡启明星
            0x3537,//小鸡启明星6300固件
    };

    private static class KishiAuxRequestInfo {
        final int interfaceIndex;
        final UsbEndpoint endpoint;
        final ByteBuffer buffer;
        String lastReport = "";

        KishiAuxRequestInfo(int interfaceIndex, UsbEndpoint endpoint) {
            this.interfaceIndex = interfaceIndex;
            this.endpoint = endpoint;
            this.buffer = ByteBuffer.allocateDirect(Math.max(64, endpoint.getMaxPacketSize()));
        }
    }

    private void startKishiAuxDiagnostics() {
        if (device.getVendorId() != 0x1532 || device.getProductId() != 0x0037) {
            return;
        }

        kishiAuxDiagnosticsRunning = true;
        kishiAuxDiagnosticRequests.clear();

        for (int interfaceIndex = 1; interfaceIndex < device.getInterfaceCount(); interfaceIndex++) {
            UsbInterface iface = device.getInterface(interfaceIndex);

            for (int endpointIndex = 0; endpointIndex < iface.getEndpointCount(); endpointIndex++) {
                UsbEndpoint endpoint = iface.getEndpoint(endpointIndex);

                if (endpoint.getDirection() != UsbConstants.USB_DIR_IN ||
                        endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_INT) {
                    continue;
                }

                UsbRequest request = new UsbRequest();
                if (!request.initialize(connection, endpoint)) {
                    reportRawDiagnostic(String.format(
                            "KISHI AUX INIT FAIL IF%d EP%02X",
                            interfaceIndex, endpoint.getAddress()));
                    request.close();
                    continue;
                }

                KishiAuxRequestInfo info = new KishiAuxRequestInfo(interfaceIndex, endpoint);
                request.setClientData(info);

                if (!request.queue(info.buffer)) {
                    reportRawDiagnostic(String.format(
                            "KISHI AUX QUEUE FAIL IF%d EP%02X",
                            interfaceIndex, endpoint.getAddress()));
                    request.close();
                    continue;
                }

                kishiAuxDiagnosticRequests.add(request);
            }
        }

        reportRawDiagnostic(String.format(
                "KISHI AUX READY | interrupt endpoints=%d",
                kishiAuxDiagnosticRequests.size()));

        kishiAuxDiagnosticThread = new Thread(() -> {
            while (kishiAuxDiagnosticsRunning && !Thread.currentThread().isInterrupted()) {
                UsbRequest completed = connection.requestWait();
                if (completed == null) {
                    if (kishiAuxDiagnosticsRunning) {
                        reportRawDiagnostic("KISHI AUX requestWait returned null");
                    }
                    break;
                }

                Object clientData = completed.getClientData();
                if (!(clientData instanceof KishiAuxRequestInfo)) {
                    continue;
                }

                KishiAuxRequestInfo info = (KishiAuxRequestInfo) clientData;
                int result = info.buffer.position();
                info.buffer.flip();

                StringBuilder hex = new StringBuilder();
                while (info.buffer.hasRemaining()) {
                    if (hex.length() > 0) {
                        hex.append(' ');
                    }
                    hex.append(String.format("%02X", info.buffer.get() & 0xFF));
                }

                String report = String.format(
                        "KISHI AUX IF%d EP%02X | len=%d | %s",
                        info.interfaceIndex,
                        info.endpoint.getAddress(),
                        result,
                        hex.toString());

                if (!report.equals(info.lastReport)) {
                    info.lastReport = report;
                    reportRawDiagnostic(report);
                }

                info.buffer.clear();
                if (kishiAuxDiagnosticsRunning && !completed.queue(info.buffer)) {
                    reportRawDiagnostic(String.format(
                            "KISHI AUX REQUEUE FAIL IF%d EP%02X",
                            info.interfaceIndex, info.endpoint.getAddress()));
                    break;
                }
            }
        }, "KishiAuxInterruptReader");

        kishiAuxDiagnosticThread.start();
    }

    private void stopKishiAuxDiagnostics() {
        kishiAuxDiagnosticsRunning = false;

        for (UsbRequest request : kishiAuxDiagnosticRequests) {
            request.cancel();
        }

        if (kishiAuxDiagnosticThread != null) {
            kishiAuxDiagnosticThread.interrupt();
            kishiAuxDiagnosticThread = null;
        }

        for (UsbRequest request : kishiAuxDiagnosticRequests) {
            request.close();
        }
        kishiAuxDiagnosticRequests.clear();
    }

    @Override
    public boolean start() {
        // For the Kishi V3 Pro XL we deliberately leave auxiliary HID interfaces
        // to Android. Do not attach the temporary raw UsbRequest sniffer here.
        return super.start();
    }

    @Override
    public void stop() {
        super.stop();
    }

    public static boolean canClaimDevice(UsbDevice device) {
        for (int supportedVid : SUPPORTED_VENDORS) {
            if (device.getVendorId() == supportedVid &&
                    device.getInterfaceCount() >= 1 &&
                    device.getInterface(0).getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                    device.getInterface(0).getInterfaceSubclass() == XB360_IFACE_SUBCLASS &&
                    device.getInterface(0).getInterfaceProtocol() == XB360_IFACE_PROTOCOL) {
                return true;
            }
        }

        return false;
    }

    public Xbox360Controller(UsbDevice device, UsbDeviceConnection connection, int deviceId, UsbDriverListener listener) {
        super(device, connection, deviceId, listener);
    }

    private int unsignByte(byte b) {
        if (b < 0) {
            return b + 256;
        }
        else {
            return b;
        }
    }
    private void reportRazerRawDiagnostic(ByteBuffer buffer) {
        if (buffer.remaining() < 4) {
            return;
        }

        ByteBuffer copy = buffer.asReadOnlyBuffer();
        int start = copy.position();
        int length = copy.remaining();
        int buttons1 = copy.get(start + 2) & 0xFF;
        int buttons2 = copy.get(start + 3) & 0xFF;

        StringBuilder extras = new StringBuilder();
        if (length > 14) {
            for (int i = start + 14; i < start + length && i < start + 22; i++) {
                if (extras.length() > 0) {
                    extras.append(' ');
                }
                extras.append(String.format("%02X", copy.get(i) & 0xFF));
            }
        }

        String signature = String.format("%02X:%02X:%s",
                buttons1, buttons2, extras.toString());
        if (signature.equals(lastDiagnosticSignature)) {
            return;
        }

        lastDiagnosticSignature = signature;
        reportRawDiagnostic(String.format("XINPUT 360 | VID:PID=%04X:%04X | buttons=%02X %02X | len=%d | extra=%s",
                        device.getVendorId(), device.getProductId(),
                        buttons1, buttons2, length,
                        extras.length() == 0 ? "-" : extras.toString()));
    }



    @Override
    protected boolean handleRead(ByteBuffer buffer) {
        reportRazerRawDiagnostic(buffer);
        if (buffer.remaining() < 14) {
            LimeLog.severe("Read too small: "+buffer.remaining());
            return false;
        }

        // Skip first short
        buffer.position(buffer.position() + 2);

        // DPAD
        byte b = buffer.get();
        setButtonFlag(ControllerPacket.LEFT_FLAG, b & 0x04);
        setButtonFlag(ControllerPacket.RIGHT_FLAG, b & 0x08);
        setButtonFlag(ControllerPacket.UP_FLAG, b & 0x01);
        setButtonFlag(ControllerPacket.DOWN_FLAG, b & 0x02);

        // Start/Select
        setButtonFlag(ControllerPacket.PLAY_FLAG, b & 0x10);
        setButtonFlag(ControllerPacket.BACK_FLAG, b & 0x20);

        // LS/RS
        setButtonFlag(ControllerPacket.LS_CLK_FLAG, b & 0x40);
        setButtonFlag(ControllerPacket.RS_CLK_FLAG, b & 0x80);

        // ABXY buttons
        b = buffer.get();
        setButtonFlag(ControllerPacket.A_FLAG, b & 0x10);
        setButtonFlag(ControllerPacket.B_FLAG, b & 0x20);
        setButtonFlag(ControllerPacket.X_FLAG, b & 0x40);
        setButtonFlag(ControllerPacket.Y_FLAG, b & 0x80);

        // LB/RB
        setButtonFlag(ControllerPacket.LB_FLAG, b & 0x01);
        setButtonFlag(ControllerPacket.RB_FLAG, b & 0x02);

        // Xbox button
        setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, b & 0x04);

        // Triggers
        leftTrigger = unsignByte(buffer.get()) / 255.0f;
        rightTrigger = unsignByte(buffer.get()) / 255.0f;

        // Left stick
        leftStickX = buffer.getShort() / 32767.0f;
        leftStickY = ~buffer.getShort() / 32767.0f;

        // Right stick
        rightStickX = buffer.getShort() / 32767.0f;
        rightStickY = ~buffer.getShort() / 32767.0f;

        // Return true to send input
        return true;
    }

    private boolean sendLedCommand(byte command) {
        byte[] commandBuffer = {0x01, 0x03, command};

        int res = connection.bulkTransfer(outEndpt, commandBuffer, commandBuffer.length, 3000);
        if (res != commandBuffer.length) {
            LimeLog.warning("LED set transfer failed: "+res);
            return false;
        }

        return true;
    }

    @Override
    protected boolean doInit() {
        // Turn the LED on corresponding to our device ID
        sendLedCommand((byte)(2 + (getControllerId() % 4)));

        // No need to fail init if the LED command fails
        return true;
    }

    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        byte[] data = {
                0x00, 0x08, 0x00,
                (byte)(lowFreqMotor >> 8), (byte)(highFreqMotor >> 8),
                0x00, 0x00, 0x00
        };
        int res = connection.bulkTransfer(outEndpt, data, data.length, 100);
        if (res != data.length) {
            LimeLog.warning("Rumble transfer failed: "+res);
        }
    }

    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        // Trigger motors not present on Xbox 360 controllers
    }
}
