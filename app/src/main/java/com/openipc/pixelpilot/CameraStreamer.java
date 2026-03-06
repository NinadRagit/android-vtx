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
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * CameraStreamer handles the VTX pipeline:
 * Camera -> MediaCodec (H.264) -> UDP (Localhost:8001)
 */
public class CameraStreamer {
    private static final String TAG = "CameraStreamer";
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC; // H.264
    private static final int FRAME_RATE = 60;
    private static final int BITRATE = 4000000; // 4 Mbps
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;

    private final Context context;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private MediaCodec encoder;
    private Surface encoderSurface;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private DatagramSocket udpSocket;
    private InetAddress destinationAddress;
    private static final int DESTINATION_PORT = 8001;

    public CameraStreamer(Context context) {
        this.context = context;
    }

    private boolean isStreaming = false;

    public void startStreaming(Surface previewSurface) {
        if (isStreaming) return;
        isStreaming = true;
        
        Log.d(TAG, "Starting VTX stream...");
        startBackgroundThread();
        setupUDP();
        setupEncoder();
        openCamera(previewSurface);
    }

    public void stopStreaming() {
        if (!isStreaming) return;
        isStreaming = false;
        
        Log.d(TAG, "Stopping VTX stream...");
        // Rest of the existing stop logic...
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (encoder != null) {
            encoder.stop();
            encoder.release();
            encoder = null;
        }
        if (udpSocket != null) {
            udpSocket.close();
            udpSocket = null;
        }
        stopBackgroundThread();
    }

    private void setupUDP() {
        try {
            udpSocket = new DatagramSocket();
            destinationAddress = InetAddress.getByName("127.0.0.1");
        } catch (IOException e) {
            Log.e(TAG, "UDP Setup failed", e);
        }
    }

    private void setupEncoder() {
        try {
            encoder = MediaCodec.createEncoderByType(MIME_TYPE);
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, WIDTH, HEIGHT);

            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // 1 second between keyframes
            
            // Low latency settings
            format.setInteger(MediaFormat.KEY_PRIORITY, 0); // Real-time priority
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);

            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = encoder.createInputSurface();

            encoder.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                    // Not used for Surface input
                }

                @Override
                public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                    if (outputBuffer != null && info.size > 0) {
                        // Log I-frame occurrences to verify KEY_I_FRAME_INTERVAL behavior
                        if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                            Log.d(TAG, "I-frame: size=" + info.size + " pts=" + info.presentationTimeUs);
                        }
                        byte[] outData = new byte[info.size];
                        outputBuffer.get(outData);
                        sendOverUDP(outData);
                    }
                    codec.releaseOutputBuffer(index, false);
                }

                @Override
                public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                    Log.e(TAG, "Encoder Error", e);
                }

                @Override
                public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                    Log.d(TAG, "Encoder Format Changed: " + format);
                }
            }, backgroundHandler);

            encoder.start();
        } catch (IOException e) {
            Log.e(TAG, "Encoder setup failed", e);
        }
    }

    // wfb-ng MAX_PAYLOAD_SIZE is ~3993 bytes. Each UDP packet sent to wfb_tx
    // becomes one wfb-ng data unit, reconstructed intact on the GS side.
    // Do NOT fragment here — wfb_tx handles FEC/fragmentation internally.
    // NAL units larger than this limit must be split at NAL boundaries.
    private static final int MAX_WFB_PAYLOAD = 3993;

    private void sendOverUDP(byte[] data) {
        if (udpSocket == null || data == null) return;
        try {
            if (data.length <= MAX_WFB_PAYLOAD) {
                // Common case: NAL unit fits in one wfb-ng packet
                DatagramPacket packet = new DatagramPacket(data, 0, data.length, destinationAddress, DESTINATION_PORT);
                udpSocket.send(packet);
            } else {
                // Rare case: very large NAL unit (e.g. IDR frame).
                // Split into chunks. wfb-ng will deliver each as a separate
                // UDP packet on the GS, so the receiver must handle reassembly.
                int offset = 0;
                while (offset < data.length) {
                    int chunkSize = Math.min(MAX_WFB_PAYLOAD, data.length - offset);
                    DatagramPacket packet = new DatagramPacket(data, offset, chunkSize, destinationAddress, DESTINATION_PORT);
                    udpSocket.send(packet);
                    offset += chunkSize;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to send UDP packet", e);
        }
    }

    @SuppressLint("MissingPermission")
    private void openCamera(Surface previewSurface) {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String[] cameraIdList = manager.getCameraIdList();
            if (cameraIdList.length == 0) {
                Log.e(TAG, "No cameras found on device!");
                return;
            }
            String cameraId = cameraIdList[0]; // Default to back camera
            Log.d(TAG, "Opening camera: " + cameraId);
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    createCaptureSession(previewSurface);
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera open failed", e);
        }
    }

    private void createCaptureSession(Surface previewSurface) {
        try {
            final CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(previewSurface);
            builder.addTarget(encoderSurface);

            cameraDevice.createCaptureSession(java.util.Arrays.asList(previewSurface, encoderSurface),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        captureSession = session;
                        try {
                            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                            captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "Capture Request failed", e);
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        Log.e(TAG, "Capture Session configuration failed");
                    }
                }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Capture Session creation failed", e);
        }
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Background Thread join interrupted", e);
            }
        }
    }
}
