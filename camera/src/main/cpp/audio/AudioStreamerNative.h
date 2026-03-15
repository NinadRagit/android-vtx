#pragma once

#include <oboe/Oboe.h>
#include <opus.h>
#include <thread>
#include <atomic>
#include <vector>
#include <mutex>
#include <netinet/in.h>

class AudioStreamerNative : public oboe::AudioStreamCallback {
public:
    AudioStreamerNative();
    ~AudioStreamerNative();

    bool start();
    void stop();

    // oboe::AudioStreamCallback interface returns data
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) override;

private:
    std::shared_ptr<oboe::AudioStream> mStream;
    OpusEncoder *mOpusEncoder = nullptr;
    int mUdpSocket = -1;
    struct sockaddr_in mDestAddr;

    // Opus prefers specific frame sizes: at 48kHz, 10ms is 480 samples.
    // 20ms is 960 samples. We'll use 20ms chunks.
    static constexpr int SAMPLE_RATE = 48000;
    static constexpr int CHANNELS = 1;
    static constexpr int FRAME_SIZE_SAMPLES = 960; // 20ms
    
    std::vector<int16_t> mPcmBuffer;
    std::mutex mBufferMutex;

    uint16_t mRtpSequenceNumber = 0;
    uint32_t mRtpTimestamp = 0;
    
    void initUdpSocket();
    void sendRtpPacket(const uint8_t* opusData, size_t opusSize);
};
