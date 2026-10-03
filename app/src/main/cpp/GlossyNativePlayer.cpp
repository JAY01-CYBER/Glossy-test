#include <jni.h>
#include <oboe/Oboe.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <condition_variable>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include "GlossyDspApi.h"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libswresample/swresample.h>
}

#ifdef LOG_TAG
#undef LOG_TAG
#endif
#define LOG_TAG "GlossyNativePlayer"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kOutputRate = 48000;
constexpr int kOutputChannels = 2;

class FloatRing final {
public:
    explicit FloatRing(size_t capacityFrames) : capSamples_(capacityFrames * 2), data_(capSamples_) {}

    size_t write(const float* src, size_t frames) {
        if (!src || frames == 0) return 0;
        size_t written = 0;
        while (written < frames) {
            const size_t w = writePos_.load(std::memory_order_relaxed);
            const size_t r = readPos_.load(std::memory_order_acquire);
            const size_t used = w - r;
            const size_t freeSamples = capSamples_ - std::min(used, capSamples_);
            if (freeSamples < 2) break;
            const size_t pos = w % capSamples_;
            const size_t chunkFrames = std::min({frames - written, freeSamples / 2, (capSamples_ - pos) / 2});
            std::memcpy(data_.data() + pos, src + written * 2, chunkFrames * 2 * sizeof(float));
            writePos_.store(w + chunkFrames * 2, std::memory_order_release);
            written += chunkFrames;
        }
        return written;
    }

    size_t read(float* dst, size_t frames) {
        if (!dst || frames == 0) return 0;
        size_t read = 0;
        while (read < frames) {
            const size_t r = readPos_.load(std::memory_order_relaxed);
            const size_t w = writePos_.load(std::memory_order_acquire);
            const size_t available = w - r;
            if (available < 2) break;
            const size_t pos = r % capSamples_;
            const size_t chunkFrames = std::min({frames - read, available / 2, (capSamples_ - pos) / 2});
            std::memcpy(dst + read * 2, data_.data() + pos, chunkFrames * 2 * sizeof(float));
            readPos_.store(r + chunkFrames * 2, std::memory_order_release);
            read += chunkFrames;
        }
        return read;
    }

    void clear() {
        const size_t w = writePos_.load(std::memory_order_acquire);
        readPos_.store(w, std::memory_order_release);
    }

private:
    size_t capSamples_;
    std::vector<float> data_;
    std::atomic<size_t> writePos_{0};
    std::atomic<size_t> readPos_{0};
};

class Player final : public oboe::AudioStreamDataCallback {
public:
    Player() : ring_(static_cast<size_t>(192000) * 4) {
        avformat_network_init(); // Initialize FFmpeg network
    }
    
    ~Player() override { 
        stop(); 
        if (dsp_) glossy_dsp_release(dsp_); 
        avformat_network_deinit();
    }

    bool play(const std::string& url, int64_t startMs) {
        if (url.empty()) return false;
        stop();
        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            url_ = url;
        }
        const int64_t safeStartMs = std::max<int64_t>(0, startMs);
        seekMs_.store(safeStartMs, std::memory_order_release);
        seekRequested_.store(safeStartMs > 0, std::memory_order_release);
        renderedFrames_.store(0, std::memory_order_release);
        playbackBaseMs_.store(safeStartMs, std::memory_order_release);
        positionPrimed_.store(false, std::memory_order_release);
        stopRequested_.store(false, std::memory_order_release);
        paused_.store(false, std::memory_order_release);
        error_.store(false, std::memory_order_release);
        initDone_.store(false, std::memory_order_release);
        
        worker_ = std::thread(&Player::decodeLoop, this);
        
        const bool ready = waitUntilReady(15000);
        if (!ready) stop();
        return ready;
    }

    void pause() {
        paused_.store(true, std::memory_order_release);
        if (stream_) stream_->requestPause();
    }

    void resume() {
        paused_.store(false, std::memory_order_release);
        if (stream_) stream_->requestStart();
    }

    void stop() {
        stopRequested_.store(true, std::memory_order_release);
        if (stream_) stream_->requestStop();
        if (worker_.joinable()) worker_.join();
        closeCodec();
        closeStream();
        ring_.clear();
        playing_.store(false, std::memory_order_release);
    }

    bool seek(int64_t ms) {
        const int64_t target = std::max<int64_t>(0, ms);
        if (!hasMedia_.load(std::memory_order_acquire)) return false;
        seekMs_.store(target, std::memory_order_release);
        seekRequested_.store(true, std::memory_order_release);
        return true;
    }

    int64_t position() const {
        const int64_t base = playbackBaseMs_.load(std::memory_order_acquire);
        const uint64_t frames = renderedFrames_.load(std::memory_order_acquire);
        const int rate = std::max(1, outputRate_.load(std::memory_order_acquire));
        return base + static_cast<int64_t>((frames * 1000ULL) / static_cast<uint64_t>(rate));
    }
    
    int64_t duration() const { return durationMs_.load(std::memory_order_acquire); }
    bool isPlaying() const { return playing_.load(std::memory_order_acquire) && !paused_.load(std::memory_order_acquire); }
    bool hasError() const { return error_.load(std::memory_order_acquire); }
    
    bool waitUntilReady(int timeoutMs) {
        std::unique_lock<std::mutex> lock(initMutex_);
        initCv_.wait_for(lock, std::chrono::milliseconds(std::max(1, timeoutMs)), [this] {
            return initDone_.load(std::memory_order_acquire) || stopRequested_.load(std::memory_order_acquire);
        });
        return hasMedia_.load(std::memory_order_acquire) && !hasError();
    }

    void setVolume(float v) { volume_.store(std::clamp(v, 0.0f, 1.0f), std::memory_order_release); }

    void setDsp(const int* bands, int count, bool enabled, bool bass, int bassStrength,
                bool virtualizer, int virtualizerStrength, bool spatial, int spatialStrength,
                bool crossfeed, int crossfeedStrength, bool reverb, int reverbMix,
                bool clarity, int clarityStrength, bool compressor, int compressorStrength,
                bool limiter, int limiterStrength,
                bool gain, int gainMb, bool headroom, bool bypass) {
        std::lock_guard<std::mutex> lock(dspMutex_);
        if (!dsp_) dsp_ = glossy_dsp_create();
        if (dsp_) {
            glossy_dsp_set(dsp_, enabled, bands, count, bass, bassStrength, virtualizer,
                           virtualizerStrength, gain, gainMb, headroom, bypass, spatial, spatialStrength, crossfeed, crossfeedStrength,
                           reverb, reverbMix, clarity, clarityStrength, compressor, compressorStrength, limiter, limiterStrength);
            dspDirty_ = true;
        }
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData, int32_t numFrames) override {
        const size_t frames = static_cast<size_t>(std::max<int32_t>(0, numFrames));
        if (!audioData || frames == 0) return oboe::DataCallbackResult::Continue;

        if (scratch_.size() < frames * 2) {
            std::memset(audioData, 0, frames * static_cast<size_t>(outputChannels_) * sizeof(float));
            underruns_.fetch_add(1, std::memory_order_relaxed);
            return oboe::DataCallbackResult::Continue;
        }
        
        const size_t got = ring_.read(scratch_.data(), frames);
        const float volume = volume_.load(std::memory_order_relaxed);
        auto* out = static_cast<float*>(audioData);
        
        for (size_t f = 0; f < frames; ++f) {
            const float l = f < got ? scratch_[f * 2] * volume : 0.0f;
            const float r = f < got ? scratch_[f * 2 + 1] * volume : 0.0f;
            if (outputChannels_ == 1) {
                out[f] = 0.5f * (l + r);
            } else {
                out[f * 2] = l;
                out[f * 2 + 1] = r;
            }
        }
        
        if (got > 0 && !paused_.load(std::memory_order_relaxed)) {
            renderedFrames_.fetch_add(got, std::memory_order_relaxed);
        }
        if (got < frames) underruns_.fetch_add(1, std::memory_order_relaxed);
        return oboe::DataCallbackResult::Continue;
    }

private:
    void closeCodec() {
        if (swrCtx_) { swr_free(&swrCtx_); }
        if (codecCtx_) { avcodec_free_context(&codecCtx_); }
        if (formatCtx_) { avformat_close_input(&formatCtx_); }
        audioStreamIdx_ = -1;
        hasMedia_.store(false, std::memory_order_release);
    }

    void closeStream() {
        if (stream_) { stream_->requestStop(); stream_->close(); stream_.reset(); }
    }

    bool openStream() {
        closeStream();
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(kOutputChannels)
            ->setSampleRate(kOutputRate)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Shared)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(this);
            
        oboe::Result r = builder.openStream(stream_);
        if (r != oboe::Result::OK) {
            LOGE("Oboe open failed: %s", oboe::convertToText(r));
            return false;
        }
        
        outputRate_.store(stream_->getSampleRate(), std::memory_order_release);
        outputChannels_ = stream_->getChannelCount();
        
        const int32_t burst = stream_->getFramesPerBurst();
        scratch_.assign(static_cast<size_t>(std::max<int32_t>(burst > 0 ? burst : 192, 192)) * 2, 0.0f);
        
        r = stream_->requestStart();
        if (r != oboe::Result::OK) {
            LOGE("Oboe start failed: %s", oboe::convertToText(r));
            closeStream();
            return false;
        }
        return true;
    }

    bool openCodec() {
        std::string url;
        { std::lock_guard<std::mutex> lock(stateMutex_); url = url_; }

        // 1. Open Input
        formatCtx_ = avformat_alloc_context();
        
        AVDictionary* options = nullptr;
        av_dict_set(&options, "user_agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36", 0);
        av_dict_set(&options, "reconnect", "1", 0);
        av_dict_set(&options, "reconnect_streamed", "1", 0);
        av_dict_set(&options, "reconnect_delay_max", "2", 0);

        if (avformat_open_input(&formatCtx_, url.c_str(), nullptr, &options) != 0) {
            LOGE("FFmpeg: Could not open input");
            if (options) av_dict_free(&options);
            return false;
        }
        if (options) av_dict_free(&options);

        // 2. Find Stream Info
        if (avformat_find_stream_info(formatCtx_, nullptr) < 0) {
            LOGE("FFmpeg: Could not find stream info");
            return false;
        }

        // 3. Find Audio Stream
        const AVCodec* codec = nullptr;
        audioStreamIdx_ = av_find_best_stream(formatCtx_, AVMEDIA_TYPE_AUDIO, -1, -1, &codec, 0);
        if (audioStreamIdx_ < 0 || !codec) {
            LOGE("FFmpeg: Could not find audio stream");
            return false;
        }

        // 4. Open Codec
        codecCtx_ = avcodec_alloc_context3(codec);
        avcodec_parameters_to_context(codecCtx_, formatCtx_->streams[audioStreamIdx_]->codecpar);
        if (avcodec_open2(codecCtx_, codec, nullptr) < 0) {
            LOGE("FFmpeg: Could not open codec");
            return false;
        }

        // 5. Setup SwrContext (Resampler to 48kHz, Stereo, Float)
        AVChannelLayout out_ch_layout;
        av_channel_layout_default(&out_ch_layout, kOutputChannels);

        swr_alloc_set_opts2(&swrCtx_,
                            &out_ch_layout, AV_SAMPLE_FMT_FLT, kOutputRate,
                            &codecCtx_->ch_layout, codecCtx_->sample_fmt, codecCtx_->sample_rate,
                            0, nullptr);
                            
        if (swr_init(swrCtx_) < 0) {
            LOGE("FFmpeg: Failed to initialize resampler");
            return false;
        }

        // 6. Set Duration
        if (formatCtx_->duration != AV_NOPTS_VALUE) {
            durationMs_.store(formatCtx_->duration / (AV_TIME_BASE / 1000), std::memory_order_release);
        }

        // 7. Open Oboe Stream & Configure DSP
        if (!openStream()) return false;
        
        {
            std::lock_guard<std::mutex> lock(dspMutex_);
            if (!dsp_) dsp_ = glossy_dsp_create();
            if (dsp_) {
                glossy_dsp_configure(dsp_, outputRate_.load(std::memory_order_acquire), kOutputChannels, 4); // 4 = Float
                dspDirty_ = false;
            }
        }
        
        hasMedia_.store(true, std::memory_order_release);
        return true;
    }

    void resetDsp() {
        std::lock_guard<std::mutex> lock(dspMutex_);
        if (dsp_) { 
            glossy_dsp_reset(dsp_); 
            glossy_dsp_configure(dsp_, outputRate_.load(std::memory_order_acquire), kOutputChannels, 4); 
        }
    }

    void decodeLoop() {
        if (!openCodec()) {
            LOGE("Glossy FFmpeg decoder/output initialization failed");
            error_.store(true, std::memory_order_release);
            playing_.store(false, std::memory_order_release);
            initDone_.store(true, std::memory_order_release);
            initCv_.notify_all();
            return;
        }
        
        playing_.store(true, std::memory_order_release);
        initDone_.store(true, std::memory_order_release);
        initCv_.notify_all();

        AVPacket* pkt = av_packet_alloc();
        AVFrame* frame = av_frame_alloc();
        std::vector<float> resampledData;

        while (!stopRequested_.load(std::memory_order_acquire)) {
            
            // Handle Seeking
            if (seekRequested_.load(std::memory_order_acquire)) {
                int64_t targetMs = std::max<int64_t>(0, seekMs_.load(std::memory_order_acquire));
                int64_t targetPts = targetMs * AV_TIME_BASE / 1000;
                
                av_seek_frame(formatCtx_, -1, targetPts, AVSEEK_FLAG_BACKWARD);
                avcodec_flush_buffers(codecCtx_);
                
                ring_.clear();
                resetDsp();
                playbackBaseMs_.store(targetMs, std::memory_order_release);
                renderedFrames_.store(0, std::memory_order_release);
                seekRequested_.store(false, std::memory_order_release);
            }
            
            // Handle Pausing
            if (paused_.load(std::memory_order_acquire)) {
                std::this_thread::sleep_for(std::chrono::milliseconds(5));
                continue;
            }

            // Read Frame
            if (av_read_frame(formatCtx_, pkt) >= 0) {
                if (pkt->stream_index == audioStreamIdx_) {
                    if (avcodec_send_packet(codecCtx_, pkt) == 0) {
                        while (avcodec_receive_frame(codecCtx_, frame) == 0) {
                            
                            // Resample decoded frame to 48kHz, Float, Stereo
                            int outSamples = swr_get_out_samples(swrCtx_, frame->nb_samples);
                            if (resampledData.size() < static_cast<size_t>(outSamples * 2)) {
                                resampledData.resize(outSamples * 2);
                            }

                            uint8_t* outData[1] = { reinterpret_cast<uint8_t*>(resampledData.data()) };
                            int converted = swr_convert(swrCtx_, outData, outSamples, (const uint8_t**)frame->data, frame->nb_samples);

                            if (converted > 0) {
                                std::vector<float> processed(converted * 2);
                                {
                                    std::lock_guard<std::mutex> lock(dspMutex_);
                                    if (dsp_) {
                                        glossy_dsp_process(dsp_, resampledData.data(), processed.data(), converted * 2 * sizeof(float));
                                    } else {
                                        processed.assign(resampledData.begin(), resampledData.begin() + converted * 2);
                                    }
                                }
                                
                                ring_.write(processed.data(), converted);

                                // Update Time
                                if (frame->pts != AV_NOPTS_VALUE) {
                                    int64_t timeMs = frame->pts * av_q2d(formatCtx_->streams[audioStreamIdx_]->time_base) * 1000;
                                    
                                    bool expected = false;
                                    if (positionPrimed_.compare_exchange_strong(expected, true, std::memory_order_acq_rel)) {
                                        playbackBaseMs_.store(timeMs, std::memory_order_release);
                                    }
                                }
                            }
                        }
                    }
                }
                av_packet_unref(pkt);
            } else {
                // End of File Reached
                break;
            }
        }

        av_frame_free(&frame);
        av_packet_free(&pkt);
        closeStream();
        playing_.store(false, std::memory_order_release);
        hasMedia_.store(false, std::memory_order_release);
    }

    std::mutex stateMutex_;
    std::mutex dspMutex_;
    std::mutex initMutex_;
    std::condition_variable initCv_;
    std::string url_;
    
    // FFmpeg Contexts
    AVFormatContext* formatCtx_ = nullptr;
    AVCodecContext* codecCtx_ = nullptr;
    SwrContext* swrCtx_ = nullptr;
    int audioStreamIdx_ = -1;
    
    std::shared_ptr<oboe::AudioStream> stream_;
    std::thread worker_;
    FloatRing ring_;
    void* dsp_ = nullptr;

    std::atomic<int> outputRate_{kOutputRate};
    int outputChannels_ = kOutputChannels;
    std::vector<float> scratch_;
    bool dspDirty_ = false;

    std::atomic<bool> stopRequested_{false};
    std::atomic<bool> paused_{false};
    std::atomic<bool> seekRequested_{false};
    std::atomic<bool> playing_{false};
    std::atomic<bool> hasMedia_{false};
    std::atomic<bool> error_{false};
    std::atomic<bool> initDone_{false};
    std::atomic<uint64_t> underruns_{0};
    std::atomic<int64_t> seekMs_{0};
    std::atomic<int64_t> playbackBaseMs_{0};
    std::atomic<uint64_t> renderedFrames_{0};
    std::atomic<bool> positionPrimed_{false};
    std::atomic<int64_t> durationMs_{0};
    std::atomic<float> volume_{1.0f};
};

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_jay_glossy_ui_player_NativePlayer_nCreate(JNIEnv*, jobject) { return reinterpret_cast<jlong>(new Player()); }

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jay_glossy_ui_player_NativePlayer_nPlayUrl(JNIEnv* env, jobject, jlong h, jstring url, jlong pos) {
    auto* p = reinterpret_cast<Player*>(h); if (!p || !url) return JNI_FALSE;
    const char* s = env->GetStringUTFChars(url, nullptr); if (!s) return JNI_FALSE;
    const bool ok = p->play(s, pos); env->ReleaseStringUTFChars(url, s); return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL Java_com_jay_glossy_ui_player_NativePlayer_nPause(JNIEnv*, jobject, jlong h) { if (auto* p=reinterpret_cast<Player*>(h)) p->pause(); }
extern "C" JNIEXPORT void JNICALL Java_com_jay_glossy_ui_player_NativePlayer_nResume(JNIEnv*, jobject, jlong h) { if (auto* p=reinterpret_cast<Player*>(h)) p->resume(); }
extern "C" JNIEXPORT void JNICALL Java_com_jay_glossy_ui_player_NativePlayer_nStop(JNIEnv*, jobject, jlong h) { if (auto* p=reinterpret_cast<Player*>(h)) p->stop(); }
extern "C" JNIEXPORT jboolean JNICALL Java_com_jay_glossy_ui_player_NativePlayer_nSeekTo(JNIEnv*, jobject, jlong h, jlong ms) { auto* p=reinterpret_cast<Player*>(h); return p && p->seek(ms) ? JNI_TRUE : JNI_FALSE; }
extern "C" JNIEXPORT jlong JNICALL Java_com_jay_glossy_ui_player_NativePlayer_nPosition(JNIEnv*, jobject, jlong h) { auto* p=reinterpret_cast<Player*>(h); return p ? p->position() : 0; }
extern "C" JNIEXPORT jlong JNICALL Java_com_jay_glossy_ui_player_NativePlayer_nDuration(JNIEnv*, jobject, jlong h) { auto* p=reinterpret_cast<Player*>(h); return p ? p->duration() : 0; }
extern "C" JNIEXPORT jboolean JNICALL Java_com_jay_glossy_ui_player_NativePlayer_nWaitUntilReady(JNIEnv*, jobject, jlong h, jint timeoutMs) { auto* p=reinterpret_cast<Player*>(h); return p && p->waitUntilReady(timeoutMs) ? JNI_TRUE : JNI_FALSE; }
extern "C" JNIEXPORT jboolean JNICALL Java_com_jay_glossy_ui_player_NativePlayer_nIsPlaying(JNIEnv*, jobject, jlong h) { auto* p=reinterpret_cast<Player*>(h); return p && p->isPlaying() ? JNI_TRUE : JNI_FALSE; }
extern "C" JNIEXPORT jboolean JNICALL Java_com_jay_glossy_ui_player_NativePlayer_nHasError(JNIEnv*, jobject, jlong h) { auto* p=reinterpret_cast<Player*>(h); return p && p->hasError() ? JNI_TRUE : JNI_FALSE; }
extern "C" JNIEXPORT void JNICALL Java_com_jay_glossy_ui_player_NativePlayer_nSetVolume(JNIEnv*, jobject, jlong h, jfloat v) { if (auto* p=reinterpret_cast<Player*>(h)) p->setVolume(v); }

extern "C" JNIEXPORT void JNICALL Java_com_jay_glossy_ui_player_NativePlayer_nSetDsp(JNIEnv* env, jobject, jlong h, jboolean enabled, jintArray bandsMb, jboolean bass, jint bassStrength, jboolean virtualizer, jint virtualizerStrength, jboolean spatial, jint spatialStrength, jboolean crossfeed, jint crossfeedStrength, jboolean reverb, jint reverbMix, jboolean clarity, jint clarityStrength, jboolean compressor, jint compressorStrength, jboolean limiter, jint limiterStrength, jboolean gain, jint gainMb, jboolean headroom, jboolean bypass) {
    auto* p=reinterpret_cast<Player*>(h); if (!p) return;
    std::vector<int> bands;
    if (bandsMb) { const jsize n=env->GetArrayLength(bandsMb); bands.resize(static_cast<size_t>(n)); if (n>0) env->GetIntArrayRegion(bandsMb,0,n,bands.data()); }
    p->setDsp(bands.data(), static_cast<int>(bands.size()), enabled==JNI_TRUE, bass==JNI_TRUE, bassStrength, virtualizer==JNI_TRUE, virtualizerStrength,
              spatial==JNI_TRUE, spatialStrength, crossfeed==JNI_TRUE, crossfeedStrength, reverb==JNI_TRUE, reverbMix,
              clarity==JNI_TRUE, clarityStrength, compressor==JNI_TRUE, compressorStrength, limiter==JNI_TRUE, limiterStrength,
              gain==JNI_TRUE, gainMb, headroom==JNI_TRUE, bypass==JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL Java_com_jay_glossy_ui_player_NativePlayer_nRelease(JNIEnv*, jobject, jlong h) { delete reinterpret_cast<Player*>(h); }
