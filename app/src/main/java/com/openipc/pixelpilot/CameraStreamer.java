package com.openipc.pixelpilot;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CameraStreamer — VTX pipeline: Main Camera → MediaCodec H.264 → UDP (localhost:8001).
 *
 * Always uses the main camera (ID "0").
 * All camera and encoder parameters are supplied via {@link CameraConfig}.
 */
public class CameraStreamer {
    private static final String TAG = "CameraStreamer";
    private static final int DESTINATION_PORT = 8001;

    private final Context context;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private MediaCodec encoder;
    private Surface encoderSurface;
    private SurfaceTexture dummySurfaceTexture;
    private Surface dummySurface;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private DatagramSocket udpSocket;
    private InetAddress destinationAddress;

    private boolean isStreaming = false;
    private CameraConfig currentConfig = new CameraConfig();

    public CameraStreamer(Context context) {
        this.context = context;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Bootstraps the entire video pipeline.
     * 1. Starts a background thread.
     * 2. Opens a UDP datagram socket to loopback:5600.
     * 3. Configures the MediaCodec hardware encoder.
     * 4. Requests control of the physical Camera2 device.
     *
     * @param previewSurface The UI Surface to draw the camera preview to (often null in headless mode).
     * @param config The imported CameraConfig (.vtxcam) determining all stream parameters.
     * @param enablePreview Whether to route frames to the previewSurface (if false, a Dummy Surface is used).
     */
    public synchronized void startStreaming(Surface previewSurface, CameraConfig config, boolean enablePreview) {
        if (enablePreview && (previewSurface == null || !previewSurface.isValid())) {
            Log.e(TAG, "Cannot start: invalid preview surface provided for preview enabled mode");
            return;
        }
        if (isStreaming) stopStreaming();
        isStreaming = true;
        currentConfig = config;

        Log.i(TAG, "startStreaming: " + config.getSummary() + (enablePreview ? " with preview" : " without preview"));
        startBackgroundThread();
        setupUDP();
        setupEncoder();
        openCamera(previewSurface, enablePreview);
    }

    public synchronized void stopStreaming() {
        isStreaming = false;
        if (captureSession != null) {
            try { captureSession.stopRepeating(); captureSession.close(); } catch (Exception ignored) {}
            captureSession = null;
        }
        if (dummySurface != null) {
            dummySurface.release();
            dummySurface = null;
        }
        if (dummySurfaceTexture != null) {
            dummySurfaceTexture.release();
            dummySurfaceTexture = null;
        }
        if (cameraDevice != null) {
            try {
                cameraDevice.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing camera", e);
            }
            cameraDevice = null;
        }
        if (encoderSurface != null) { encoderSurface.release(); encoderSurface = null; }
        if (encoder != null) { try { encoder.stop(); encoder.release(); } catch (Exception ignored) {} encoder = null; }
        if (udpSocket != null) { udpSocket.close(); udpSocket = null; }
        stopBackgroundThread();
    }

    // ── Background thread ──────────────────────────────────────────────────

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try { backgroundThread.join(); } catch (InterruptedException ignored) {}
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    // ── Callbacks ───────────────────────────────────────────────────────────
    
    public interface LatencyCallback {
        void onLatencyUpdate(int rawLatencyMs, int avgLatencyMs);
    }

    private LatencyCallback latencyCallback;
    
    public void setLatencyCallback(LatencyCallback callback) {
        this.latencyCallback = callback;
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    private void setupUDP() {
        try {
            udpSocket = new DatagramSocket();
            destinationAddress = InetAddress.getByName("127.0.0.1");
        } catch (Exception e) {
            Log.e(TAG, "UDP setup failed", e);
        }
    }

    // ── Encoder ───────────────────────────────────────────────────────────

    /**
     * Configures the Android hardware MediaCodec for low-latency H.264 / H.265 encoding.
     * Starts the encoder asynchronously, routing NAL units to sendOverUDP() upon output.
     */
    private void setupEncoder() {
        CameraConfig cfg = currentConfig;
        String mime = "h265".equalsIgnoreCase(cfg.encoder.codec)
                ? MediaFormat.MIMETYPE_VIDEO_HEVC
                : MediaFormat.MIMETYPE_VIDEO_AVC;
        try {
            encoder = MediaCodec.createEncoderByType(mime);
            MediaFormat fmt = MediaFormat.createVideoFormat(mime, cfg.camera.width, cfg.camera.height);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, cfg.encoder.bitrate);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, cfg.camera.fps);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, cfg.encoder.keyframeInterval);
            if (cfg.encoder.encoderPriority > 0) {
                fmt.setInteger(MediaFormat.KEY_PRIORITY, cfg.encoder.encoderPriority);
            }
            if (cfg.encoder.encoderLatency > 0) {
                fmt.setInteger(MediaFormat.KEY_LATENCY, cfg.encoder.encoderLatency);
            }
            // Some drivers hate KEY_MAX_B_FRAMES if it's 0 (even though 0 is default baseline), so omit if 0
            if (cfg.encoder.bFrames > 0) {
                fmt.setInteger(MediaFormat.KEY_MAX_B_FRAMES, cfg.encoder.bFrames);
            }
            int bitrateMode = (cfg.encoder.bitrateMode == 0) ? 
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR : 
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR;
            fmt.setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode);
            // If operating rate is greater than 0, set it to pre-allocate hardware resources.
            if (cfg.encoder.operatingRate > 0) {
                fmt.setInteger(MediaFormat.KEY_OPERATING_RATE, cfg.encoder.operatingRate);
            } else {
                fmt.setInteger(MediaFormat.KEY_OPERATING_RATE, cfg.camera.fps);
            }
            if (cfg.encoder.intraRefreshPeriod > 0) {
                fmt.setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, cfg.encoder.intraRefreshPeriod);
            }
            if (cfg.encoder.codecProfile > 0) {
                fmt.setInteger(MediaFormat.KEY_PROFILE, cfg.encoder.codecProfile);
            }
            if (cfg.encoder.codecLevel > 0) {
                fmt.setInteger(MediaFormat.KEY_LEVEL, cfg.encoder.codecLevel);
            }
            
            // --- AGGRESSIVE LOW LATENCY OVERRIDES REMOVED ---
            // The HEVC (H.265) hardware encoder explicitly crashes when
            // these flags are present, and they had no measurable effect on H.264.
            
            encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (Exception e) {
            // Safe fallback: minimal H.264 settings
            Log.e(TAG, "Encoder configure failed, using safe defaults: " + e.getMessage());
            try {
                if (encoder != null) { try { encoder.release(); } catch (Exception ignored) {} }
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                MediaFormat safe = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, cfg.camera.width, cfg.camera.height);
                safe.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                safe.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000);
                safe.setInteger(MediaFormat.KEY_FRAME_RATE, cfg.camera.fps);
                safe.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                encoder.configure(safe, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                Log.i(TAG, "Encoder started with safe fallback settings");
            } catch (Exception e2) {
                Log.e(TAG, "Encoder fallback also failed", e2);
                return;
            }
        }

        encoderSurface = encoder.createInputSurface();

        encoder.setCallback(new MediaCodec.Callback() {
            private byte[] spsPpsCache = null;
            private final ArrayList<Integer> latencyHistory = new ArrayList<>(30);
            private int frameCount = 0;

            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {}

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index,
                                                @NonNull MediaCodec.BufferInfo info) {
                // Camera2 PTS is on CLOCK_MONOTONIC (pauses during sleep).
                // SystemClock.uptimeMillis() is also CLOCK_MONOTONIC — same domain, no calibration needed.
                // elapsedRealtimeNanos() (CLOCK_BOOTTIME) would include sleep time and cause a large epoch gap.
                long currentUptimeUs = android.os.SystemClock.uptimeMillis() * 1000L;
                int latencyMs = (int) ((currentUptimeUs - info.presentationTimeUs) / 1000);

                frameCount++;
                // Filter out invalid values: codec-config frames have ptsUs=0 (huge positive delta)
                // and genuine HW encoder latency is always well under 500ms
                if (latencyMs > 0 && latencyMs < 500) {
                    latencyHistory.add(latencyMs);
                    if (latencyHistory.size() > 30) latencyHistory.remove(0);

                    int sum = 0;
                    for (int lat : latencyHistory) sum += lat;
                    int avgLatencyMs = sum / latencyHistory.size();

                    if (frameCount % 60 == 0) {
                        try {
                            com.openipc.mavlink.MavlinkNative.nativeSendNamedValueFloat("EncLat", (float) avgLatencyMs);
                        } catch (UnsatisfiedLinkError e) {
                            Log.w(TAG, "Mavlink native library not loaded yet");
                        }
                    }

                    if (latencyCallback != null) {
                        final int rawLat = latencyMs;
                        final int avgLat = avgLatencyMs;
                        new Handler(Looper.getMainLooper()).post(() ->
                            latencyCallback.onLatencyUpdate(rawLat, avgLat)
                        );
                    }
                }

                ByteBuffer buf = codec.getOutputBuffer(index);
                if (buf != null && info.size > 0) {
                    byte[] data = new byte[info.size];
                    buf.get(data);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) spsPpsCache = data;
                    if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 && spsPpsCache != null)
                        sendOverUDP(spsPpsCache);
                    sendOverUDP(data);
                }
                codec.releaseOutputBuffer(index, false);
            }

            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                Log.e(TAG, "Encoder error", e);
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                Log.d(TAG, "Encoder format changed: " + format);
            }
        }, backgroundHandler);

        encoder.start();
        Log.i(TAG, "MediaCodec encoder started successfully");
    }

    // ── Camera ────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private void openCamera(Surface previewSurface, boolean enablePreview) {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            manager.openCamera(currentConfig.camera.cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    createCaptureSession(previewSurface, enablePreview);
                }
                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close(); cameraDevice = null;
                }
                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close(); cameraDevice = null;
                    Log.e(TAG, "Camera error: " + error);
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to open camera", e);
        }
    }

    /**
     * Builds the Camera2 Capture Request. Routes raw YUV frames to both the encoderSurface
     * and the effectivePreviewSurface (which is either the true UI surface or the Dummy workaround).
     */
    private void createCaptureSession(Surface previewSurface, boolean enablePreview) {
        if (encoderSurface == null) { Log.e(TAG, "Encoder surface null"); return; }
        try {
            final CameraConfig cfg = currentConfig;
            final CaptureRequest.Builder builder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            
            Surface effectivePreviewSurface = null;
            if (enablePreview && previewSurface != null) {
                effectivePreviewSurface = previewSurface;
            } else {
                // To bypass Android HAL capping FPS to 60 when no preview is attached
                if (dummySurfaceTexture != null) dummySurfaceTexture.release();
                if (dummySurface != null) dummySurface.release();
                dummySurfaceTexture = new SurfaceTexture(0);
                dummySurfaceTexture.setDefaultBufferSize(cfg.camera.width, cfg.camera.height);
                dummySurface = new Surface(dummySurfaceTexture);
                effectivePreviewSurface = dummySurface;
            }

            builder.addTarget(effectivePreviewSurface);
            builder.addTarget(encoderSurface);

            // Apply all CameraConfig fields
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(cfg.camera.fps, cfg.camera.fps));
            builder.set(CaptureRequest.CONTROL_AF_MODE, cfg.camera.afMode);
            builder.set(CaptureRequest.CONTROL_AE_LOCK, cfg.camera.aeLocked);
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, cfg.camera.aeCompensation);
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, cfg.camera.noiseReduction);
            builder.set(CaptureRequest.EDGE_MODE, cfg.camera.edgeMode);
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, cfg.camera.videoStabilization ? 1 : 0);
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, cfg.camera.opticalStabilizationMode);
            
            // AWB Controls
            builder.set(CaptureRequest.CONTROL_AWB_MODE, cfg.camera.awbMode);
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, cfg.camera.awbLocked);
            
            // ISP Tuning
            builder.set(CaptureRequest.TONEMAP_MODE, cfg.camera.tonemapMode);
            builder.set(CaptureRequest.SHADING_MODE, cfg.camera.lensShadingMode);
            builder.set(CaptureRequest.HOT_PIXEL_MODE, cfg.camera.hotPixelMode);
            
            if (cfg.camera.iso > 0) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, cfg.camera.iso);
            }
            if (cfg.camera.shutterSpeed > 0) {
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, cfg.camera.shutterSpeed);
            }

            List<Surface> surfaces = Arrays.asList(effectivePreviewSurface, encoderSurface);

            // We strictly use standard standard capture sessions because ConstrainedHighSpeedCaptureSession
            // forces batching of requests which inherently destroys low-latency pipelines.
            cameraDevice.createCaptureSession(surfaces,
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        captureSession = session;
                        try {
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                            Log.i(TAG, "Standard session started @ " + cfg.camera.fps + " FPS");
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "Capture request failed", e);
                        }
                    }
                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        Log.e(TAG, "Standard session configure failed");
                    }
                }, backgroundHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Capture session creation failed", e);
        }
    }

    // ── UDP send with WFB-ng payload limit ────────────────────────────────

    private static final int MAX_WFB_PAYLOAD = 3993;

    private void sendOverUDP(byte[] data) {
        if (udpSocket == null || data == null) return;
        try {
            if (data.length <= MAX_WFB_PAYLOAD) {
                udpSocket.send(new DatagramPacket(data, 0, data.length, destinationAddress, DESTINATION_PORT));
            } else {
                int offset = 0;
                while (offset < data.length) {
                    int chunk = Math.min(MAX_WFB_PAYLOAD, data.length - offset);
                    udpSocket.send(new DatagramPacket(data, offset, chunk, destinationAddress, DESTINATION_PORT));
                    offset += chunk;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "UDP send failed", e);
        }
    }
}
