#include <jni.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>
#include <media/NdkMediaDataSource.h>
#include <android/api-level.h>
#include <oboe/Oboe.h>
#include <android/log.h>
#include <algorithm>
#include <dlfcn.h>
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
#include "AudioDecoder.h"

#ifdef LOG_TAG
#undef LOG_TAG
#endif
#ifdef LOGE
#undef LOGE
#endif
#ifdef LOGI
#undef LOGI
#endif
#define LOG_TAG "GlossyNativePlayer"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kOutputRate = 48000;
constexpr int kOutputChannels = 2;
constexpr int kPcm16Encoding = 2;
constexpr int kPcmFloatEncoding = 4;
constexpr char kPcmEncodingKey[] = "pcm-encoding";

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

namespace {
using FnAMediaDataSourceClose = void (*)(AMediaDataSource*);
using FnAMediaDataSourceDelete = void (*)(AMediaDataSource*);
using FnAMediaDataSourceNewUri = AMediaDataSource* (*)(const char*, int, const char* const*);
using FnAMediaExtractorSetDataSourceCustom = media_status_t (*)(AMediaExtractor*, AMediaDataSource*);

void* mediaNdkHandle() {
    static void* handle = []() -> void* {
        return dlopen("libmediandk.so", RTLD_NOW | RTLD_LOCAL);
    }();
    return handle;
}

template <typename T>
T mediaNdkSymbol(const char* name) {
    void* handle = mediaNdkHandle();
    return handle ? reinterpret_cast<T>(dlsym(handle, name)) : nullptr;
}

void closeDataSourceCompat(AMediaDataSource* source) {
    if (!source) return;
    if (auto fn = mediaNdkSymbol<FnAMediaDataSourceClose>("AMediaDataSource_close")) fn(source);
}

void deleteDataSourceCompat(AMediaDataSource* source) {
    if (!source) return;
    if (auto fn = mediaNdkSymbol<FnAMediaDataSourceDelete>("AMediaDataSource_delete")) fn(source);
}

AMediaDataSource* newUriDataSourceCompat(const char* uri, int numHeaders, const char* const* headers) {
    if (auto fn = mediaNdkSymbol<FnAMediaDataSourceNewUri>("AMediaDataSource_newUri")) {
        return fn(uri, numHeaders, headers);
    }
    return nullptr;
}

media_status_t setDataSourceCustomCompat(AMediaExtractor* extractor, AMediaDataSource* source) {
    if (auto fn = mediaNdkSymbol<FnAMediaExtractorSetDataSourceCustom>("AMediaExtractor_setDataSourceCustom")) {
        return fn(extractor, source);
    }
    return AMEDIA_ERROR_UNSUPPORTED;
}
} // namespace

class Player final : public oboe::AudioStreamDataCallback {
public:
    Player() : ring_(static_cast<size_t>(192000) * 4) {}
    ~Player() override { stop(); if (dsp_) glossy_dsp_release(dsp_); }

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
        usingFallback_.store(false, std::memory_order_release);
        initDone_.store(false, std::memory_order_release);
        worker_ = std::thread(&Player::decodeLoop, this);
        // Do not report success until native decoder + output path are actually initialized.
        // This makes the Kotlin fallback decision deterministic instead of optimistic.
        const bool ready = waitUntilReady(15000);
        if (!ready) stop();
        return ready;
    }

    void pause() {
        paused_.store(true, std::memory_order_release);
        if (usingFallback_.load(std::memory_order_acquire) && fallback_) fallback_->pause();
        if (stream_) stream_->requestPause();
    }

    void resume() {
        paused_.store(false, std::memory_order_release);
        if (usingFallback_.load(std::memory_order_acquire) && fallback_) fallback_->resume();
        if (stream_) stream_->requestStart();
    }

    void stop() {
        stopRequested_.store(true, std::memory_order_release);
        if (fallback_) fallback_->stop();
        usingFallback_.store(false, std::memory_order_release);
        if (dataSource_ && !dataSourceClosed_) {
            closeDataSourceCompat(dataSource_);
            dataSourceClosed_ = true;
        }
        if (stream_) stream_->requestStop();
        if (worker_.joinable()) worker_.join();
        closeCodec();
        closeStream();
        ring_.clear();
        playing_.store(false, std::memory_order_release);
    }

    bool seek(int64_t ms) {
        const int64_t target = std::max<int64_t>(0, ms);
        if (usingFallback_.load(std::memory_order_acquire) && fallback_) return fallback_->seekTo(target);
        if (!hasMedia_.load(std::memory_order_acquire)) return false;
        seekMs_.store(target, std::memory_order_release);
        seekRequested_.store(true, std::memory_order_release);
        return true;
    }

    int64_t position() const {
        if (usingFallback_.load(std::memory_order_acquire) && fallback_) return fallback_->getCurrentPositionMs();
        const int64_t base = playbackBaseMs_.load(std::memory_order_acquire);
        const uint64_t frames = renderedFrames_.load(std::memory_order_acquire);
        const int rate = std::max(1, outputRate_.load(std::memory_order_acquire));
        return base + static_cast<int64_t>((frames * 1000ULL) / static_cast<uint64_t>(rate));
    }
    int64_t duration() const { return usingFallback_.load(std::memory_order_acquire) && fallback_ ? fallback_->getDurationMs() : durationMs_.load(std::memory_order_acquire); }
    bool isPlaying() const { return usingFallback_.load(std::memory_order_acquire) && fallback_ ? fallback_->isPlaying() : (playing_.load(std::memory_order_acquire) && !paused_.load(std::memory_order_acquire)); }
    bool hasError() const { return usingFallback_.load(std::memory_order_acquire) && fallback_ ? fallback_->hasError() : error_.load(std::memory_order_acquire); }
    bool waitUntilReady(int timeoutMs) {
        std::unique_lock<std::mutex> lock(initMutex_);
        initCv_.wait_for(lock, std::chrono::milliseconds(std::max(1, timeoutMs)), [this] {
            return initDone_.load(std::memory_order_acquire) || stopRequested_.load(std::memory_order_acquire);
        });
        return hasMedia_.load(std::memory_order_acquire) && !hasError();
    }

    void setVolume(float v) { volume_.store(std::clamp(v, 0.0f, 1.0f), std::memory_order_release); if (usingFallback_.load(std::memory_order_acquire) && fallback_) fallback_->setVolume(v); }

    void setDsp(const int* bands, int count, bool enabled, bool bass, int bassStrength,
                bool virtualizer, int virtualizerStrength, bool spatial, int spatialStrength,
                bool crossfeed, int crossfeedStrength, bool reverb, int reverbMix,
                bool gain, int gainMb, bool headroom, bool bypass) {
        std::lock_guard<std::mutex> lock(dspMutex_);
        if (!dsp_) dsp_ = glossy_dsp_create();
        if (dsp_) {
            glossy_dsp_set(dsp_, enabled, bands, count, bass, bassStrength, virtualizer,
                           virtualizerStrength, gain, gainMb, headroom, bypass, spatial, spatialStrength, crossfeed, crossfeedStrength, reverb, reverbMix);
            dspDirty_ = true;
        }
        if (usingFallback_.load(std::memory_order_acquire) && fallback_) {
            fallback_->setDsp(bands, count, enabled, bass, bassStrength, virtualizer,
                              virtualizerStrength, gain, gainMb, headroom, bypass, spatial, spatialStrength, crossfeed, crossfeedStrength, reverb, reverbMix);
        }
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData, int32_t numFrames) override {
        auto* out = static_cast<float*>(audioData);
        const size_t frames = static_cast<size_t>(std::max<int32_t>(0, numFrames));
        const size_t got = ring_.read(out, frames);
        if (got > 0 && !paused_.load(std::memory_order_relaxed)) {
            renderedFrames_.fetch_add(got, std::memory_order_relaxed);
        }
        const float volume = volume_.load(std::memory_order_relaxed);
        const size_t total = frames * 2;
        for (size_t i = got * 2; i < total; ++i) out[i] = 0.0f;
        for (size_t i = 0; i < got * 2; ++i) out[i] *= volume;
        if (got < frames) underruns_.fetch_add(1, std::memory_order_relaxed);
        return oboe::DataCallbackResult::Continue;
    }

private:
    void closeCodec() {
        if (codec_) { AMediaCodec_stop(codec_); AMediaCodec_delete(codec_); codec_ = nullptr; }
        if (extractor_) { AMediaExtractor_delete(extractor_); extractor_ = nullptr; }
        if (trackFormat_) { AMediaFormat_delete(trackFormat_); trackFormat_ = nullptr; }
        if (dataSource_) {
            if (!dataSourceClosed_) closeDataSourceCompat(dataSource_);
            deleteDataSourceCompat(dataSource_);
            dataSource_ = nullptr;
        }
        mime_.clear();
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
        const int actualRate = stream_->getSampleRate();
        const int actualChannels = stream_->getChannelCount();
        if (actualRate <= 0 || actualChannels != kOutputChannels) {
            LOGE("Unsupported Oboe output format: rate=%d channels=%d", actualRate, actualChannels);
            closeStream();
            return false;
        }
        outputRate_.store(actualRate, std::memory_order_release);
        LOGI("Oboe output: requested=%d actual=%d channels=%d", kOutputRate, actualRate, actualChannels);
        r = stream_->requestStart();
        if (r != oboe::Result::OK) {
            LOGE("Oboe start failed: %s", oboe::convertToText(r));
            closeStream();
            return false;
        }
        return true;
    }

    bool openCodec() {
        extractor_ = AMediaExtractor_new();
        if (!extractor_) return false;
        std::string url;
        { std::lock_guard<std::mutex> lock(stateMutex_); url = url_; }
        media_status_t status = AMEDIA_ERROR_UNSUPPORTED;
        {
            const char* headers[] = {
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36",
                "Accept",
                "*/*",
                "Connection",
                "keep-alive"
            };
            AMediaDataSource* source = newUriDataSourceCompat(url.c_str(), 3, headers);
            if (source) {
                dataSource_ = source;
                dataSourceClosed_ = false;
                status = setDataSourceCustomCompat(extractor_, source);
                if (status != AMEDIA_OK) {
                    closeDataSourceCompat(source);
                    deleteDataSourceCompat(source);
                    dataSource_ = nullptr;
                }
            }
        }
        if (status != AMEDIA_OK) {
            status = AMediaExtractor_setDataSource(extractor_, url.c_str());
        }
        if (status != AMEDIA_OK) { LOGE("Extractor setDataSource failed: %d", status); return false; }

        const size_t count = AMediaExtractor_getTrackCount(extractor_);
        for (size_t i = 0; i < count; ++i) {
            AMediaFormat* fmt = AMediaExtractor_getTrackFormat(extractor_, i);
            const char* mime = nullptr;
            const bool audio = fmt && AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime) &&
                               mime && std::strncmp(mime, "audio/", 6) == 0;
            if (!audio) { if (fmt) AMediaFormat_delete(fmt); continue; }

            trackFormat_ = fmt;
            AMediaExtractor_selectTrack(extractor_, i);
            mime_ = mime;
            AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sourceRate_);
            AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &sourceChannels_);
            sourceRate_ = std::clamp(sourceRate_, 8000, 192000);
            trackSourceRate_ = sourceRate_;
            sourceChannels_ = std::clamp(sourceChannels_, 1, 2);
            int64_t durationUs = 0;
            if (AMediaFormat_getInt64(fmt, AMEDIAFORMAT_KEY_DURATION, &durationUs) && durationUs > 0) {
                durationMs_.store(durationUs / 1000, std::memory_order_release);
            }
            int32_t pcmEncoding = kPcm16Encoding;
            AMediaFormat_getInt32(fmt, kPcmEncodingKey, &pcmEncoding);
            pcmEncoding_ = pcmEncoding;

            codec_ = AMediaCodec_createDecoderByType(mime_.c_str());
            if (!codec_) return false;
            if (AMediaCodec_configure(codec_, fmt, nullptr, nullptr, 0) != AMEDIA_OK) return false;
            if (AMediaCodec_start(codec_) != AMEDIA_OK) return false;
            if (!openStream()) return false;
            {
                std::lock_guard<std::mutex> lock(dspMutex_);
                if (!dsp_) dsp_ = glossy_dsp_create();
                if (!dsp_) return false;
                glossy_dsp_configure(dsp_, outputRate_.load(std::memory_order_acquire), kOutputChannels, 4);
                dspDirty_ = false;
            }
            hasMedia_.store(true, std::memory_order_release);
            return true;
        }
        return false;
    }

    void resetDsp() {
        std::lock_guard<std::mutex> lock(dspMutex_);
        if (dsp_) { glossy_dsp_reset(dsp_); glossy_dsp_configure(dsp_, outputRate_.load(std::memory_order_acquire), kOutputChannels, 4); }
    }

    float decodeSample(const uint8_t* data, size_t index) const {
        switch (pcmEncoding_) {
            case kPcmFloatEncoding: {
                float v; std::memcpy(&v, data + index * sizeof(float), sizeof(float)); return std::clamp(v, -1.0f, 1.0f);
            }
            case 3: { // PCM 8-bit unsigned
                return (static_cast<int>(data[index]) - 128) / 128.0f;
            }
            default: {
                int16_t v; std::memcpy(&v, data + index * sizeof(int16_t), sizeof(int16_t)); return static_cast<float>(v) / 32768.0f;
            }
        }
    }

    size_t decodeToStereo(const uint8_t* pcm, size_t bytes, std::vector<float>& stereo) {
        if (!pcm || bytes == 0 || sourceChannels_ < 1) return 0;
        size_t bytesPerSample = pcmEncoding_ == kPcmFloatEncoding ? 4 : (pcmEncoding_ == 3 ? 1 : 2);
        const size_t frameBytes = bytesPerSample * static_cast<size_t>(sourceChannels_);
        if (frameBytes == 0) return 0;
        const size_t frames = bytes / frameBytes;
        stereo.resize(frames * 2);
        for (size_t f = 0; f < frames; ++f) {
            const size_t base = f * static_cast<size_t>(sourceChannels_);
            const float l = decodeSample(pcm, base);
            const float r = sourceChannels_ > 1 ? decodeSample(pcm, base + 1) : l;
            stereo[f * 2] = l; stereo[f * 2 + 1] = r;
        }
        return frames;
    }

    // Streaming linear resampler. It keeps one source frame across codec output boundaries.
    size_t resample(const std::vector<float>& input, size_t frames, std::vector<float>& output) {
        if (frames == 0) return 0;
        const int outputRate = std::max(1, outputRate_.load(std::memory_order_acquire));
        if (sourceRate_ == outputRate) { output = input; return frames; }
        std::vector<float> work;
        work.reserve((frames + 1) * 2);
        if (havePrev_) { work.push_back(prevL_); work.push_back(prevR_); }
        work.insert(work.end(), input.begin(), input.begin() + static_cast<std::ptrdiff_t>(frames * 2));
        const size_t workFrames = work.size() / 2;
        const double step = static_cast<double>(sourceRate_) / static_cast<double>(outputRate);
        double pos = resamplePos_;
        output.clear();
        while (pos + 1.0 < static_cast<double>(workFrames)) {
            const size_t i = static_cast<size_t>(pos);
            const double frac = pos - static_cast<double>(i);
            const float l0 = work[i * 2], r0 = work[i * 2 + 1];
            const float l1 = work[(i + 1) * 2], r1 = work[(i + 1) * 2 + 1];
            output.push_back(l0 + static_cast<float>((l1 - l0) * frac));
            output.push_back(r0 + static_cast<float>((r1 - r0) * frac));
            pos += step;
        }
        prevL_ = work[(workFrames - 1) * 2];
        prevR_ = work[(workFrames - 1) * 2 + 1];
        havePrev_ = true;
        resamplePos_ = pos - static_cast<double>(workFrames - 1);
        return output.size() / 2;
    }

    void resetResampler() {
        havePrev_ = false; prevL_ = prevR_ = 0.0f; resamplePos_ = 0.0;
    }

    void performSeek() {
        if (!extractor_ || !codec_) return;
        const int64_t targetMs = std::max<int64_t>(0, seekMs_.load(std::memory_order_acquire));
        AMediaExtractor_seekTo(extractor_, targetMs * 1000, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);
        AMediaCodec_flush(codec_);
        ring_.clear();
        resetResampler();
        resetDsp();
        positionMs_.store(targetMs, std::memory_order_release);
        playbackBaseMs_.store(targetMs, std::memory_order_release);
        renderedFrames_.store(0, std::memory_order_release);
        positionPrimed_.store(false, std::memory_order_release);
        seekRequested_.store(false, std::memory_order_release);
    }

    void processDecoded(const uint8_t* pcm, size_t bytes, int64_t ptsUs) {
        std::vector<float> stereo;
        const size_t frames = decodeToStereo(pcm, bytes, stereo);
        if (!frames) return;
        std::vector<float> resampled;
        const size_t outFrames = resample(stereo, frames, resampled);
        if (!outFrames) return;
        std::vector<float> processed(resampled.size());
        {
            std::lock_guard<std::mutex> lock(dspMutex_);
            if (dsp_) {
                if (dspDirty_) { glossy_dsp_configure(dsp_, outputRate_.load(std::memory_order_acquire), kOutputChannels, 4); dspDirty_ = false; }
                glossy_dsp_process(dsp_, resampled.data(), processed.data(), static_cast<int>(resampled.size() * sizeof(float)));
            } else {
                processed = resampled;
            }
        }
        ring_.write(processed.data(), outFrames);
        // Position is derived from frames actually consumed by Oboe, not decoder PTS.
        // Decoder PTS can run ahead by the entire PCM buffer. Prime the base once from the
        // first decoded timestamp so a seek lands on the decoder's actual sync position.
        bool expected = false;
        if (ptsUs >= 0 && positionPrimed_.compare_exchange_strong(expected, true, std::memory_order_acq_rel)) {
            playbackBaseMs_.store(ptsUs / 1000, std::memory_order_release);
        }
    }

    void flushResamplerAtEos() {
        const int outputRate = std::max(1, outputRate_.load(std::memory_order_acquire));
        if (sourceRate_ == outputRate || !havePrev_) return;
        std::vector<float> tail = {prevL_, prevR_};
        std::vector<float> out;
        // Duplicate the final source frame so the final interpolation interval can be emitted.
        tail.push_back(prevL_); tail.push_back(prevR_);
        resamplePos_ = std::max(0.0, resamplePos_);
        const size_t frames = resample(tail, 2, out);
        if (frames > 0) {
            std::vector<float> processed(out.size());
            std::lock_guard<std::mutex> lock(dspMutex_);
            if (dsp_) glossy_dsp_process(dsp_, out.data(), processed.data(), static_cast<int>(out.size() * sizeof(float)));
            else processed = out;
            ring_.write(processed.data(), frames);
        }
    }

    void decodeLoop() {
        if (!openCodec()) {
            // Android MediaCodec/Extractor is the primary path. FFmpeg is a real native
            // fallback for codecs/container/HTTP cases the platform path cannot open.
            closeStream();
            closeCodec();
            std::string fallbackUrl;
            { std::lock_guard<std::mutex> lock(stateMutex_); fallbackUrl = url_; }
            if (!fallback_) fallback_ = std::make_unique<AudioDecoder>();
            const int64_t start = seekMs_.load(std::memory_order_acquire);
            if (fallback_->openUrl(fallbackUrl, start)) {
                usingFallback_.store(true, std::memory_order_release);
                fallback_->setVolume(volume_.load(std::memory_order_acquire));
                playing_.store(true, std::memory_order_release);
                hasMedia_.store(true, std::memory_order_release);
                initDone_.store(true, std::memory_order_release);
                initCv_.notify_all();
                while (!stopRequested_.load(std::memory_order_acquire) && fallback_->isPlaying()) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(20));
                }
                playing_.store(false, std::memory_order_release);
                hasMedia_.store(false, std::memory_order_release);
                return;
            }
            LOGE("Native MediaCodec and FFmpeg fallback both failed");
            error_.store(true, std::memory_order_release);
            playing_.store(false, std::memory_order_release);
            initDone_.store(true, std::memory_order_release);
            initCv_.notify_all();
            return;
        }
        playing_.store(true, std::memory_order_release);
        initDone_.store(true, std::memory_order_release);
        initCv_.notify_all();
        if (seekRequested_.load(std::memory_order_acquire)) performSeek();

        AMediaCodecBufferInfo info{};
        bool inputDone = false;
        while (!stopRequested_.load(std::memory_order_acquire)) {
            if (seekRequested_.load(std::memory_order_acquire)) { inputDone = false; performSeek(); }
            if (paused_.load(std::memory_order_acquire)) {
                std::this_thread::sleep_for(std::chrono::milliseconds(5));
                continue;
            }
            if (!inputDone) {
                const ssize_t index = AMediaCodec_dequeueInputBuffer(codec_, 10000);
                if (index >= 0) {
                    size_t capacity = 0;
                    uint8_t* buffer = AMediaCodec_getInputBuffer(codec_, index, &capacity);
                    const ssize_t sampleSize = buffer ? AMediaExtractor_readSampleData(extractor_, buffer, capacity) : -1;
                    if (sampleSize < 0) {
                        AMediaCodec_queueInputBuffer(codec_, index, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        const int64_t pts = AMediaExtractor_getSampleTime(extractor_);
                        AMediaCodec_queueInputBuffer(codec_, index, 0, sampleSize, std::max<int64_t>(0, pts), 0);
                        AMediaExtractor_advance(extractor_);
                    }
                }
            }

            const ssize_t outIndex = AMediaCodec_dequeueOutputBuffer(codec_, &info, 10000);
            if (outIndex >= 0) {
                if (info.size > 0) {
                    size_t size = 0;
                    uint8_t* pcm = AMediaCodec_getOutputBuffer(codec_, outIndex, &size);
                    if (pcm && info.offset >= 0 && static_cast<size_t>(info.offset) < size) {
                        const size_t available = size - static_cast<size_t>(info.offset);
                        const size_t bytes = std::min<size_t>(static_cast<size_t>(info.size), available);
                        processDecoded(pcm + info.offset, bytes, info.presentationTimeUs);
                    }
                }
                const bool eos = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
                AMediaCodec_releaseOutputBuffer(codec_, outIndex, false);
                if (eos) { flushResamplerAtEos(); break; }
            } else if (outIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                AMediaFormat* fmt = AMediaCodec_getOutputFormat(codec_);
                if (fmt) {
                    int32_t outputRate = sourceRate_;
                    int32_t outputChannels = sourceChannels_;
                    AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_SAMPLE_RATE, &outputRate);
                    AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &outputChannels);
                    int32_t enc = pcmEncoding_;
                    AMediaFormat_getInt32(fmt, kPcmEncodingKey, &enc);
                    pcmEncoding_ = enc;

                    // MediaCodec output PCM should keep the track's native sample rate.
                    // Some vendor codecs report the device/output rate here (for example
                    // 192 kHz for a 48 kHz stream). Treating that metadata as decoder rate
                    // would make the resampler consume 4 source frames per output frame,
                    // producing the exact ~4x-speed symptom. Keep the track rate authoritative.
                    if (outputRate > 0 && outputRate != trackSourceRate_) {
                        LOGE("Ignoring suspicious MediaCodec output rate=%d; track rate=%d",
                             outputRate, trackSourceRate_);
                    }
                    sourceRate_ = trackSourceRate_;
                    if (outputChannels >= 1 && outputChannels <= 2) {
                        sourceChannels_ = outputChannels;
                    }
                    ring_.clear();
                    resetResampler();
                    resetDsp();
                    AMediaFormat_delete(fmt);
                }
            }
        }
        closeStream();
        playing_.store(false, std::memory_order_release);
        hasMedia_.store(false, std::memory_order_release);
    }

    std::mutex stateMutex_;
    std::mutex dspMutex_;
    std::mutex initMutex_;
    std::condition_variable initCv_;
    std::string url_;
    std::string mime_;
    AMediaExtractor* extractor_ = nullptr;
    AMediaCodec* codec_ = nullptr;
    AMediaFormat* trackFormat_ = nullptr;
    AMediaDataSource* dataSource_ = nullptr;
    bool dataSourceClosed_ = false;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::unique_ptr<AudioDecoder> fallback_;
    std::thread worker_;
    FloatRing ring_;
    void* dsp_ = nullptr;

    int sourceRate_ = 48000;
    int trackSourceRate_ = 48000;
    std::atomic<int> outputRate_{kOutputRate};
    int sourceChannels_ = 2;
    int pcmEncoding_ = kPcm16Encoding;
    bool dspDirty_ = false;
    bool havePrev_ = false;
    float prevL_ = 0.0f, prevR_ = 0.0f;
    double resamplePos_ = 0.0;

    std::atomic<bool> stopRequested_{false};
    std::atomic<bool> paused_{false};
    std::atomic<bool> seekRequested_{false};
    std::atomic<bool> playing_{false};
    std::atomic<bool> hasMedia_{false};
    std::atomic<bool> error_{false};
    std::atomic<bool> initDone_{false};
    std::atomic<uint64_t> underruns_{0};
    std::atomic<int64_t> seekMs_{0};
    std::atomic<int64_t> positionMs_{0};
    std::atomic<int64_t> playbackBaseMs_{0};
    std::atomic<uint64_t> renderedFrames_{0};
    std::atomic<bool> positionPrimed_{false};
    std::atomic<bool> usingFallback_{false};
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
extern "C" JNIEXPORT void JNICALL Java_com_jay_glossy_ui_player_NativePlayer_nSetDsp(JNIEnv* env, jobject, jlong h, jboolean enabled, jintArray bandsMb, jboolean bass, jint bassStrength, jboolean virtualizer, jint virtualizerStrength, jboolean spatial, jint spatialStrength, jboolean crossfeed, jint crossfeedStrength, jboolean reverb, jint reverbMix, jboolean gain, jint gainMb, jboolean headroom, jboolean bypass) {
    auto* p=reinterpret_cast<Player*>(h); if (!p) return;
    std::vector<int> bands;
    if (bandsMb) { const jsize n=env->GetArrayLength(bandsMb); bands.resize(static_cast<size_t>(n)); if (n>0) env->GetIntArrayRegion(bandsMb,0,n,bands.data()); }
    p->setDsp(bands.data(), static_cast<int>(bands.size()), enabled==JNI_TRUE, bass==JNI_TRUE, bassStrength, virtualizer==JNI_TRUE, virtualizerStrength, spatial==JNI_TRUE, spatialStrength, crossfeed==JNI_TRUE, crossfeedStrength, reverb==JNI_TRUE, reverbMix, gain==JNI_TRUE, gainMb, headroom==JNI_TRUE, bypass==JNI_TRUE);
}
extern "C" JNIEXPORT void JNICALL Java_com_jay_glossy_ui_player_NativePlayer_nRelease(JNIEnv*, jobject, jlong h) { delete reinterpret_cast<Player*>(h); }
