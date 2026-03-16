#pragma once

#include <oboe/Oboe.h>
#include <opus.h>
#include <thread>
#include <atomic>
#include <vector>
#include <mutex>
#include <netinet/in.h>

struct NativeAudioConfig {
    int sampleRate;
    int channels;
    int inputPreset;
    int performanceMode;
    int sharingMode;
    int format;
    int bitrate;
    int complexity;
    int application;
    int frameSizeMs;
    bool enableFec;
    bool enableDtx;
    int vbrMode;
};

class AudioStreamerNative : public oboe::AudioStreamCallback {
public:
    AudioStreamerNative();
    ~AudioStreamerNative();

    bool start(const NativeAudioConfig& config);
    void stop();

    // oboe::AudioStreamCallback interface returns data
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) override;

private:
    std::shared_ptr<oboe::AudioStream> mStream;
    OpusEncoder *mOpusEncoder = nullptr;
    int mUdpSocket = -1;

    struct sockaddr_in mDestAddr;

    NativeAudioConfig mConfig;
    int mFrameSizeSamples;
    
    std::vector<int16_t> mPcmBuffer;
    std::mutex mBufferMutex;

    uint16_t mRtpSequenceNumber = 0;
    uint32_t mRtpTimestamp = 0;
    
    void initUdpSocket();
    void sendRtpPacket(const uint8_t* opusData, size_t opusSize);
};
