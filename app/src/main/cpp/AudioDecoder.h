#ifndef GLOSSY_AUDIO_DECODER_H
#define GLOSSY_AUDIO_DECODER_H

#include <string>
#include <memory>
#include <android/log.h>
#include <oboe/Oboe.h>
#include <thread>
#include <mutex>
#include <atomic>
#include <deque>
#include <vector>
#include "GlossyDspApi.h"

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavutil/opt.h>
}

#define LOG_TAG "GlossyFFmpegFallback"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

class AudioDecoder : public oboe::AudioStreamDataCallback {
public:
    AudioDecoder();
    ~AudioDecoder() override;

    bool openUrl(const std::string& url, int64_t startPositionMs = 0);
    void pause();
    void resume();
    void stop();
    void release();
    bool seekTo(int64_t positionMs);
    void setVolume(float vol);
    void setDsp(const int* bands, int count, bool enabled, bool bass, int bassStrength,
                bool virtualizer, int virtualizerStrength, bool spatial, int spatialStrength,
                bool crossfeed, int crossfeedStrength, bool reverb, int reverbMix,
                bool gain, int gainMb, bool headroom, bool bypass);

    int64_t getCurrentPositionMs() const { return currentPositionMs.load(); }
    int64_t getDurationMs() const { return durationMs.load(); }
    bool isPlaying() const { return isPlaying_.load() && !isPaused.load(); }
    bool hasError() const { return error_.load(); }
    bool shouldInterrupt() const { return !isDecoding.load(); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void*, int32_t) override;

private:
    AVFormatContext* formatCtx = nullptr;
    AVCodecContext* codecCtx = nullptr;
    AVFrame* frame = nullptr;
    AVPacket* packet = nullptr;
    SwrContext* swrCtx = nullptr;
    std::shared_ptr<oboe::AudioStream> audioStream;

    int audioStreamIndex = -1;
    int targetSampleRate = 48000;
    int targetChannels = 2;
    int sourceChannels = 2;
    int sourceSampleRate = 48000;

    std::atomic<bool> isPlaying_{false};
    std::atomic<bool> isPaused{false};
    std::atomic<bool> seekRequested{false};
    std::atomic<int64_t> seekTargetMs{0};
    std::atomic<float> volume{1.0f};
    std::atomic<int64_t> currentPositionMs{0};
    std::atomic<int64_t> durationMs{0};
    std::atomic<bool> isDecoding{false};
    std::atomic<bool> error_{false};

    std::thread decoderThread;
    std::mutex bufferMutex;
    std::deque<int16_t> audioBuffer;
    const size_t MAX_BUFFER_SIZE = 48000 * 2 * 6;

    std::mutex dspMutex;
    void* dsp_ = nullptr;
    bool dspDirty_ = false;

    void decodeLoop();
    void releaseCodec();
    bool openOutputStream();
    void processFrame(int64_t ptsUs);
    bool configureResampler();
};

#endif
