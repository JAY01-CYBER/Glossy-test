#include "AudioDecoder.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>

extern "C" {
#include <libavutil/dict.h>
}

static int decode_interrupt_cb(void *ctx) {
    auto* decoder = static_cast<AudioDecoder*>(ctx);
    return decoder->shouldInterrupt() ? 1 : 0;
}

AudioDecoder::AudioDecoder() {
    avformat_network_init();
    frame = av_frame_alloc();
    packet = av_packet_alloc();
}

AudioDecoder::~AudioDecoder() {
    stop();
    release();
    {
        std::lock_guard<std::mutex> lock(dspMutex);
        if (dsp_) { glossy_dsp_release(dsp_); dsp_ = nullptr; }
    }
    av_frame_free(&frame);
    av_packet_free(&packet);
    avformat_network_deinit();
}

bool AudioDecoder::openOutputStream() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::I16)
        ->setChannelCount(targetChannels)
        ->setSampleRate(targetSampleRate)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this);

    const auto result = builder.openStream(audioStream);
    if (result != oboe::Result::OK) {
        LOGE("FFmpeg Oboe open failed: %s", oboe::convertToText(result));
        return false;
    }
    if (audioStream->requestStart() != oboe::Result::OK) {
        audioStream->close();
        audioStream.reset();
        return false;
    }
    return true;
}

bool AudioDecoder::openUrl(const std::string& url, int64_t startPositionMs) {
    stop();
    if (url.empty()) return false;

    error_.store(false);
    isDecoding.store(true);
    isPaused.store(false);
    isPlaying_.store(false);
    currentPositionMs.store(std::max<int64_t>(0, startPositionMs));

    formatCtx = avformat_alloc_context();
    if (!formatCtx) return false;
    formatCtx->interrupt_callback.callback = decode_interrupt_cb;
    formatCtx->interrupt_callback.opaque = this;

    AVDictionary* options = nullptr;
    av_dict_set(&options, "user_agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36", 0);
    av_dict_set(&options, "protocol_whitelist", "file,http,https,tcp,tls,crypto", 0);
    av_dict_set(&options, "reconnect", "1", 0);
    av_dict_set(&options, "reconnect_streamed", "1", 0);
    av_dict_set(&options, "reconnect_delay_max", "3", 0);

    int result = avformat_open_input(&formatCtx, url.c_str(), nullptr, &options);
    av_dict_free(&options);
    if (result < 0) { error_.store(true); releaseCodec(); return false; }
    if (avformat_find_stream_info(formatCtx, nullptr) < 0) { error_.store(true); releaseCodec(); return false; }

    const AVCodec* codec = nullptr;
    audioStreamIndex = av_find_best_stream(formatCtx, AVMEDIA_TYPE_AUDIO, -1, -1, &codec, 0);
    if (audioStreamIndex < 0 || !codec) { error_.store(true); releaseCodec(); return false; }

    auto* stream = formatCtx->streams[audioStreamIndex];
    codecCtx = avcodec_alloc_context3(codec);
    if (!codecCtx || avcodec_parameters_to_context(codecCtx, stream->codecpar) < 0 || avcodec_open2(codecCtx, codec, nullptr) < 0) {
        error_.store(true); releaseCodec(); return false;
    }

    sourceSampleRate = std::clamp(codecCtx->sample_rate > 0 ? codecCtx->sample_rate : 48000, 8000, 192000);
    sourceChannels = std::clamp(codecCtx->ch_layout.nb_channels > 0 ? codecCtx->ch_layout.nb_channels : 2, 1, 2);

    int64_t durationUs = formatCtx->duration;
    if (durationUs > 0 && durationUs != AV_NOPTS_VALUE) durationMs.store(durationUs / 1000);

    swrCtx = swr_alloc();
    if (!swrCtx) { error_.store(true); releaseCodec(); return false; }
    AVChannelLayout inLayout = codecCtx->ch_layout;
    if (inLayout.nb_channels <= 0) av_channel_layout_default(&inLayout, sourceChannels);
    AVChannelLayout outLayout;
    av_channel_layout_default(&outLayout, targetChannels);
    av_opt_set_chlayout(swrCtx, "in_chlayout", &inLayout, 0);
    av_opt_set_int(swrCtx, "in_sample_rate", sourceSampleRate, 0);
    av_opt_set_sample_fmt(swrCtx, "in_sample_fmt", codecCtx->sample_fmt, 0);
    av_opt_set_chlayout(swrCtx, "out_chlayout", &outLayout, 0);
    av_opt_set_int(swrCtx, "out_sample_rate", targetSampleRate, 0);
    av_opt_set_sample_fmt(swrCtx, "out_sample_fmt", AV_SAMPLE_FMT_S16, 0);
    av_channel_layout_uninit(&outLayout);
    if (swr_init(swrCtx) < 0) { error_.store(true); releaseCodec(); return false; }

    {
        std::lock_guard<std::mutex> lock(bufferMutex);
        audioBuffer.clear();
    }
    {
        std::lock_guard<std::mutex> lock(dspMutex);
        if (!dsp_) dsp_ = glossy_dsp_create();
        if (!dsp_ || !glossy_dsp_configure(dsp_, targetSampleRate, targetChannels, 2)) {
            error_.store(true); releaseCodec(); return false;
        }
        dspDirty_ = false;
    }

    if (!openOutputStream()) { error_.store(true); releaseCodec(); return false; }

    if (startPositionMs > 0) {
        av_seek_frame(formatCtx, audioStreamIndex,
                      av_rescale_q(startPositionMs, AVRational{1,1000}, stream->time_base),
                      AVSEEK_FLAG_BACKWARD);
        currentPositionMs.store(startPositionMs);
    }

    decoderThread = std::thread(&AudioDecoder::decodeLoop, this);
    isPlaying_.store(true);
    return true;
}

void AudioDecoder::pause() {
    isPaused.store(true);
    if (audioStream) audioStream->requestPause();
}

void AudioDecoder::resume() {
    isPaused.store(false);
    if (audioStream) audioStream->requestStart();
}

bool AudioDecoder::seekTo(int64_t positionMs) {
    if (!formatCtx || !isDecoding.load()) return false;
    seekTargetMs.store(std::max<int64_t>(0, positionMs));
    seekRequested.store(true);
    return true;
}

void AudioDecoder::setVolume(float vol) { volume.store(std::clamp(vol, 0.0f, 1.0f)); }

void AudioDecoder::setDsp(const int* bands, int count, bool enabled, bool bass, int bassStrength,
                          bool virtualizer, int virtualizerStrength, bool spatial, int spatialStrength,
                          bool crossfeed, int crossfeedStrength, bool reverb, int reverbMix,
                          bool gain, int gainMb, bool headroom, bool bypass) {
    std::lock_guard<std::mutex> lock(dspMutex);
    if (!dsp_) dsp_ = glossy_dsp_create();
    if (dsp_) {
        glossy_dsp_set(dsp_, enabled, bands, count, bass, bassStrength, virtualizer,
                       virtualizerStrength, gain, gainMb, headroom, bypass, spatial, spatialStrength, crossfeed, crossfeedStrength, reverb, reverbMix);
        dspDirty_ = true;
    }
}

void AudioDecoder::stop() {
    isDecoding.store(false);
    isPlaying_.store(false);
    isPaused.store(false);
    if (audioStream) audioStream->requestStop();
    if (decoderThread.joinable()) decoderThread.join();
    if (audioStream) { audioStream->close(); audioStream.reset(); }
    {
        std::lock_guard<std::mutex> lock(bufferMutex);
        audioBuffer.clear();
    }
    releaseCodec();
}

void AudioDecoder::release() {
    stop();
    std::lock_guard<std::mutex> lock(dspMutex);
    if (dsp_) { glossy_dsp_release(dsp_); dsp_ = nullptr; }
}

void AudioDecoder::releaseCodec() {
    if (swrCtx) { swr_free(&swrCtx); swrCtx = nullptr; }
    if (codecCtx) { avcodec_free_context(&codecCtx); codecCtx = nullptr; }
    if (formatCtx) { avformat_close_input(&formatCtx); formatCtx = nullptr; }
    audioStreamIndex = -1;
}

void AudioDecoder::processFrame(int64_t ptsUs) {
    const int maxOut = swr_get_out_samples(swrCtx, frame->nb_samples);
    if (maxOut <= 0) return;
    std::vector<int16_t> pcm(static_cast<size_t>(maxOut) * targetChannels);
    uint8_t* out[] = { reinterpret_cast<uint8_t*>(pcm.data()) };
    const int outSamples = swr_convert(swrCtx, out, maxOut,
                                       const_cast<const uint8_t**>(frame->extended_data), frame->nb_samples);
    if (outSamples <= 0) return;
    pcm.resize(static_cast<size_t>(outSamples) * targetChannels);

    std::vector<float> input(pcm.size()), processed(pcm.size());
    for (size_t i = 0; i < pcm.size(); ++i) input[i] = static_cast<float>(pcm[i]) / 32768.0f;
    {
        std::lock_guard<std::mutex> lock(dspMutex);
        if (dsp_) {
            if (dspDirty_) { glossy_dsp_configure(dsp_, targetSampleRate, targetChannels, 2); dspDirty_ = false; }
            if (glossy_dsp_process(dsp_, input.data(), processed.data(), static_cast<int>(input.size() * sizeof(float))) <= 0) processed = input;
        } else processed = input;
    }
    for (size_t i = 0; i < pcm.size(); ++i) {
        const float v = std::clamp(processed[i], -1.0f, 1.0f);
        pcm[i] = static_cast<int16_t>(std::lrint(v * 32767.0f));
    }

    std::lock_guard<std::mutex> lock(bufferMutex);
    const size_t room = MAX_BUFFER_SIZE > audioBuffer.size() ? MAX_BUFFER_SIZE - audioBuffer.size() : 0;
    const size_t count = std::min(room, pcm.size());
    audioBuffer.insert(audioBuffer.end(), pcm.begin(), pcm.begin() + static_cast<std::ptrdiff_t>(count));
    if (ptsUs != AV_NOPTS_VALUE) currentPositionMs.store(std::max<int64_t>(0, ptsUs / 1000));
}

void AudioDecoder::decodeLoop() {
    while (isDecoding.load()) {
        if (seekRequested.exchange(false)) {
            const int64_t target = seekTargetMs.load();
            const auto* stream = formatCtx->streams[audioStreamIndex];
            const int64_t pts = av_rescale_q(target, AVRational{1,1000}, stream->time_base);
            if (av_seek_frame(formatCtx, audioStreamIndex, pts, AVSEEK_FLAG_BACKWARD) >= 0) {
                avcodec_flush_buffers(codecCtx);
                swr_convert(swrCtx, nullptr, 0, nullptr, 0);
                std::lock_guard<std::mutex> lock(bufferMutex);
                audioBuffer.clear();
                currentPositionMs.store(target);
            }
        }

        if (isPaused.load()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }
        bool bufferFull = false;
        {
            std::lock_guard<std::mutex> lock(bufferMutex);
            bufferFull = audioBuffer.size() >= MAX_BUFFER_SIZE;
        }
        if (bufferFull) {
            // Keep the decode thread ahead, but bounded.
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }

        const int ret = av_read_frame(formatCtx, packet);
        if (ret < 0) {
            // EOF: drain decoder once, then finish. Network/interruption is treated as EOS/error.
            avcodec_send_packet(codecCtx, nullptr);
            while (avcodec_receive_frame(codecCtx, frame) >= 0) processFrame(frame->best_effort_timestamp == AV_NOPTS_VALUE ? AV_NOPTS_VALUE : av_rescale_q(frame->best_effort_timestamp, formatCtx->streams[audioStreamIndex]->time_base, AVRational{1,1000000}));
            break;
        }
        if (packet->stream_index == audioStreamIndex) {
            if (avcodec_send_packet(codecCtx, packet) >= 0) {
                while (avcodec_receive_frame(codecCtx, frame) >= 0) {
                    int64_t ptsUs = frame->best_effort_timestamp == AV_NOPTS_VALUE
                        ? AV_NOPTS_VALUE
                        : av_rescale_q(frame->best_effort_timestamp, formatCtx->streams[audioStreamIndex]->time_base, AVRational{1,1000000});
                    processFrame(ptsUs);
                }
            }
        }
        av_packet_unref(packet);
    }
    isDecoding.store(false);
    isPlaying_.store(false);
}

oboe::DataCallbackResult AudioDecoder::onAudioReady(oboe::AudioStream*, void* audioData, int32_t numFrames) {
    auto* output = static_cast<int16_t*>(audioData);
    const size_t needed = static_cast<size_t>(std::max(0, numFrames)) * targetChannels;
    if (isPaused.load() || !isPlaying_.load()) {
        std::memset(output, 0, needed * sizeof(int16_t));
        return oboe::DataCallbackResult::Continue;
    }

    std::lock_guard<std::mutex> lock(bufferMutex);
    const size_t available = std::min(needed, audioBuffer.size());
    for (size_t i = 0; i < available; ++i) output[i] = audioBuffer.front(), audioBuffer.pop_front();
    for (size_t i = available; i < needed; ++i) output[i] = 0;

    const float vol = volume.load();
    if (vol != 1.0f) {
        for (size_t i = 0; i < needed; ++i) {
            output[i] = static_cast<int16_t>(std::clamp(static_cast<float>(output[i]) * vol, -32768.0f, 32767.0f));
        }
    }
    return oboe::DataCallbackResult::Continue;
}
