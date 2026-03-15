#include "CameraStreamerNative.h"

#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <time.h>
#include <dlfcn.h>
#include <cerrno>
#include <cstring>

// ── Constructor / Destructor ────────────────────────────────────────────────

CameraStreamerNative::CameraStreamerNative() = default;

CameraStreamerNative::~CameraStreamerNative() {
    stop();
}

// ── Public API ──────────────────────────────────────────────────────────────

bool CameraStreamerNative::start(const NativeCameraConfig& config, ANativeWindow* previewWindow) {
    if (streaming_.load()) {
        LOGW("Already streaming, stopping first");
        stop();
    }

    config_ = config;
    previewWindow_ = previewWindow; // may be null for headless

    LOGI("Starting native pipeline: %dx%d @ %d fps, codec=%s, bitrate=%d",
         config_.width, config_.height, config_.fps,
         config_.codec.c_str(), config_.bitrate);

    if (!setupUDP()) {
        LOGE("Failed to setup UDP socket");
        return false;
    }

    if (!setupEncoder()) {
        LOGE("Failed to setup encoder");
        return false;
    }

    if (!openCamera()) {
        LOGE("Failed to open camera");
        return false;
    }

    streaming_.store(true);

    // Start encoder polling thread
    encoderThread_ = std::thread(&CameraStreamerNative::encoderLoop, this);

    LOGI("Native camera pipeline started successfully");
    return true;
}

void CameraStreamerNative::stop() {
    if (!streaming_.load() && !encoder_ && !cameraDevice_) return;

    LOGI("Stopping native camera pipeline...");
    streaming_.store(false);

    // 1. Stop capture session
    if (captureSession_) {
        ACameraCaptureSession_close(captureSession_);
        captureSession_ = nullptr;
    }

    // 2. Close camera device
    if (cameraDevice_) {
        ACameraDevice_close(cameraDevice_);
        cameraDevice_ = nullptr;
    }

    // 3. Join encoder thread
    if (encoderThread_.joinable()) {
        encoderThread_.join();
    }

    // 4. Stop and release encoder
    if (encoder_) {
        AMediaCodec_stop(encoder_);
        AMediaCodec_delete(encoder_);
        encoder_ = nullptr;
    }

    // 5. Release encoder window (the Surface we created from the encoder)
    if (encoderWindow_) {
        ANativeWindow_release(encoderWindow_);
        encoderWindow_ = nullptr;
    }

    // 6. Clean up capture request targets
    if (captureRequest_) {
        if (encoderTarget_)  ACaptureRequest_removeTarget(captureRequest_, encoderTarget_);
        if (previewTarget_)  ACaptureRequest_removeTarget(captureRequest_, previewTarget_);
        ACaptureRequest_free(captureRequest_);
        captureRequest_ = nullptr;
    }
    if (encoderTarget_) { ACameraOutputTarget_free(encoderTarget_); encoderTarget_ = nullptr; }
    if (previewTarget_) { ACameraOutputTarget_free(previewTarget_); previewTarget_ = nullptr; }

    // 7. Clean up session outputs
    if (outputContainer_) {
        if (encoderOutput_) ACaptureSessionOutputContainer_remove(outputContainer_, encoderOutput_);
        if (previewOutput_) ACaptureSessionOutputContainer_remove(outputContainer_, previewOutput_);
        ACaptureSessionOutputContainer_free(outputContainer_);
        outputContainer_ = nullptr;
    }
    if (encoderOutput_) { ACaptureSessionOutput_free(encoderOutput_); encoderOutput_ = nullptr; }
    if (previewOutput_) { ACaptureSessionOutput_free(previewOutput_); previewOutput_ = nullptr; }

    // 8. Release camera manager
    if (cameraManager_) {
        ACameraManager_delete(cameraManager_);
        cameraManager_ = nullptr;
    }

    // 9. Close UDP socket
    if (udpSocket_ >= 0) {
        close(udpSocket_);
        udpSocket_ = -1;
    }

    // Clear caches
    spsPpsCache_.clear();
    latencyHistory_.clear();

    LOGI("Native camera pipeline stopped");
}

// ── UDP Setup ───────────────────────────────────────────────────────────────

bool CameraStreamerNative::setupUDP() {
    udpSocket_ = socket(AF_INET, SOCK_DGRAM, 0);
    if (udpSocket_ < 0) {
        LOGE("socket() failed: %s", strerror(errno));
        return false;
    }
    LOGI("UDP socket created (fd=%d) -> 127.0.0.1:%d", udpSocket_, VIDEO_UDP_PORT);
    return true;
}

void CameraStreamerNative::sendOverUDP_Raw(const uint8_t* packetData, size_t packetLen) {
    if (udpSocket_ < 0) return;
    struct sockaddr_in dest{};
    dest.sin_family      = AF_INET;
    dest.sin_port        = htons(VIDEO_UDP_PORT);
    dest.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    sendto(udpSocket_, packetData, packetLen, 0, (struct sockaddr*)&dest, sizeof(dest));
}

void CameraStreamerNative::sendNalu(const uint8_t* naluData, size_t naluSize, uint32_t rtpTs) {
    if (naluSize == 0) return;

    const size_t MAX_PAYLOAD_SIZE = 1400; // fit well within wfb_tx's 1450 MTU
    uint32_t ssrc = htonl(rtpSsrc_);
    
    if (naluSize <= MAX_PAYLOAD_SIZE) {
        // Single NAL unit packet
        std::vector<uint8_t> packet(12 + naluSize);
        packet[0] = 0x80;
        packet[1] = 97;
        uint16_t seq = htons(rtpSeqNumber_++);
        memcpy(&packet[2], &seq, 2);
        memcpy(&packet[4], &rtpTs, 4);
        memcpy(&packet[8], &ssrc, 4);
        
        memcpy(&packet[12], naluData, naluSize);
        sendOverUDP_Raw(packet.data(), packet.size());
    } else {
        // Fragmentation (FU-A)
        if (isH265_) {
            // H.265 FU-A
            uint8_t payloadHdr0 = naluData[0];
            uint8_t payloadHdr1 = naluData[1];
            uint8_t nalType = (payloadHdr0 >> 1) & 0x3F;
            
            uint8_t fuIndicator0 = (payloadHdr0 & 0x81) | (49 << 1); 
            uint8_t fuIndicator1 = payloadHdr1;
            
            const uint8_t* payload = naluData + 2;
            size_t remaining = naluSize - 2;
            bool isFirst = true;
            
            while (remaining > 0) {
                size_t chunkSize = std::min(remaining, MAX_PAYLOAD_SIZE);
                bool isLast = (chunkSize == remaining);
                
                uint8_t fuHeader = nalType;
                if (isFirst) fuHeader |= 0x80; // S-bit
                if (isLast) fuHeader |= 0x40;  // E-bit
                
                std::vector<uint8_t> packet(12 + 3 + chunkSize);
                packet[0] = 0x80;
                packet[1] = 97;
                uint16_t seq = htons(rtpSeqNumber_++);
                memcpy(&packet[2], &seq, 2);
                memcpy(&packet[4], &rtpTs, 4);
                memcpy(&packet[8], &ssrc, 4);
                
                packet[12] = fuIndicator0;
                packet[13] = fuIndicator1;
                packet[14] = fuHeader;
                memcpy(&packet[15], payload, chunkSize);
                
                sendOverUDP_Raw(packet.data(), packet.size());
                
                payload += chunkSize;
                remaining -= chunkSize;
                isFirst = false;
            }
        } else {
            // H.264 FU-A
            uint8_t nalHdr = naluData[0];
            uint8_t nalType = nalHdr & 0x1F;
            uint8_t fuIndicator = (nalHdr & 0xE0) | 28; // FU-A type is 28
            
            const uint8_t* payload = naluData + 1;
            size_t remaining = naluSize - 1;
            bool isFirst = true;
            
            while (remaining > 0) {
                size_t chunkSize = std::min(remaining, MAX_PAYLOAD_SIZE);
                bool isLast = (chunkSize == remaining);
                
                uint8_t fuHeader = nalType;
                if (isFirst) fuHeader |= 0x80; // S-bit
                if (isLast) fuHeader |= 0x40;  // E-bit
                
                std::vector<uint8_t> packet(12 + 2 + chunkSize);
                packet[0] = 0x80;
                packet[1] = 97;
                uint16_t seq = htons(rtpSeqNumber_++);
                memcpy(&packet[2], &seq, 2);
                memcpy(&packet[4], &rtpTs, 4);
                memcpy(&packet[8], &ssrc, 4);
                
                packet[12] = fuIndicator;
                packet[13] = fuHeader;
                memcpy(&packet[14], payload, chunkSize);
                
                sendOverUDP_Raw(packet.data(), packet.size());
                
                payload += chunkSize;
                remaining -= chunkSize;
                isFirst = false;
            }
        }
    }
}

void CameraStreamerNative::sendOverUDP(const uint8_t* data, size_t len, uint32_t timestampUs) {
    if (udpSocket_ < 0 || data == nullptr || len == 0) return;

    // Convert timestamp to 90kHz RTP timestamp
    uint32_t rtpTs = htonl((uint32_t)((timestampUs / 1000000.0) * 90000.0));

    // Parse Annex B and send each NALU
    size_t i = 0;
    while (i < len) {
        // Find next start code
        int startCodeLen = 0;
        if (i + 2 < len && data[i] == 0 && data[i+1] == 0 && data[i+2] == 1) {
            startCodeLen = 3;
        } else if (i + 3 < len && data[i] == 0 && data[i+1] == 0 && data[i+2] == 0 && data[i+3] == 1) {
            startCodeLen = 4;
        }

        if (startCodeLen > 0) {
            i += startCodeLen;
            size_t nalStart = i;
            
            // Find end of this NALU (next start code or end of buffer)
            size_t nalEnd = len;
            for (size_t j = nalStart; j < len - 2; ++j) {
                if (data[j] == 0 && data[j+1] == 0 && data[j+2] == 1) {
                    if (j > nalStart && data[j-1] == 0) {
                        nalEnd = j - 1;
                    } else {
                        nalEnd = j;
                    }
                    break;
                }
            }

            size_t nalSize = nalEnd - nalStart;
            if (nalSize > 0) {
                sendNalu(data + nalStart, nalSize, rtpTs);
            }
            i = nalEnd;
        } else {
            // No start code at beginning? scan forward to find first start code.
            size_t nextStart = len;
            for (size_t j = i; j < len - 2; ++j) {
                if (data[j] == 0 && data[j+1] == 0 && data[j+2] == 1) {
                    if (j > i && data[j-1] == 0) {
                        nextStart = j - 1;
                    } else {
                        nextStart = j;
                    }
                    break;
                }
            }
            if (nextStart > i) {
                sendNalu(data + i, nextStart - i, rtpTs);
            }
            i = nextStart;
        }
    }
}

// ── Encoder Setup ───────────────────────────────────────────────────────────

bool CameraStreamerNative::setupEncoder() {
    const char* mime = (config_.codec == "h265")
                       ? "video/hevc"
                       : "video/avc";

    encoder_ = AMediaCodec_createEncoderByType(mime);
    if (!encoder_) {
        LOGE("AMediaCodec_createEncoderByType(%s) failed", mime);
        return false;
    }

    AMediaFormat* fmt = AMediaFormat_new();
    AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, mime);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH,  config_.width);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, config_.height);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_COLOR_FORMAT, 0x7F000789); // COLOR_FormatSurface
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_BIT_RATE, config_.bitrate);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_FRAME_RATE, config_.fps);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, config_.keyframeInterval);

    // Bitrate mode: 0=CBR, 1=VBR
    AMediaFormat_setInt32(fmt, "bitrate-mode",
                          config_.bitrateMode == 0 ? 1 /* CBR */ : 0 /* VBR */);

    // Operating rate
    int opRate = (config_.operatingRate > 0) ? config_.operatingRate : config_.fps;
    AMediaFormat_setInt32(fmt, "operating-rate", opRate);

    // Optional encoder tuning
    if (config_.encoderPriority > 0) {
        AMediaFormat_setInt32(fmt, "priority", config_.encoderPriority);
    }
    if (config_.encoderLatency > 0) {
        AMediaFormat_setInt32(fmt, "latency", config_.encoderLatency);
    }
    if (config_.bFrames > 0) {
        AMediaFormat_setInt32(fmt, "max-bframes", config_.bFrames);
    }
    if (config_.intraRefreshPeriod > 0) {
        AMediaFormat_setInt32(fmt, "intra-refresh-period", config_.intraRefreshPeriod);
    }
    if (config_.codecProfile > 0) {
        AMediaFormat_setInt32(fmt, "profile", config_.codecProfile);
    }
    if (config_.codecLevel > 0) {
        AMediaFormat_setInt32(fmt, "level", config_.codecLevel);
    }

    media_status_t status = AMediaCodec_configure(
            encoder_, fmt, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
    AMediaFormat_delete(fmt);

    if (status != AMEDIA_OK) {
        LOGE("AMediaCodec_configure failed: %d — trying safe fallback", status);
        // Safe fallback: minimal H.264
        AMediaCodec_delete(encoder_);
        encoder_ = AMediaCodec_createEncoderByType("video/avc");
        if (!encoder_) return false;

        AMediaFormat* safe = AMediaFormat_new();
        AMediaFormat_setString(safe, AMEDIAFORMAT_KEY_MIME, "video/avc");
        AMediaFormat_setInt32(safe, AMEDIAFORMAT_KEY_WIDTH,  config_.width);
        AMediaFormat_setInt32(safe, AMEDIAFORMAT_KEY_HEIGHT, config_.height);
        AMediaFormat_setInt32(safe, AMEDIAFORMAT_KEY_COLOR_FORMAT, 0x7F000789);
        AMediaFormat_setInt32(safe, AMEDIAFORMAT_KEY_BIT_RATE, 4000000);
        AMediaFormat_setInt32(safe, AMEDIAFORMAT_KEY_FRAME_RATE, config_.fps);
        AMediaFormat_setInt32(safe, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 1);

        status = AMediaCodec_configure(
                encoder_, safe, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
        AMediaFormat_delete(safe);

        if (status != AMEDIA_OK) {
            LOGE("Fallback configure also failed: %d", status);
            AMediaCodec_delete(encoder_);
            encoder_ = nullptr;
            return false;
        }
        LOGW("Using safe fallback encoder settings");
    }

    // Create the encoder's input Surface
    status = AMediaCodec_createInputSurface(encoder_, &encoderWindow_);
    if (status != AMEDIA_OK || !encoderWindow_) {
        LOGE("AMediaCodec_createInputSurface failed: %d", status);
        AMediaCodec_delete(encoder_);
        encoder_ = nullptr;
        return false;
    }

    status = AMediaCodec_start(encoder_);
    if (status != AMEDIA_OK) {
        LOGE("AMediaCodec_start failed: %d", status);
        ANativeWindow_release(encoderWindow_);
        encoderWindow_ = nullptr;
        AMediaCodec_delete(encoder_);
        encoder_ = nullptr;
        return false;
    }

    isH265_ = (strcmp(mime, "video/hevc") == 0);
    LOGI("AMediaCodec encoder started (%s, %dx%d, %d bps), isH265_=%d",
         mime, config_.width, config_.height, config_.bitrate, (int)isH265_);
    return true;
}

// ── Encoder polling thread ──────────────────────────────────────────────────

void CameraStreamerNative::encoderLoop() {
    LOGI("Encoder output thread started");

    // Resolve the telemetry whiteboard update function from libmavlink.so
    // This allows us to update MAVLink telemetry directly from C++ without JNI
    typedef void (*UpdateLatencyFn)(float);
    UpdateLatencyFn updateLatency = nullptr;
    void* mavlinkLib = dlopen("libmavlink.so", RTLD_NOLOAD);
    if (mavlinkLib) {
        updateLatency = (UpdateLatencyFn)dlsym(mavlinkLib, "telemetry_update_encoder_latency");
        if (updateLatency) {
            LOGI("Resolved telemetry_update_encoder_latency from libmavlink.so");
        } else {
            LOGW("Could not resolve telemetry_update_encoder_latency: %s", dlerror());
        }
        dlclose(mavlinkLib);
    } else {
        LOGW("Could not find libmavlink.so: %s", dlerror());
    }

    while (streaming_.load()) {
        AMediaCodecBufferInfo info;
        ssize_t idx = AMediaCodec_dequeueOutputBuffer(encoder_, &info, 10000 /* 10ms timeout */);

        if (idx >= 0) {
            // ── Latency calculation (CLOCK_MONOTONIC domain) ────────────
            struct timespec ts;
            clock_gettime(CLOCK_MONOTONIC, &ts);
            int64_t nowUs = (int64_t)ts.tv_sec * 1000000LL + ts.tv_nsec / 1000;
            int latencyMs = (int)((nowUs - info.presentationTimeUs) / 1000);

            if (latencyMs > 0 && latencyMs < 500) {
                latencyHistory_.push_back(latencyMs);
                if (latencyHistory_.size() > LATENCY_WINDOW) {
                    latencyHistory_.erase(latencyHistory_.begin());
                }
                int sum = 0;
                for (int v : latencyHistory_) sum += v;
                int avg = sum / (int)latencyHistory_.size();

                // Update the TelemetryWhiteboard in libmavlink.so
                if (updateLatency) {
                    updateLatency((float)avg);
                }
            }

            // ── NAL unit extraction and UDP send ────────────────────────
            size_t outSize = 0;
            uint8_t* buf = AMediaCodec_getOutputBuffer(encoder_, idx, &outSize);
            if (buf && info.size > 0) {
                uint8_t* nalData = buf + info.offset;
                size_t   nalLen  = (size_t)info.size;

                if (info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) {
                    // SPS/PPS — cache for prepending to keyframes
                    spsPpsCache_.assign(nalData, nalData + nalLen);
                    LOGI("Cached SPS/PPS (%zu bytes)", nalLen);
                }

                if ((info.flags & 1 /* BUFFER_FLAG_KEY_FRAME */) && !spsPpsCache_.empty()) {
                    sendOverUDP(spsPpsCache_.data(), spsPpsCache_.size(), info.presentationTimeUs);
                }

                sendOverUDP(nalData, nalLen, info.presentationTimeUs);
            }

            AMediaCodec_releaseOutputBuffer(encoder_, idx, false);

        } else if (idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat* newFmt = AMediaCodec_getOutputFormat(encoder_);
            if (newFmt) {
                const char* fmtStr = AMediaFormat_toString(newFmt);
                LOGI("Encoder format changed: %s", fmtStr ? fmtStr : "(null)");
                AMediaFormat_delete(newFmt);
            }
        } else if (idx == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
            // Normal timeout, loop again
        }
    }

    LOGI("Encoder output thread finished");
}

// ── Camera Setup ────────────────────────────────────────────────────────────

// Static C callbacks for ACameraDevice
void CameraStreamerNative::onDeviceDisconnected(void* ctx, ACameraDevice* dev) {
    LOGW("Camera device disconnected");
}

void CameraStreamerNative::onDeviceError(void* ctx, ACameraDevice* dev, int error) {
    LOGE("Camera device error: %d", error);
}

// Static C callbacks for ACameraCaptureSession
void CameraStreamerNative::onSessionClosed(void* ctx, ACameraCaptureSession* session) {
    LOGI("Capture session closed");
}

void CameraStreamerNative::onSessionReady(void* ctx, ACameraCaptureSession* session) {
    LOGI("Capture session ready");
}

void CameraStreamerNative::onSessionActive(void* ctx, ACameraCaptureSession* session) {
    LOGI("Capture session active");
}

bool CameraStreamerNative::openCamera() {
    cameraManager_ = ACameraManager_create();
    if (!cameraManager_) {
        LOGE("ACameraManager_create failed");
        return false;
    }

    // Open the camera device specified in config
    ACameraDevice_StateCallbacks deviceCbs{};
    deviceCbs.context       = this;
    deviceCbs.onDisconnected = onDeviceDisconnected;
    deviceCbs.onError        = onDeviceError;

    camera_status_t camStatus = ACameraManager_openCamera(
            cameraManager_, config_.cameraId.c_str(), &deviceCbs, &cameraDevice_);
    if (camStatus != ACAMERA_OK) {
        LOGE("ACameraManager_openCamera(%s) failed: %d", config_.cameraId.c_str(), camStatus);
        return false;
    }
    LOGI("Camera %s opened", config_.cameraId.c_str());

    return createCaptureSession();
}

bool CameraStreamerNative::createCaptureSession() {
    if (!encoderWindow_) {
        LOGE("Encoder surface is null, can't create session");
        return false;
    }

    camera_status_t status;

    // Create output container
    status = ACaptureSessionOutputContainer_create(&outputContainer_);
    if (status != ACAMERA_OK) { LOGE("OutputContainer_create failed"); return false; }

    // Encoder output
    status = ACaptureSessionOutput_create(encoderWindow_, &encoderOutput_);
    if (status != ACAMERA_OK) { LOGE("Encoder SessionOutput_create failed"); return false; }
    ACaptureSessionOutputContainer_add(outputContainer_, encoderOutput_);

    // Preview output (use encoder window as dummy if no preview provided)
    ANativeWindow* effectivePreview = previewWindow_ ? previewWindow_ : encoderWindow_;
    if (previewWindow_) {
        status = ACaptureSessionOutput_create(previewWindow_, &previewOutput_);
        if (status != ACAMERA_OK) { LOGE("Preview SessionOutput_create failed"); return false; }
        ACaptureSessionOutputContainer_add(outputContainer_, previewOutput_);
    }

    // Create capture request
    status = ACameraDevice_createCaptureRequest(cameraDevice_, TEMPLATE_RECORD, &captureRequest_);
    if (status != ACAMERA_OK) { LOGE("createCaptureRequest failed"); return false; }

    // Add encoder target
    ACameraOutputTarget_create(encoderWindow_, &encoderTarget_);
    ACaptureRequest_addTarget(captureRequest_, encoderTarget_);

    // Add preview target (if provided)
    if (previewWindow_) {
        ACameraOutputTarget_create(previewWindow_, &previewTarget_);
        ACaptureRequest_addTarget(captureRequest_, previewTarget_);
    }

    // Apply camera settings from config
    // FPS range
    int32_t fpsRange[2] = { config_.fps, config_.fps };
    ACaptureRequest_setEntry_i32(captureRequest_,
            ACAMERA_CONTROL_AE_TARGET_FPS_RANGE, 2, fpsRange);

    // AF mode
    uint8_t afMode = (uint8_t)config_.afMode;
    ACaptureRequest_setEntry_u8(captureRequest_, ACAMERA_CONTROL_AF_MODE, 1, &afMode);

    // AWB mode
    uint8_t awbMode = (uint8_t)config_.awbMode;
    ACaptureRequest_setEntry_u8(captureRequest_, ACAMERA_CONTROL_AWB_MODE, 1, &awbMode);

    // AE lock
    uint8_t aeLock = config_.aeLocked ? 1 : 0;
    ACaptureRequest_setEntry_u8(captureRequest_, ACAMERA_CONTROL_AE_LOCK, 1, &aeLock);

    // AWB lock
    uint8_t awbLock = config_.awbLocked ? 1 : 0;
    ACaptureRequest_setEntry_u8(captureRequest_, ACAMERA_CONTROL_AWB_LOCK, 1, &awbLock);

    // AE compensation
    int32_t aeComp = config_.aeCompensation;
    ACaptureRequest_setEntry_i32(captureRequest_,
            ACAMERA_CONTROL_AE_EXPOSURE_COMPENSATION, 1, &aeComp);

    // Edge mode
    uint8_t edgeMode = (uint8_t)config_.edgeMode;
    ACaptureRequest_setEntry_u8(captureRequest_, ACAMERA_EDGE_MODE, 1, &edgeMode);

    // Noise reduction
    uint8_t noiseReduction = (uint8_t)config_.noiseReduction;
    ACaptureRequest_setEntry_u8(captureRequest_, ACAMERA_NOISE_REDUCTION_MODE, 1, &noiseReduction);

    // Video stabilization
    uint8_t stabMode = config_.videoStabilization ? 1 : 0;
    ACaptureRequest_setEntry_u8(captureRequest_,
            ACAMERA_CONTROL_VIDEO_STABILIZATION_MODE, 1, &stabMode);

    // OIS
    uint8_t oisMode = (uint8_t)config_.opticalStabilizationMode;
    ACaptureRequest_setEntry_u8(captureRequest_,
            ACAMERA_LENS_OPTICAL_STABILIZATION_MODE, 1, &oisMode);

    // Tonemap mode
    uint8_t tonemapMode = (uint8_t)config_.tonemapMode;
    ACaptureRequest_setEntry_u8(captureRequest_, ACAMERA_TONEMAP_MODE, 1, &tonemapMode);

    // Lens shading mode
    uint8_t shadingMode = (uint8_t)config_.lensShadingMode;
    ACaptureRequest_setEntry_u8(captureRequest_, ACAMERA_SHADING_MODE, 1, &shadingMode);

    // Hot pixel mode
    uint8_t hotPixelMode = (uint8_t)config_.hotPixelMode;
    ACaptureRequest_setEntry_u8(captureRequest_, ACAMERA_HOT_PIXEL_MODE, 1, &hotPixelMode);

    // Manual ISO  (disables AE if nonzero)
    if (config_.iso > 0) {
        uint8_t aeOff = ACAMERA_CONTROL_AE_MODE_OFF;
        ACaptureRequest_setEntry_u8(captureRequest_, ACAMERA_CONTROL_AE_MODE, 1, &aeOff);
        int32_t iso = config_.iso;
        ACaptureRequest_setEntry_i32(captureRequest_, ACAMERA_SENSOR_SENSITIVITY, 1, &iso);
    }

    // Manual shutter speed
    if (config_.shutterSpeed > 0) {
        int64_t exposure = config_.shutterSpeed;
        ACaptureRequest_setEntry_i64(captureRequest_, ACAMERA_SENSOR_EXPOSURE_TIME, 1, &exposure);
    }

    // Create capture session
    ACameraCaptureSession_stateCallbacks sessionCbs{};
    sessionCbs.context   = this;
    sessionCbs.onClosed  = onSessionClosed;
    sessionCbs.onReady   = onSessionReady;
    sessionCbs.onActive  = onSessionActive;

    status = ACameraDevice_createCaptureSession(
            cameraDevice_, outputContainer_, &sessionCbs, &captureSession_);
    if (status != ACAMERA_OK) {
        LOGE("createCaptureSession failed: %d", status);
        return false;
    }

    // Start repeating request
    status = ACameraCaptureSession_setRepeatingRequest(
            captureSession_, nullptr, 1, &captureRequest_, nullptr);
    if (status != ACAMERA_OK) {
        LOGE("setRepeatingRequest failed: %d", status);
        return false;
    }

    LOGI("Capture session started @ %d FPS", config_.fps);
    return true;
}
