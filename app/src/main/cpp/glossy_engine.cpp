#include <jni.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>
#include <mutex>

namespace {

constexpr float kPi = 3.14159265358979323846f;
constexpr int kMaxBands = 31;
constexpr std::array<float, kMaxBands> kBandHz = {
    20.0f, 25.0f, 31.0f, 40.0f, 50.0f, 63.0f, 80.0f, 100.0f, 125.0f, 160.0f,
    200.0f, 250.0f, 315.0f, 400.0f, 500.0f, 630.0f, 800.0f, 1000.0f,
    1250.0f, 1600.0f, 2000.0f, 2500.0f, 3150.0f, 4000.0f, 5000.0f, 6300.0f,
    8000.0f, 10000.0f, 12500.0f, 16000.0f, 20000.0f,
};

struct Biquad {
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f;
    float a1 = 0.0f, a2 = 0.0f;
    float x1L = 0.0f, x2L = 0.0f, y1L = 0.0f, y2L = 0.0f;
    float x1R = 0.0f, x2R = 0.0f, y1R = 0.0f, y2R = 0.0f;

    void reset() {
        x1L = x2L = y1L = y2L = 0.0f;
        x1R = x2R = y1R = y2R = 0.0f;
    }

    float processL(float x) {
        const float y = b0 * x + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L;
        x2L = x1L; x1L = x; y2L = y1L; y1L = y;
        return y;
    }

    float processR(float x) {
        const float y = b0 * x + b1 * x1R + b2 * x2R - a1 * y1R - a2 * y2R;
        x2R = x1R; x1R = x; y2R = y1R; y1R = y;
        return y;
    }
};

static void makePeaking(Biquad& q, float fs, float freq, float gainDb, float Q = 1.15f) {
    if (freq >= fs * 0.49f || std::abs(gainDb) < 0.01f) {
        q = Biquad{};
        return;
    }
    const float A = std::pow(10.0f, gainDb / 40.0f);
    const float w = 2.0f * kPi * freq / fs;
    const float c = std::cos(w);
    const float s = std::sin(w);
    const float alpha = s / (2.0f * Q);
    const float a0 = 1.0f + alpha / A;
    q.b0 = (1.0f + alpha * A) / a0;
    q.b1 = (-2.0f * c) / a0;
    q.b2 = (1.0f - alpha * A) / a0;
    q.a1 = (-2.0f * c) / a0;
    q.a2 = (1.0f - alpha / A) / a0;
}

static void makeLowShelf(Biquad& q, float fs, float freq, float gainDb) {
    if (freq >= fs * 0.49f || std::abs(gainDb) < 0.01f) {
        q = Biquad{};
        return;
    }
    const float A = std::pow(10.0f, gainDb / 40.0f);
    const float w = 2.0f * kPi * freq / fs;
    const float c = std::cos(w);
    const float s = std::sin(w);
    const float alpha = s * 0.5f;
    const float beta = 2.0f * std::sqrt(A) * alpha;
    const float a0 = (A + 1.0f) + (A - 1.0f) * c + beta;
    q.b0 = A * ((A + 1.0f) - (A - 1.0f) * c + beta) / a0;
    q.b1 = 2.0f * A * ((A - 1.0f) - (A + 1.0f) * c) / a0;
    q.b2 = A * ((A + 1.0f) - (A - 1.0f) * c - beta) / a0;
    q.a1 = -2.0f * ((A - 1.0f) + (A + 1.0f) * c) / a0;
    q.a2 = ((A + 1.0f) + (A - 1.0f) * c - beta) / a0;
}

struct Reverb {
    static constexpr int kLines = 4;
    std::array<std::vector<float>, kLines> buffers;
    std::array<size_t, kLines> pos{};
    float mix = 0.0f;

    void configure(int fs) {
        static constexpr float times[kLines] = {0.0297f, 0.0371f, 0.0411f, 0.0437f};
        for (int i = 0; i < kLines; ++i) {
            const size_t n = std::max<size_t>(1, static_cast<size_t>(fs * times[i]));
            buffers[i].assign(n, 0.0f);
            pos[i] = 0;
        }
    }

    void reset() {
        for (auto& b : buffers) std::fill(b.begin(), b.end(), 0.0f);
        pos.fill(0);
    }

    float process(float x, float feedback) {
        if (buffers[0].empty()) return x;
        float wet = 0.0f;
        for (int i = 0; i < kLines; ++i) {
            auto& b = buffers[i];
            float y = b[pos[i]];
            b[pos[i]] = x + y * feedback;
            pos[i] = (pos[i] + 1) % b.size();
            wet += y;
        }
        return wet / static_cast<float>(kLines);
    }
};



struct Spatializer {
    static constexpr int kDelaySize = 4096;
    std::vector<float> left{kDelaySize, 0.0f}, right{kDelaySize, 0.0f};
    int pos = 0;
    std::atomic<bool> enabled{false};
    float strength = 0.0f;
    float azimuth = 0.0f;
    float elevation = 0.0f;
    void configure() { left.assign(kDelaySize, 0.0f); right.assign(kDelaySize, 0.0f); pos = 0; }
    void reset() { std::fill(left.begin(), left.end(), 0.0f); std::fill(right.begin(), right.end(), 0.0f); pos = 0; }
    void set(bool en, float s, float a, float e) { enabled.store(en); strength = std::clamp(s,0.0f,1.0f); azimuth = std::clamp(a,-1.5707963f,1.5707963f); elevation = std::clamp(e,-1.5707963f,1.5707963f); }
    float delayed(const std::vector<float>& b, float d) const {
        float r = static_cast<float>(pos) - d; while (r < 0) r += kDelaySize; while (r >= kDelaySize) r -= kDelaySize;
        int i = static_cast<int>(r), j=(i+1)%kDelaySize; float f=r-i; return b[i]*(1-f)+b[j]*f;
    }
    void process(float& l, float& r, int fs) {
        if (!enabled.load() || strength <= 0.001f) return;
        left[pos]=l; right[pos]=r;
        const float a=std::abs(azimuth), itd=(0.0875f/343.0f)*(std::sin(a)+a)*fs*strength;
        const float dl=azimuth>0?itd:0, dr=azimuth<0?itd:0;
        const float shadow=1.0f-0.6f*std::sin(a)*strength;
        const float elev=0.72f+0.28f*std::cos(elevation);
        l=delayed(left,dl)*((azimuth>0?shadow:1.0f)*elev);
        r=delayed(right,dr)*((azimuth<0?shadow:1.0f)*elev);
        const float mid=(l+r)*0.5f, side=(l-r)*0.5f;
        const float width=1.0f+0.45f*strength;
        l=mid+side*width; r=mid-side*width;
        pos=(pos+1)%kDelaySize;
    }
};

struct Crossfeed {
    static constexpr int kDelaySize=512;
    std::vector<float> l{kDelaySize,0.0f}, r{kDelaySize,0.0f};
    int pos=0; float lpL=0,lpR=0;
    std::atomic<bool> enabled{false}; float strength=0.0f;
    void set(bool en,float s){enabled.store(en);strength=std::clamp(s,0.0f,1.0f);}
    void reset(){std::fill(l.begin(),l.end(),0);std::fill(r.begin(),r.end(),0);pos=0;lpL=lpR=0;}
    void process(float& leftIn,float& rightIn,int fs){
        if(!enabled.load()||strength<=0.001f)return;
        l[pos]=leftIn;r[pos]=rightIn; const float d=0.0003f*fs; float ri=pos-d; while(ri<0)ri+=kDelaySize; int i=(int)ri,j=(i+1)%kDelaySize;float f=ri-i;
        float dl=l[i]*(1-f)+l[j]*f, dr=r[i]*(1-f)+r[j]*f; const float a=std::exp(-2.0f*3.14159265f*700.0f/fs);
        lpL=(1-a)*dl+a*lpL;lpR=(1-a)*dr+a*lpR; float s=strength; leftIn=leftIn*(1-0.5f*s)+lpR*s; rightIn=rightIn*(1-0.5f*s)+lpL*s; pos=(pos+1)%kDelaySize;
    }
};

struct ReverbFx {
    std::array<std::vector<float>,4> buf; std::array<size_t,4> pos{}; std::atomic<bool> enabled{false}; float mix=0, feedback=0.55f;
    void configure(int fs){const float t[4]={0.0297f,0.0371f,0.0411f,0.0437f};for(int i=0;i<4;i++){buf[i].assign(std::max<size_t>(1,(size_t)(fs*t[i])),0);pos[i]=0;}}
    void reset(){for(auto&b:buf)std::fill(b.begin(),b.end(),0);pos.fill(0);}
    void set(bool en,float m){enabled.store(en);mix=std::clamp(m,0.0f,0.35f);}
    float processOne(float x, int channel){if(!enabled.load()||mix<=0.001f||buf[0].empty())return x;float wet=0;for(int i=0;i<4;i++){auto&b=buf[i];size_t p=(pos[i]+static_cast<size_t>(channel*17))%b.size();float y=b[p];b[p]=x+y*feedback;wet+=y;}return x*(1-mix)+wet*mix/4.0f;}
    void processStereo(float& l,float& r){if(!enabled.load()||mix<=0.001f||buf[0].empty())return;for(int i=0;i<4;i++){auto&b=buf[i];float yl=b[pos[i]], yr=b[(pos[i]+17)%b.size()];b[pos[i]]=l+yl*feedback;b[(pos[i]+17)%b.size()]=r+yr*feedback;l=l*(1-mix)+yl*mix/4.0f;r=r*(1-mix)+yr*mix/4.0f;pos[i]=(pos[i]+1)%b.size();}}
};

struct SpectrumAnalyzer {
    static constexpr int kFftSize = 512;
    static constexpr int kBins = 32;
    std::array<float, kFftSize> ring{};
    int write = 0;
    int filled = 0;
    std::array<float, kFftSize> real{};
    std::array<float, kFftSize> imag{};
    std::array<float, kBins> windowed{};
    std::array<std::atomic<float>, kBins> levels{};
    std::atomic<float> rms{0.0f};
    int sampleRate = 48000;
    int hop = 0;

    SpectrumAnalyzer() {
        for (auto& v : levels) v.store(0.0f, std::memory_order_relaxed);
    }

    void configure(int fs) {
        sampleRate = std::max(8000, fs);
        reset();
    }

    void reset() {
        ring.fill(0.0f);
        real.fill(0.0f);
        imag.fill(0.0f);
        write = filled = hop = 0;
        rms.store(0.0f, std::memory_order_relaxed);
        for (auto& v : levels) v.store(0.0f, std::memory_order_relaxed);
    }

    static void fft(std::array<float, kFftSize>& re, std::array<float, kFftSize>& im) {
        for (int i = 1, j = 0; i < kFftSize; ++i) {
            int bit = kFftSize >> 1;
            for (; j & bit; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                std::swap(re[i], re[j]);
                std::swap(im[i], im[j]);
            }
        }
        for (int len = 2; len <= kFftSize; len <<= 1) {
            const float angle = -2.0f * kPi / static_cast<float>(len);
            const float wLenR = std::cos(angle);
            const float wLenI = std::sin(angle);
            for (int i = 0; i < kFftSize; i += len) {
                float wr = 1.0f, wi = 0.0f;
                const int half = len >> 1;
                for (int j = 0; j < half; ++j) {
                    const int u = i + j;
                    const int v = u + half;
                    const float vr = re[v] * wr - im[v] * wi;
                    const float vi = re[v] * wi + im[v] * wr;
                    const float ur = re[u], ui = im[u];
                    re[u] = ur + vr; im[u] = ui + vi;
                    re[v] = ur - vr; im[v] = ui - vi;
                    const float nwr = wr * wLenR - wi * wLenI;
                    wi = wr * wLenI + wi * wLenR;
                    wr = nwr;
                }
            }
        }
    }

    void publish() {
        float power = 0.0f;
        for (int i = 0; i < kFftSize; ++i) {
            const int idx = (write + i) % kFftSize;
            const float w = 0.5f - 0.5f * std::cos(2.0f * kPi * i / (kFftSize - 1));
            real[i] = ring[idx] * w;
            imag[i] = 0.0f;
            power += real[i] * real[i];
        }
        fft(real, imag);
        rms.store(std::sqrt(power / static_cast<float>(kFftSize)), std::memory_order_relaxed);

        const float minHz = 35.0f;
        const float maxHz = std::min(18000.0f, sampleRate * 0.45f);
        for (int b = 0; b < kBins; ++b) {
            const float lo = minHz * std::pow(maxHz / minHz, static_cast<float>(b) / kBins);
            const float hi = minHz * std::pow(maxHz / minHz, static_cast<float>(b + 1) / kBins);
            const int loBin = std::max(1, static_cast<int>(std::floor(lo * kFftSize / sampleRate)));
            const int hiBin = std::min(kFftSize / 2, static_cast<int>(std::ceil(hi * kFftSize / sampleRate)));
            float peak = 0.0f;
            for (int k = loBin; k <= hiBin; ++k) {
                const float mag = std::sqrt(real[k] * real[k] + imag[k] * imag[k]) / (kFftSize * 0.5f);
                peak = std::max(peak, mag);
            }
            // Smooth on the UI-facing side to avoid jitter without locking the audio thread.
            const float previous = levels[b].load(std::memory_order_relaxed);
            const float smoothed = std::max(peak, previous * 0.78f);
            levels[b].store(std::clamp(smoothed, 0.0f, 1.0f), std::memory_order_relaxed);
        }
    }

    void push(float sample) {
        ring[write] = sample;
        write = (write + 1) % kFftSize;
        filled = std::min(kFftSize, filled + 1);
        if (++hop >= 128 && filled == kFftSize) {
            hop = 0;
            publish();
        }
    }

    void read(float* out, int n) const {
        if (!out || n <= 0) return;
        const int count = std::min(n, kBins);
        for (int i = 0; i < count; ++i) out[i] = levels[i].load(std::memory_order_relaxed);
        for (int i = count; i < n; ++i) out[i] = 0.0f;
    }
};

static SpectrumAnalyzer gSpectrum;

struct Engine {
    int sampleRate = 0;
    int channels = 0;
    int encoding = 0;
    bool enabled = false;
    bool bassEnabled = false;
    bool virtualizerEnabled = false;
    bool outputGainEnabled = false;
    bool autoHeadroom = false;
    bool bypass = false;
    int bassStrength = 0;
    int virtualizerStrength = 0;
    float outputGainDb = 0.0f;
    std::array<float, kMaxBands> bandGainDb{};
    std::array<Biquad, kMaxBands> eq{};
    Biquad bass;
    Biquad bassHarmonic;
    Biquad clarity;
    float limiterGain = 1.0f;
    float compressorEnvelope = 0.0f;
    float loudnessGain = 1.0f;
    Reverb reverb;
    Spatializer spatializer;
    Crossfeed crossfeed;
    ReverbFx spatialReverb;
    bool spatialEnabled = false;
    int spatialStrength = 0;
    bool crossfeedEnabled = false;
    int crossfeedStrength = 0;
    bool reverbEnabled = false;
    int reverbMix = 0;
    bool clarityEnabled = false;
    int clarityStrength = 0;
    bool compressorEnabled = true;
    int compressorStrength = 350;
    bool limiterEnabled = true;
    int limiterStrength = 650;

    void configure(int fs, int ch, int enc) {
        sampleRate = fs;
        gSpectrum.configure(fs);
        channels = ch;
        encoding = enc;
        rebuild();
    }

    void setDsp(bool en, const std::vector<int>& bands, bool bassEn, int bassS,
               bool virtEn, int virtS, bool gainEn, int gainMb, bool headroom) {
        enabled = en;
        bassEnabled = bassEn;
        bassStrength = std::clamp(bassS, 0, 1000);
        virtualizerEnabled = virtEn;
        virtualizerStrength = std::clamp(virtS, 0, 1000);
        outputGainEnabled = gainEn;
        outputGainDb = static_cast<float>(gainMb) / 100.0f;
        autoHeadroom = headroom;
        bandGainDb.fill(0.0f);

        if (!bands.empty()) {
            for (int i = 0; i < kMaxBands; ++i) {
                const float x = static_cast<float>(i) * (bands.size() - 1) / static_cast<float>(kMaxBands - 1);
                const int lo = std::clamp(static_cast<int>(std::floor(x)), 0, static_cast<int>(bands.size()) - 1);
                const int hi = std::clamp(lo + 1, 0, static_cast<int>(bands.size()) - 1);
                const float t = x - static_cast<float>(lo);
                bandGainDb[i] = (static_cast<float>(bands[lo]) * (1.0f - t) + static_cast<float>(bands[hi]) * t) / 100.0f;
            }
        }
        rebuild();
    }

    void setEffects(bool spatialEn, int spatialS, bool crossEn, int crossS, bool reverbEn, int reverbS, bool clarityEn, int clarityS, bool compressorEn, int compressorS, bool limiterEn, int limiterS) {
        spatialEnabled=spatialEn; spatialStrength=std::clamp(spatialS,0,1000);
        crossfeedEnabled=crossEn; crossfeedStrength=std::clamp(crossS,0,1000);
        reverbEnabled=reverbEn; reverbMix=std::clamp(reverbS,0,350);
        clarityEnabled=clarityEn; clarityStrength=std::clamp(clarityS,0,1000);
        compressorEnabled=compressorEn; compressorStrength=std::clamp(compressorS,0,1000);
        limiterEnabled=limiterEn; limiterStrength=std::clamp(limiterS,0,1000);
        spatializer.set(spatialEn, spatialStrength/1000.0f, 0.0f, 0.0f);
        crossfeed.set(crossEn, crossfeedStrength/1000.0f);
        spatialReverb.set(reverbEn, reverbMix/1000.0f);
        const float presence = clarityEnabled ? (4.0f * static_cast<float>(clarityStrength) / 1000.0f) : 0.0f;
        if (sampleRate > 0) makePeaking(clarity, static_cast<float>(sampleRate), 3200.0f, presence, 0.9f);
    }

    void rebuild() {
        if (sampleRate <= 0) return;
        for (int i = 0; i < kMaxBands; ++i) {
            makePeaking(eq[i], static_cast<float>(sampleRate), kBandHz[i], enabled ? bandGainDb[i] : 0.0f);
        }
        const float bassDb = enabled && bassEnabled ? 10.0f * static_cast<float>(bassStrength) / 1000.0f : 0.0f;
        makeLowShelf(bass, static_cast<float>(sampleRate), 105.0f, bassDb);
        makePeaking(bassHarmonic, static_cast<float>(sampleRate), 95.0f,
                    enabled && bassEnabled ? 1.5f * static_cast<float>(bassStrength) / 1000.0f : 0.0f, 0.55f);
        const float presence = enabled && clarityEnabled
                ? (4.0f * static_cast<float>(clarityStrength) / 1000.0f)
                : 0.0f;
        makePeaking(clarity, static_cast<float>(sampleRate), 3200.0f, presence, 0.9f);
        reverb.configure(sampleRate);
        spatializer.configure();
        spatialReverb.configure(sampleRate);
        resetState();
    }

    void resetState() {
        for (auto& f : eq) f.reset();
        bass.reset();
        bassHarmonic.reset();
        clarity.reset();
        reverb.reset();
        spatializer.reset();
        crossfeed.reset();
        spatialReverb.reset();
        limiterGain = 1.0f;
        compressorEnvelope = 1.0f;
        loudnessGain = 1.0f;
    }

    float processSample(float x, int ch, float& other) {
        if (!enabled || bypass) return x;
        if (ch == 0) {
            x = bass.processL(x);
            for (auto& f : eq) x = f.processL(x);
            x = clarity.processL(x);
            if (bassEnabled && bassStrength > 0) {
                const float low = bassHarmonic.processL(x);
                const float drive = 0.025f * static_cast<float>(bassStrength) / 1000.0f;
                x += std::tanh(low * (1.0f + 3.0f * drive)) * drive;
            }
        } else {
            x = bass.processR(x);
            for (auto& f : eq) x = f.processR(x);
            x = clarity.processR(x);
            if (bassEnabled && bassStrength > 0) {
                const float low = bassHarmonic.processR(x);
                const float drive = 0.025f * static_cast<float>(bassStrength) / 1000.0f;
                x += std::tanh(low * (1.0f + 3.0f * drive)) * drive;
            }
        }
        const float gainDb = outputGainEnabled ? outputGainDb : 0.0f;
        x *= std::pow(10.0f, gainDb / 20.0f);
        return x;
    }

    void processStereo(float& l, float& r) {
        if (!enabled || bypass) return;
        l = processSample(l, 0, r);
        r = processSample(r, 1, l);

        if (virtualizerEnabled) {
            const float width = 1.0f + 1.25f * static_cast<float>(virtualizerStrength) / 1000.0f;
            const float mid = 0.5f * (l + r);
            const float side = 0.5f * (l - r) * width;
            l = mid + side; r = mid - side;
        }
        if (crossfeedEnabled) crossfeed.process(l, r, sampleRate);
        if (spatialEnabled) spatializer.process(l, r, sampleRate);
        if (reverbEnabled) spatialReverb.processStereo(l, r);

        // Optional dynamics stage followed by a safety ceiling. Compression is
        // intentionally conservative so EQ/spatial boosts stay punchy instead of
        // becoming flat.
        const float peak = std::max(std::abs(l), std::abs(r));
        if (compressorEnabled && compressorStrength > 0 && peak > 0.00001f) {
            const float strength = static_cast<float>(compressorStrength) / 1000.0f;
            const float thresholdDb = -24.0f + 18.0f * strength;
            const float threshold = std::pow(10.0f, thresholdDb / 20.0f);
            const float ratio = 1.0f + 3.0f * strength;
            const float inputDb = 20.0f * std::log10(std::max(peak, 1.0e-6f));
            float gainDb = 0.0f;
            if (inputDb > thresholdDb) {
                const float compressedDb = thresholdDb + (inputDb - thresholdDb) / ratio;
                gainDb = compressedDb - inputDb;
            }
            const float desired = std::pow(10.0f, gainDb / 20.0f);
            const float attackCoeff = std::exp(-1.0f / (static_cast<float>(sampleRate) * 0.005f));
            const float releaseCoeff = std::exp(-1.0f / (static_cast<float>(sampleRate) * 0.100f));
            const float coeff = desired < compressorEnvelope ? attackCoeff : releaseCoeff;
            compressorEnvelope = coeff * compressorEnvelope + (1.0f - coeff) * desired;
            l *= compressorEnvelope;
            r *= compressorEnvelope;
        } else {
            compressorEnvelope = 1.0f;
        }

        if (autoHeadroom) {
            const float rms = std::sqrt(0.5f * (l * l + r * r));
            const float targetQuiet = 0.0631f;
            const float desired = rms > 0.0001f && rms < targetQuiet
                    ? std::min(1.3335f, targetQuiet / rms)
                    : 1.0f;
            const float coeff = desired > loudnessGain
                    ? std::exp(-1.0f / (static_cast<float>(sampleRate) * 0.035f))
                    : std::exp(-1.0f / (static_cast<float>(sampleRate) * 0.250f));
            loudnessGain = coeff * loudnessGain + (1.0f - coeff) * desired;
            l *= loudnessGain;
            r *= loudnessGain;
        } else {
            loudnessGain = 1.0f;
        }

        const float peakAfterLoudness = std::max(std::abs(l), std::abs(r));
        const float limiterStrength01 = static_cast<float>(limiterStrength) / 1000.0f;
        const float threshold = limiterEnabled
                ? (0.995f - 0.095f * limiterStrength01)
                : 0.999f;
        float target = 1.0f;
        if (limiterEnabled && peakAfterLoudness > threshold) target = threshold / peakAfterLoudness;
        const float attack = std::exp(-1.0f / (static_cast<float>(sampleRate) * 0.002f));
        const float release = std::exp(-1.0f / (static_cast<float>(sampleRate) * 0.080f));
        if (target < limiterGain) limiterGain = attack * limiterGain + (1.0f - attack) * target;
        else limiterGain = release * limiterGain + (1.0f - release) * target;
        l *= limiterGain;
        r *= limiterGain;
        l = std::clamp(l, -0.999f, 0.999f);
        r = std::clamp(r, -0.999f, 0.999f);
    }
};

inline int bytesPerSample(int encoding) {
    switch (encoding) {
        case 2: return 2;
        case 4: return 4;
        case 0x10000000: return 3;
        case 0x20000000: return 4;
        default: return 0;
    }
}

inline Engine* fromHandle(jlong handle) {
    return reinterpret_cast<Engine*>(handle);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jay_glossy_ui_player_NativeEngine_nCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new Engine());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jay_glossy_ui_player_NativeEngine_nConfigure(JNIEnv*, jobject, jlong handle,
                                                       jint sampleRate, jint channels, jint encoding) {
    auto* e = fromHandle(handle);
    if (!e || sampleRate <= 0 || channels <= 0 || channels > 2 || bytesPerSample(encoding) == 0) return JNI_FALSE;
    e->configure(sampleRate, channels, encoding);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jay_glossy_ui_player_NativeEngine_nSetDsp(JNIEnv* env, jobject, jlong handle,
                                                    jboolean enabled, jintArray bandsMb,
                                                    jboolean bassEnabled, jint bassStrength,
                                                    jboolean virtualizerEnabled, jint virtualizerStrength,
                                                    jboolean spatialEnabled, jint spatialStrength,
                                                    jboolean crossfeedEnabled, jint crossfeedStrength,
                                                    jboolean reverbEnabled, jint reverbMix,
                                                    jboolean clarityEnabled, jint clarityStrength,
                                                    jboolean compressorEnabled, jint compressorStrength,
                                                    jboolean limiterEnabled, jint limiterStrength,
                                                    jboolean outputGainEnabled, jint outputGainMb,
                                                    jboolean autoHeadroom, jboolean bypass) {
    auto* e = fromHandle(handle);
    if (!e) return;
    std::vector<int> bands;
    if (bandsMb) {
        const jsize n = env->GetArrayLength(bandsMb);
        bands.resize(static_cast<size_t>(n));
        if (n > 0) env->GetIntArrayRegion(bandsMb, 0, n, bands.data());
    }
    e->setDsp(enabled == JNI_TRUE, bands, bassEnabled == JNI_TRUE, bassStrength,
              virtualizerEnabled == JNI_TRUE, virtualizerStrength,
              outputGainEnabled == JNI_TRUE, outputGainMb, autoHeadroom == JNI_TRUE);
    e->bypass = bypass == JNI_TRUE;
    e->setEffects(spatialEnabled == JNI_TRUE, spatialStrength, crossfeedEnabled == JNI_TRUE, crossfeedStrength, reverbEnabled == JNI_TRUE, reverbMix,
                  clarityEnabled == JNI_TRUE, clarityStrength, compressorEnabled == JNI_TRUE, compressorStrength,
                  limiterEnabled == JNI_TRUE, limiterStrength);
}


static int processEngine(Engine* e, const void* input, void* output, int bytes) {
    auto* in = static_cast<const uint8_t*>(input);
    auto* out = static_cast<uint8_t*>(output);
    if (!e || !in || !out || bytes <= 0) return 0;
    // Feed the analyzer from the decoded PCM before DSP so the visualization
    // represents the source signal rather than post-limiter output.
    if (e->encoding == 2) {
        const int samples = bytes / 2;
        const auto* src = reinterpret_cast<const int16_t*>(in);
        if (e->channels == 2) {
            for (int i = 0; i + 1 < samples; i += 2) {
                gSpectrum.push(0.5f * (static_cast<float>(src[i]) + static_cast<float>(src[i + 1])) / 32768.0f);
            }
        } else {
            for (int i = 0; i < samples; ++i) gSpectrum.push(static_cast<float>(src[i]) / 32768.0f);
        }
    } else if (e->encoding == 4) {
        const int samples = bytes / 4;
        const auto* src = reinterpret_cast<const float*>(in);
        if (e->channels == 2) {
            for (int i = 0; i + 1 < samples; i += 2) gSpectrum.push(0.5f * (src[i] + src[i + 1]));
        } else {
            for (int i = 0; i < samples; ++i) gSpectrum.push(src[i]);
        }
    }

    if (!e->enabled || e->bypass) {
        std::memcpy(out, in, static_cast<size_t>(bytes));
        return bytes;
    }

    if (e->encoding == 2) {
        const int samples = bytes / 2;
        const auto* src = reinterpret_cast<const int16_t*>(in);
        auto* dst = reinterpret_cast<int16_t*>(out);
        if (e->channels == 2) {
            for (int i = 0; i + 1 < samples; i += 2) {
                float l = static_cast<float>(src[i]) / 32768.0f;
                float r = static_cast<float>(src[i + 1]) / 32768.0f;
                e->processStereo(l, r);
                dst[i] = static_cast<int16_t>(std::lrintf(l * 32767.0f));
                dst[i + 1] = static_cast<int16_t>(std::lrintf(r * 32767.0f));
            }
        } else {
            for (int i = 0; i < samples; ++i) {
                float l = static_cast<float>(src[i]) / 32768.0f;
                float r = l;
                e->processStereo(l, r);
                dst[i] = static_cast<int16_t>(std::lrintf(l * 32767.0f));
            }
        }
        return samples * 2;
    }

    if (e->encoding == 4) {
        const int samples = bytes / 4;
        const auto* src = reinterpret_cast<const float*>(in);
        auto* dst = reinterpret_cast<float*>(out);
        if (e->channels == 2) {
            for (int i = 0; i + 1 < samples; i += 2) {
                float l = src[i], r = src[i + 1];
                e->processStereo(l, r);
                dst[i] = l;
                dst[i + 1] = r;
            }
        } else {
            for (int i = 0; i < samples; ++i) {
                float l = src[i], r = l;
                e->processStereo(l, r);
                dst[i] = l;
            }
        }
        return samples * 4;
    }

    std::memcpy(out, in, static_cast<size_t>(bytes));
    return bytes;

}

extern "C" JNIEXPORT jint JNICALL
Java_com_jay_glossy_ui_player_NativeEngine_nProcess(JNIEnv* env, jobject, jlong handle,
                                                     jobject input, jobject output, jint bytes) {
    auto* e = fromHandle(handle);
    if (!e || !input || !output || bytes <= 0) return 0;
    auto* in = env->GetDirectBufferAddress(input);
    auto* out = env->GetDirectBufferAddress(output);
    const jlong capacity = env->GetDirectBufferCapacity(output);
    if (!in || !out || capacity < bytes) return 0;
    return processEngine(e, in, out, bytes);
}


extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_jay_glossy_ui_player_NativeEngine_nGetSpectrum(JNIEnv* env, jobject) {
    jfloatArray result = env->NewFloatArray(SpectrumAnalyzer::kBins);
    if (!result) return nullptr;
    std::array<float, SpectrumAnalyzer::kBins> values{};
    gSpectrum.read(values.data(), SpectrumAnalyzer::kBins);
    env->SetFloatArrayRegion(result, 0, SpectrumAnalyzer::kBins, values.data());
    return result;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_jay_glossy_ui_player_NativeEngine_nGetRms(JNIEnv*, jobject) {
    return gSpectrum.rms.load(std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jay_glossy_ui_player_NativeEngine_nReset(JNIEnv*, jobject, jlong handle) {
    if (auto* e = fromHandle(handle)) e->resetState();
}

extern "C" JNIEXPORT void JNICALL
Java_com_jay_glossy_ui_player_NativeEngine_nRelease(JNIEnv*, jobject, jlong handle) {
    delete fromHandle(handle);
}



extern "C" void* glossy_dsp_create() {
    return reinterpret_cast<void*>(new Engine());
}

extern "C" bool glossy_dsp_configure(void* handle, int sampleRate, int channels, int encoding) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (!e || sampleRate <= 0 || channels <= 0 || channels > 2 || bytesPerSample(encoding) == 0) return false;
    e->configure(sampleRate, channels, encoding);
    return true;
}

extern "C" void glossy_dsp_set(void* handle, bool enabled, const int* bandsMb, int bandCount,
                                 bool bassEnabled, int bassStrength,
                                 bool virtualizerEnabled, int virtualizerStrength,
                                 bool outputGainEnabled, int outputGainMb,
                                 bool autoHeadroom, bool bypass,
                                 bool spatialEnabled, int spatialStrength,
                                 bool crossfeedEnabled, int crossfeedStrength,
                                 bool reverbEnabled, int reverbMix) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (!e) return;
    std::vector<int> bands;
    if (bandsMb && bandCount > 0) bands.assign(bandsMb, bandsMb + bandCount);
    e->setDsp(enabled, bands, bassEnabled, bassStrength, virtualizerEnabled,
              virtualizerStrength, outputGainEnabled, outputGainMb, autoHeadroom);
    e->bypass = bypass;
    e->setEffects(spatialEnabled, spatialStrength, crossfeedEnabled, crossfeedStrength, reverbEnabled, reverbMix);
}

extern "C" int glossy_dsp_process(void* handle, const void* input, void* output, int bytes) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (!e || !input || !output || bytes <= 0) return 0;
    return processEngine(e, input, output, bytes);
}

extern "C" void glossy_dsp_reset(void* handle) {
    if (auto* e = reinterpret_cast<Engine*>(handle)) e->resetState();
}

extern "C" void glossy_dsp_release(void* handle) {
    delete reinterpret_cast<Engine*>(handle);
}
