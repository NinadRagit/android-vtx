#include "AudioStreamerNative.h"
#include <android/log.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <cstring>
#include <chrono>

#define TAG "AudioStreamerNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// RTP Header definition
struct RtpHeader {
    uint8_t flags;       // V=2, P=0, X=0, CC=0  -> 0x80
    uint8_t m_pt;        // M=0, PT=98 (Opus) -> 0x62
    uint16_t seq;
    uint32_t ts;
    uint32_t ssrc;
};

AudioStreamerNative::AudioStreamerNative() {
    initUdpSocket();
}

AudioStreamerNative::~AudioStreamerNative() {
    stop();
    if (mOpusEncoder) {
        opus_encoder_destroy(mOpusEncoder);
    }
    if (mUdpSocket >= 0) {
        close(mUdpSocket);
    }
}

void AudioStreamerNative::initUdpSocket() {
    mUdpSocket = socket(AF_INET, SOCK_DGRAM, 0);
    if (mUdpSocket < 0) {
        LOGE("Failed to create UDP socket for audio");
        return;
    }

    memset(&mDestAddr, 0, sizeof(mDestAddr));
    mDestAddr.sin_family = AF_INET;
    mDestAddr.sin_port = htons(8001); // Same port as video
    inet_pton(AF_INET, "127.0.0.1", &mDestAddr.sin_addr);
}

bool AudioStreamerNative::start(const NativeAudioConfig& config) {
    mConfig = config;
    mFrameSizeSamples = (mConfig.sampleRate * mConfig.frameSizeMs) / 1000;

    int opusErr = OPUS_OK;
    mOpusEncoder = opus_encoder_create(mConfig.sampleRate, mConfig.channels, mConfig.application, &opusErr);
    if (opusErr != OPUS_OK) {
        LOGE("Failed to create Opus encoder: %s", opus_strerror(opusErr));
        return false;
    }

    opus_encoder_ctl(mOpusEncoder, OPUS_SET_BITRATE(mConfig.bitrate));
    opus_encoder_ctl(mOpusEncoder, OPUS_SET_COMPLEXITY(mConfig.complexity));
    opus_encoder_ctl(mOpusEncoder, OPUS_SET_VBR(mConfig.vbrMode));
    opus_encoder_ctl(mOpusEncoder, OPUS_SET_INBAND_FEC(mConfig.enableFec ? 1 : 0));
    opus_encoder_ctl(mOpusEncoder, OPUS_SET_DTX(mConfig.enableDtx ? 1 : 0));

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
           ->setPerformanceMode(static_cast<oboe::PerformanceMode>(mConfig.performanceMode))
           ->setSharingMode(static_cast<oboe::SharingMode>(mConfig.sharingMode))
           ->setFormat(static_cast<oboe::AudioFormat>(mConfig.format))
           ->setChannelCount(mConfig.channels)
           ->setSampleRate(mConfig.sampleRate)
           ->setCallback(this)
           ->setInputPreset(static_cast<oboe::InputPreset>(mConfig.inputPreset));

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open Oboe audio stream: %s", oboe::convertToText(result));
        return false;
    }

    result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start Oboe audio stream: %s", oboe::convertToText(result));
        return false;
    }

    LOGI("AudioStreamerNative started successfully. SampleRate: %d", mStream->getSampleRate());
    return true;
}

void AudioStreamerNative::stop() {
    if (mStream) {
        mStream->requestStop();
        mStream->close();
        mStream.reset();
        LOGI("AudioStreamerNative Oboe stream stopped.");
    }
    std::lock_guard<std::mutex> lock(mBufferMutex);
    if (mOpusEncoder) {
        opus_encoder_destroy(mOpusEncoder);
        mOpusEncoder = nullptr;
    }
}

oboe::DataCallbackResult AudioStreamerNative::onAudioReady(
        oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) {
    
    if (!mOpusEncoder || mUdpSocket < 0) return oboe::DataCallbackResult::Continue;

    const int16_t *pcmData = static_cast<const int16_t*>(audioData);
    
    std::lock_guard<std::mutex> lock(mBufferMutex);
    mPcmBuffer.insert(mPcmBuffer.end(), pcmData, pcmData + numFrames);

    // Encode in chunks of mFrameSizeSamples
    while (mPcmBuffer.size() >= mFrameSizeSamples) {
        uint8_t opusPayload[1500]; // Max UDP usually 1400-1500
        
        int encodedBytes = opus_encode(
            mOpusEncoder, 
            mPcmBuffer.data(), 
            mFrameSizeSamples,  
            opusPayload, 
            sizeof(opusPayload)
        );

        if (encodedBytes > 0) {
            sendRtpPacket(opusPayload, encodedBytes);
        } else {
            LOGE("Opus encoding failed: %s", opus_strerror(encodedBytes));
        }

        // Remove the processed chunk
        mPcmBuffer.erase(mPcmBuffer.begin(), mPcmBuffer.begin() + mFrameSizeSamples);
        
        // Update RTP Timestamp by the number of samples we just encoded
        mRtpTimestamp += mFrameSizeSamples; 
    }

    return oboe::DataCallbackResult::Continue;
}

void AudioStreamerNative::sendRtpPacket(const uint8_t* opusData, size_t opusSize) {
    uint8_t packet[1500];
    
    // 12-byte RTP Header
    RtpHeader* rtp = reinterpret_cast<RtpHeader*>(packet);
    rtp->flags = 0x80; // V=2
    rtp->m_pt = 98;    // PT=98 (Dynamic, commonly Opus)
    rtp->seq = htons(mRtpSequenceNumber++);
    rtp->ts = htonl(mRtpTimestamp);
    rtp->ssrc = htonl(0x12345679); // Distinct SSRC for Audio
    
    memcpy(packet + 12, opusData, opusSize);
    
    sendto(mUdpSocket, packet, 12 + opusSize, 0, 
           (struct sockaddr*)&mDestAddr, sizeof(mDestAddr));
}
