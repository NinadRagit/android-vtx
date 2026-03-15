#pragma once

#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCaptureRequest.h>
#include <camera/NdkCameraCaptureSession.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>

#include <string>
#include <atomic>
#include <thread>
#include <mutex>
#include <vector>
#include <cstdint>

#define LOG_TAG "CameraStreamerNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Maximum WFB-NG payload size (must chunk NAL units larger than this)
static constexpr int MAX_WFB_PAYLOAD = 65000; // Let WFB-NG handle link-layer fragmentation
// UDP destination port for wfb-ng video input
static constexpr int VIDEO_UDP_PORT = 8001;
static constexpr int RTP_HEADER_SIZE = 12;

/**
 * Native configuration struct — mirrors CameraConfig.java fields.
 * All values are passed from Java via JNI at startup.
 */
struct NativeCameraConfig {
    // Camera
    std::string cameraId = "0";
    int32_t width        = 1280;
    int32_t height       = 720;
    int32_t fps          = 30;
    bool    aeLocked     = false;
    int32_t aeCompensation = 0;
    int32_t afMode       = 0;
    int32_t edgeMode     = 0;
    int32_t noiseReduction = 0;
    bool    videoStabilization = false;
    int32_t opticalStabilizationMode = 0;
    int32_t awbMode      = 1;
    bool    awbLocked    = false;
    int32_t tonemapMode  = 1;
    int32_t lensShadingMode = 1;
    int32_t hotPixelMode = 1;
    int32_t iso          = 0;
    int64_t shutterSpeed = 0;

    // Encoder
    int32_t     bitrate           = 4000000;
    int32_t     keyframeInterval  = 1;
    int32_t     intraRefreshPeriod = 0;
    std::string codec             = "h264"; // "h264" or "h265"
    int32_t     bitrateMode       = 0;      // 0=CBR, 1=VBR
    int32_t     bFrames           = 0;
    int32_t     encoderLatency    = 0;
    int32_t     encoderPriority   = 0;
    int32_t     operatingRate     = 0;
    int32_t     codecProfile      = 0;
    int32_t     codecLevel        = 0;
};

/**
 * CameraStreamerNative — Pure C++ camera-to-UDP pipeline.
 *
 * Opens the Android Camera2 device via ACameraManager,
 * pushes frames into an AMediaCodec hardware encoder,
 * and transmits the resulting H.264/H.265 NAL units
 * over a localhost UDP socket to wfb-ng (port 8001).
 *
 * Encoder latency telemetry is measured natively using
 * CLOCK_MONOTONIC and forwarded to the TelemetryWhiteboard
 * (declared extern in mavlink.cpp) for the C++ scheduler.
 */
class CameraStreamerNative {
public:
    CameraStreamerNative();
    ~CameraStreamerNative();

    /** Start the full pipeline: Camera → Encoder → UDP */
    bool start(const NativeCameraConfig& config, ANativeWindow* previewWindow);

    /** Tear down everything gracefully */
    void stop();

    bool isStreaming() const { return streaming_.load(); }

private:
    // ── Camera ──────────────────────────────────────────────
    ACameraManager*              cameraManager_    = nullptr;
    ACameraDevice*               cameraDevice_     = nullptr;
    ACaptureSessionOutput*       encoderOutput_    = nullptr;
    ACaptureSessionOutput*       previewOutput_    = nullptr;
    ACaptureSessionOutputContainer* outputContainer_ = nullptr;
    ACameraCaptureSession*       captureSession_   = nullptr;
    ACaptureRequest*             captureRequest_   = nullptr;
    ACameraOutputTarget*         encoderTarget_    = nullptr;
    ACameraOutputTarget*         previewTarget_    = nullptr;

    // ── Encoder ─────────────────────────────────────────────
    AMediaCodec* encoder_        = nullptr;
    ANativeWindow* encoderWindow_ = nullptr;
    ANativeWindow* previewWindow_ = nullptr; // NOT owned by us

    // ── UDP ────────────────────────────────────────────────
    int udpSocket_ = -1;

    // ── State ──────────────────────────────────────────────
    std::atomic<bool> streaming_{false};
    NativeCameraConfig config_;

    // RTP State
    uint16_t rtpSeqNumber_ = 0;
    uint32_t rtpSsrc_ = 0x87654321; // Distinct from audio SSRC

    // SPS/PPS cache for prepending to keyframes
    std::vector<uint8_t> spsPpsCache_;

    // Latency rolling average
    std::vector<int> latencyHistory_;
    bool isH265_ = false;
    static constexpr size_t LATENCY_WINDOW = 30;

    // ── Encoder polling thread ─────────────────────────────
    std::thread encoderThread_;
    void encoderLoop();

    // ── Internal setup ─────────────────────────────────────
    bool setupUDP();
    bool setupEncoder();
    bool openCamera();
    bool createCaptureSession();

    // Sends a full Annex B buffer (will be split into NAL units)
    void sendOverUDP(const uint8_t* data, size_t len, uint32_t timestampUs);
    
    // Internal RTP packetization helpers
    void sendNalu(const uint8_t* naluData, size_t naluSize, uint32_t rtpTs);
    void sendOverUDP_Raw(const uint8_t* packetData, size_t packetLen);

    // ── NDK Callbacks (static C thunks) ────────────────────
    static void onDeviceDisconnected(void* ctx, ACameraDevice* dev);
    static void onDeviceError(void* ctx, ACameraDevice* dev, int error);
    static void onSessionClosed(void* ctx, ACameraCaptureSession* session);
    static void onSessionReady(void* ctx, ACameraCaptureSession* session);
    static void onSessionActive(void* ctx, ACameraCaptureSession* session);
};
