package com.limelight.binding.audio;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.jni.MoonBridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MicrophoneCaptureManager {
    public interface LevelListener {
        void onLevelUpdate(double level, boolean signalDetected, String status);
    }

    public static final class InputDeviceEntry {
        public final int id;
        public final String label;

        public InputDeviceEntry(int id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    public static final int DEVICE_ID_BLUETOOTH_HEADSET = -100;

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_COUNT = 1;
    private static final int FRAME_SIZE = 960;
    private static final int DEFAULT_BITRATE = 24000;
    private static final int LEVEL_UPDATE_INTERVAL_MS = 50;
    private static final int SIGNAL_PEAK_THRESHOLD = 250;
    private static final double SIGNAL_RMS_THRESHOLD = 90.0;
    private static final double PREVIEW_RMS_FLOOR = 40.0;
    private static final double PREVIEW_RMS_CEILING = 4000.0;
    private static final double PREVIEW_PEAK_CEILING = 12000.0;
    private static final double PREVIEW_DECAY_FACTOR = 0.84;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AudioRecord audioRecord;
    private Thread captureThread;
    private volatile boolean running;
    private boolean streamingToHost;
    private LevelListener levelListener;
    private String currentStatus;
    private double currentLevel;
    private boolean signalDetected;

    private AudioManager routedAudioManager;
    private boolean communicationRouteActive;
    private int previousAudioMode = AudioManager.MODE_NORMAL;

    public MicrophoneCaptureManager(Context context) {
        this.context = context.getApplicationContext();
        this.currentStatus = string(R.string.microphone_preview_inactive);
    }

    public static boolean hasRecordAudioPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED;
    }

    public static List<InputDeviceEntry> getAvailableInputDevices(Context context) {
        Map<String, InputDeviceEntry> uniqueEntries = new LinkedHashMap<>();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return new ArrayList<>();
        }

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return new ArrayList<>();
        }

        boolean bluetoothFound = false;

        for (AudioDeviceInfo deviceInfo : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            String label = describeDevice(deviceInfo);
            int entryId = deviceInfo.getId();

            if (isBluetoothHeadsetDevice(deviceInfo)) {
                // Bluetooth headset microphones are communication devices. Store a
                // semantic ID instead of Android's transient device ID so capture
                // can establish the HFP/communication route before opening AudioRecord.
                entryId = DEVICE_ID_BLUETOOTH_HEADSET;
                bluetoothFound = true;
            }

            if (!uniqueEntries.containsKey(label)) {
                uniqueEntries.put(label, new InputDeviceEntry(entryId, label));
            }
        }

        if (!bluetoothFound) {
            for (AudioDeviceInfo deviceInfo : audioManager.getDevices(AudioManager.GET_DEVICES_ALL)) {
                if (deviceInfo.isSource() && isBluetoothHeadsetDevice(deviceInfo)) {
                    String label = describeDevice(deviceInfo);
                    uniqueEntries.put(label,
                            new InputDeviceEntry(DEVICE_ID_BLUETOOTH_HEADSET, label));
                    bluetoothFound = true;
                    break;
                }
            }
        }

        if (!bluetoothFound && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                for (AudioDeviceInfo deviceInfo : audioManager.getAvailableCommunicationDevices()) {
                    if (isBluetoothHeadsetDevice(deviceInfo)) {
                        String label = deviceInfo.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET ?
                                "Bluetooth LE Audio headset microphone" :
                                "Bluetooth headset microphone";
                        uniqueEntries.put(label,
                                new InputDeviceEntry(DEVICE_ID_BLUETOOTH_HEADSET, label));
                        break;
                    }
                }
            }
            catch (SecurityException | IllegalStateException e) {
                LimeLog.warning("Unable to enumerate Bluetooth communication devices: " +
                        e.getMessage());
            }
        }

        return new ArrayList<>(uniqueEntries.values());
    }

    public boolean startPreview(int preferredDeviceId, LevelListener listener) {
        return startCapture(preferredDeviceId, listener, false);
    }

    public boolean startStreaming(int preferredDeviceId, LevelListener listener) {
        return startCapture(preferredDeviceId, listener, true);
    }

    public void stop() {
        AudioRecord recordToRelease;
        Thread threadToJoin;
        boolean wasStreaming;

        running = false;
        recordToRelease = audioRecord;
        threadToJoin = captureThread;
        wasStreaming = streamingToHost;

        if (recordToRelease != null) {
            try {
                recordToRelease.stop();
            }
            catch (IllegalStateException ignored) {
            }
        }

        if (threadToJoin != null) {
            try {
                threadToJoin.join(2000);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        captureThread = null;
        audioRecord = null;
        if (recordToRelease != null) {
            recordToRelease.release();
        }

        if (wasStreaming) {
            MoonBridge.stopMicrophoneStreaming();
            MoonBridge.cleanupMicrophoneEncoder();
        }
        streamingToHost = false;

        deactivateCommunicationRoute();

        currentLevel = 0.0;
        signalDetected = false;
        dispatchStatus(string(R.string.microphone_preview_inactive), 0.0, false);
    }

    private boolean startCapture(int preferredDeviceId, LevelListener listener, boolean streamToHost) {
        CaptureConfig config;
        AudioRecord newRecord;
        final int bufferSamples;

        stop();
        levelListener = listener;

        if (!hasRecordAudioPermission(context)) {
            dispatchStatus(string(R.string.microphone_preview_permission_required), 0.0, false);
            return false;
        }

        if (streamToHost && !MoonBridge.isMicrophoneStreamActive()) {
            dispatchStatus(string(R.string.microphone_host_not_negotiated), 0.0, false);
            return false;
        }

        boolean bluetoothRequested =
                preferredDeviceId == DEVICE_ID_BLUETOOTH_HEADSET ||
                isBluetoothDeviceId(preferredDeviceId);

        if (bluetoothRequested) {
            // WH-CH720N and Evolve2 65 expose their microphones through the
            // Bluetooth HFP/HSP communication path. Merely calling
            // AudioRecord.setPreferredDevice() while A2DP is active can leave us
            // with a selectable device that delivers silence. Establish the same
            // Android communication route used by voice/calling apps first.
            boolean routeActivated = activateBluetoothCommunicationRoute();
            LimeLog.info("Bluetooth microphone communication route active=" + routeActivated);

            AudioDeviceInfo bluetoothInput = waitForBluetoothInputDevice();
            if (bluetoothInput != null) {
                preferredDeviceId = bluetoothInput.getId();
                LimeLog.info("Resolved active Bluetooth microphone input to device ID " +
                        preferredDeviceId);
            }
            else {
                deactivateCommunicationRoute();
                dispatchStatus(string(R.string.microphone_preview_selected_missing), 0.0, false);
                return false;
            }
        }

        config = createCaptureConfig(preferredDeviceId);
        if (config == null) {
            dispatchStatus(string(R.string.microphone_preview_open_failed), 0.0, false);
            return false;
        }

        newRecord = config.record;
        bufferSamples = config.bufferSamples;

        if (streamToHost && MoonBridge.setupMicrophoneEncoder(SAMPLE_RATE, CHANNEL_COUNT, DEFAULT_BITRATE) != 0) {
            newRecord.release();
            dispatchStatus(string(R.string.microphone_encoder_setup_failed), 0.0, false);
            return false;
        }

        try {
            newRecord.startRecording();
        }
        catch (IllegalStateException e) {
            if (streamToHost) {
                MoonBridge.cleanupMicrophoneEncoder();
            }
            newRecord.release();
            dispatchStatus(string(R.string.microphone_capture_start_failed), 0.0, false);
            return false;
        }

        if (newRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            if (streamToHost) {
                MoonBridge.cleanupMicrophoneEncoder();
            }
            newRecord.release();
            dispatchStatus(string(R.string.microphone_capture_start_failed), 0.0, false);
            return false;
        }

        LimeLog.info(String.format((Locale) null,
                "Microphone capture active using source %s, device %s, buffer %d samples",
                config.sourceName,
                config.deviceLabel,
                bufferSamples));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioDeviceInfo routedDevice = newRecord.getRoutedDevice();
            LimeLog.info("Microphone routed input: " +
                    (routedDevice != null ? describeDevice(routedDevice) + " (#" + routedDevice.getId() + ")" : "unknown"));
        }

        audioRecord = newRecord;
        streamingToHost = streamToHost;
        currentStatus = config.statusMessage;
        currentLevel = 0.0;
        signalDetected = false;
        running = true;

        if (streamToHost) {
            MoonBridge.startMicrophoneStreaming();
        }

        captureThread = new Thread(() -> runCaptureLoop(bufferSamples), streamToHost ? "MicStreamCapture" : "MicPreviewCapture");
        captureThread.start();
        dispatchStatus(config.statusMessage, 0.0, false);
        return true;
    }

    private void runCaptureLoop(int bufferSamples) {
        short[] readBuffer = new short[bufferSamples];
        int pendingPeak = 0;
        double pendingRms = 0.0;
        long lastUpdateTime = SystemClock.elapsedRealtime();

        while (running && audioRecord != null) {
            int samplesRead;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                samplesRead = audioRecord.read(readBuffer, 0, readBuffer.length, AudioRecord.READ_BLOCKING);
            }
            else {
                samplesRead = audioRecord.read(readBuffer, 0, readBuffer.length);
            }

            if (samplesRead <= 0) {
                continue;
            }

            SignalStats signalStats = calculateSignalStats(readBuffer, samplesRead);
            if (signalStats.peak > pendingPeak) {
                pendingPeak = signalStats.peak;
            }
            if (signalStats.rms > pendingRms) {
                pendingRms = signalStats.rms;
            }

            if (streamingToHost) {
                int queued = MoonBridge.queueMicrophonePcm(readBuffer, samplesRead);
                if (queued < 0) {
                    LimeLog.warning("Failed to queue microphone PCM data for native encoding");
                }
            }

            long now = SystemClock.elapsedRealtime();
            if (now - lastUpdateTime >= LEVEL_UPDATE_INTERVAL_MS) {
                double instantaneousLevel = calculatePreviewLevel(pendingPeak, pendingRms);
                double nextLevel = Math.max(instantaneousLevel, currentLevel * PREVIEW_DECAY_FACTOR);
                boolean nextSignalDetected = pendingPeak >= SIGNAL_PEAK_THRESHOLD ||
                        pendingRms >= SIGNAL_RMS_THRESHOLD;
                pendingPeak = 0;
                pendingRms = 0.0;
                lastUpdateTime = now;
                currentLevel = nextLevel;
                signalDetected = nextSignalDetected;
                dispatchStatus(currentStatus, nextLevel, nextSignalDetected);
            }
        }
    }

    private CaptureConfig createCaptureConfig(int preferredDeviceId) {
        int minBufferSizeBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSizeBytes <= 0) {
            minBufferSizeBytes = FRAME_SIZE * 4 * 2;
        }
        int bufferSizeBytes = Math.max(minBufferSizeBytes, FRAME_SIZE * 4 * 2);
        int bufferSamples = Math.max(FRAME_SIZE, bufferSizeBytes / 2);
        boolean missingSelectedDevice = false;
        AudioDeviceInfo preferredDevice = null;
        String preferredDeviceLabel = string(R.string.microphone_device_default);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && preferredDeviceId != 0) {
            preferredDevice = findInputDevice(preferredDeviceId);
            if (preferredDevice != null) {
                preferredDeviceLabel = describeDevice(preferredDevice);
            }
            else {
                missingSelectedDevice = true;
            }
        }

        boolean directBluetooth = preferredDevice != null && isBluetoothHeadsetDevice(preferredDevice);
        int[] preferredSources = directBluetooth ?
                new int[] {
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        MediaRecorder.AudioSource.MIC,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? MediaRecorder.AudioSource.UNPROCESSED : -1
                } :
                new int[] {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? MediaRecorder.AudioSource.UNPROCESSED : -1,
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        MediaRecorder.AudioSource.MIC
                };

        for (int source : preferredSources) {
            AudioRecord candidate;

            if (source < 0) {
                continue;
            }

            candidate = buildAudioRecord(source, bufferSizeBytes);
            if (candidate == null) {
                continue;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && preferredDevice != null) {
                boolean preferredApplied = candidate.setPreferredDevice(preferredDevice);
                if (!preferredApplied) {
                    LimeLog.info("Preferred microphone device selection was rejected by AudioRecord");
                    candidate.release();
                    continue;
                }
            }

            if (candidate.getState() != AudioRecord.STATE_INITIALIZED) {
                candidate.release();
                continue;
            }

            CaptureConfig config = new CaptureConfig();
            config.record = candidate;
            config.bufferSamples = bufferSamples;
            config.sourceName = audioSourceToString(source);
            config.deviceLabel = preferredDevice != null ? preferredDeviceLabel : string(R.string.microphone_device_default);
            config.statusMessage = missingSelectedDevice ?
                    string(R.string.microphone_preview_selected_missing) :
                    (preferredDevice != null ?
                            string(R.string.microphone_preview_selected_active) :
                            string(R.string.microphone_preview_default_active));
            return config;
        }

        return null;
    }

    private AudioRecord buildAudioRecord(int audioSource, int bufferSizeBytes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return new AudioRecord.Builder()
                    .setAudioSource(audioSource)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(bufferSizeBytes)
                    .build();
        }

        return new AudioRecord(audioSource,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeBytes);
    }

    private boolean activateBluetoothCommunicationRoute() {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return false;
        }

        routedAudioManager = audioManager;
        previousAudioMode = audioManager.getMode();

        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioDeviceInfo communicationDevice = null;
                for (AudioDeviceInfo deviceInfo : audioManager.getAvailableCommunicationDevices()) {
                    if (isBluetoothHeadsetDevice(deviceInfo)) {
                        communicationDevice = deviceInfo;
                        break;
                    }
                }

                if (communicationDevice == null || !audioManager.setCommunicationDevice(communicationDevice)) {
                    audioManager.setMode(previousAudioMode);
                    routedAudioManager = null;
                    return false;
                }
            }
            else {
                audioManager.startBluetoothSco();
                audioManager.setBluetoothScoOn(true);
            }

            communicationRouteActive = true;
            return true;
        }
        catch (SecurityException | IllegalStateException e) {
            LimeLog.warning("Unable to activate Bluetooth communication route: " + e.getMessage());
            try {
                audioManager.setMode(previousAudioMode);
            }
            catch (RuntimeException ignored) {
            }
            routedAudioManager = null;
            communicationRouteActive = false;
            return false;
        }
    }

    private void deactivateCommunicationRoute() {
        if (!communicationRouteActive || routedAudioManager == null) {
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                routedAudioManager.clearCommunicationDevice();
            }
            else {
                routedAudioManager.setBluetoothScoOn(false);
                routedAudioManager.stopBluetoothSco();
            }
        }
        catch (SecurityException | IllegalStateException e) {
            LimeLog.warning("Unable to clear Bluetooth communication route: " + e.getMessage());
        }

        try {
            routedAudioManager.setMode(previousAudioMode);
        }
        catch (RuntimeException e) {
            LimeLog.warning("Unable to restore previous audio mode: " + e.getMessage());
        }

        communicationRouteActive = false;
        routedAudioManager = null;
    }

    private AudioDeviceInfo waitForBluetoothInputDevice() {
        for (int attempt = 0; attempt < 10; attempt++) {
            AudioDeviceInfo deviceInfo = findBluetoothInputDevice();
            if (deviceInfo != null) {
                return deviceInfo;
            }

            SystemClock.sleep(100);
        }

        return null;
    }

    private AudioDeviceInfo findBuiltInInputDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null;
        }

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return null;
        }

        for (AudioDeviceInfo deviceInfo : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (deviceInfo.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                return deviceInfo;
            }
        }

        return null;
    }

    private AudioDeviceInfo findBluetoothInputDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null;
        }

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return null;
        }

        for (AudioDeviceInfo deviceInfo : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (isBluetoothHeadsetDevice(deviceInfo)) {
                return deviceInfo;
            }
        }

        for (AudioDeviceInfo deviceInfo : audioManager.getDevices(AudioManager.GET_DEVICES_ALL)) {
            if (deviceInfo.isSource() && isBluetoothHeadsetDevice(deviceInfo)) {
                return deviceInfo;
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                for (AudioDeviceInfo deviceInfo : audioManager.getAvailableCommunicationDevices()) {
                    if (deviceInfo.isSource() && isBluetoothHeadsetDevice(deviceInfo)) {
                        return deviceInfo;
                    }
                }
            }
            catch (SecurityException | IllegalStateException e) {
                LimeLog.warning("Unable to resolve Bluetooth microphone candidate: " +
                        e.getMessage());
            }
        }

        return null;
    }

    private boolean isBluetoothDeviceId(int deviceId) {
        if (deviceId == 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return false;
        }

        for (AudioDeviceInfo deviceInfo : audioManager.getDevices(AudioManager.GET_DEVICES_ALL)) {
            if (deviceInfo.getId() == deviceId && isBluetoothHeadsetDevice(deviceInfo)) {
                return true;
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                for (AudioDeviceInfo deviceInfo : audioManager.getAvailableCommunicationDevices()) {
                    if (deviceInfo.getId() == deviceId && isBluetoothHeadsetDevice(deviceInfo)) {
                        return true;
                    }
                }
            }
            catch (SecurityException | IllegalStateException e) {
                LimeLog.warning("Unable to inspect Bluetooth communication device: " +
                        e.getMessage());
            }
        }

        return false;
    }

    private AudioDeviceInfo findInputDevice(int deviceId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null;
        }

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return null;
        }

        for (AudioDeviceInfo deviceInfo : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (deviceInfo.getId() == deviceId) {
                return deviceInfo;
            }
        }

        return null;
    }

    private void dispatchStatus(String status, double level, boolean detected) {
        currentStatus = status;
        mainHandler.post(() -> {
            if (levelListener != null) {
                levelListener.onLevelUpdate(level, detected, status);
            }
        });
    }

    private String string(int resId) {
        return context.getString(resId);
    }

    private static SignalStats calculateSignalStats(short[] samples, int sampleCount) {
        int peak = 0;
        double sumSquares = 0.0;

        for (int i = 0; i < sampleCount; i++) {
            int sample = Math.abs(samples[i]);
            if (sample > peak) {
                peak = sample;
            }
            sumSquares += (double) sample * sample;
        }

        SignalStats signalStats = new SignalStats();
        signalStats.peak = peak;
        signalStats.rms = sampleCount > 0 ? Math.sqrt(sumSquares / sampleCount) : 0.0;
        return signalStats;
    }

    private static double calculatePreviewLevel(int peak, double rms) {
        double rmsLevel = (Math.log10(Math.max(rms, PREVIEW_RMS_FLOOR)) -
                Math.log10(PREVIEW_RMS_FLOOR)) /
                (Math.log10(PREVIEW_RMS_CEILING) - Math.log10(PREVIEW_RMS_FLOOR));
        double peakLevel = Math.min(1.0, peak / PREVIEW_PEAK_CEILING);
        return Math.max(0.0, Math.min(1.0, Math.max(rmsLevel, peakLevel)));
    }

    private static String audioSourceToString(int audioSource) {
        if (audioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
            return "VOICE_COMMUNICATION";
        }
        if (audioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION) {
            return "VOICE_RECOGNITION";
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                audioSource == MediaRecorder.AudioSource.UNPROCESSED) {
            return "UNPROCESSED";
        }
        return "MIC";
    }

    private static boolean isBluetoothHeadsetDevice(AudioDeviceInfo deviceInfo) {
        int type = deviceInfo.getType();
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        type == AudioDeviceInfo.TYPE_BLE_HEADSET);
    }

    private static String describeDevice(AudioDeviceInfo deviceInfo) {
        switch (deviceInfo.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                return "Built-in microphone";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "Bluetooth headset microphone (direct)";
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
                return "Bluetooth LE Audio headset microphone";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return "Bluetooth audio input";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "Wired headset microphone";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "USB microphone";
            default:
                CharSequence productName = deviceInfo.getProductName();
                if (productName != null && productName.length() > 0) {
                    return productName.toString();
                }
                return "Input device " + deviceInfo.getId();
        }
    }

    private static final class SignalStats {
        int peak;
        double rms;
    }

    private static final class CaptureConfig {
        AudioRecord record;
        int bufferSamples;
        String sourceName;
        String deviceLabel;
        String statusMessage;
    }
}
