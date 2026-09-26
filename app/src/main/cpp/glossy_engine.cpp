#include <jni.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>
#include <oboe/Oboe.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr float kPi = 3.14159265358979323846f;
constexpr int kMaxBands = 31;
constexpr int kMaxChannels = 2;
constexpr std::array<float, kMaxBands> kBandHz = {
    20.f,25.f,31.f,40.f,50.f,63.f,80.f,100.f,125.f,160.f,200.f,250.f,315.f,400.f,500.f,630.f,
    800.f,1000.f,1250.f,1600.f,2000.f,2500.f,3150.f,4000.f,5000.f,6300.f,8000.f,10000.f,12500.f,16000.f,20000.f
};

struct Biquad {
    float b0=1.f,b1=0.f,b2=0.f,a1=0.f,a2=0.f;
    float x1L=0.f,x2L=0.f,y1L=0.f,y2L=0.f,x1R=0.f,x2R=0.f,y1R=0.f,y2R=0.f;
    void reset(){x1L=x2L=y1L=y2L=x1R=x2R=y1R=y2R=0.f;}
    float L(float x){float y=b0*x+b1*x1L+b2*x2L-a1*y1L-a2*y2L;x2L=x1L;x1L=x;y2L=y1L;y1L=y;return y;}
    float R(float x){float y=b0*x+b1*x1R+b2*x2R-a1*y1R-a2*y2R;x2R=x1R;x1R=x;y2R=y1R;y1R=y;return y;}
};

static void peaking(Biquad& q,float fs,float f,float db,float Q=1.15f){
    if(fs<=0.f||f>=fs*.49f||std::abs(db)<.001f){q=Biquad{};return;}
    const float A=std::pow(10.f,db/40.f),w=2.f*kPi*f/fs,c=std::cos(w),s=std::sin(w),alpha=s/(2.f*Q),a0=1.f+alpha/A;
    q.b0=(1.f+alpha*A)/a0;q.b1=-2.f*c/a0;q.b2=(1.f-alpha*A)/a0;q.a1=-2.f*c/a0;q.a2=(1.f-alpha/A)/a0;
}

static void lowShelf(Biquad& q,float fs,float f,float db){
    if(fs<=0.f||f>=fs*.49f||std::abs(db)<.001f){q=Biquad{};return;}
    const float A=std::pow(10.f,db/40.f),w=2.f*kPi*f/fs,c=std::cos(w),s=std::sin(w),alpha=s*.5f,beta=2.f*std::sqrt(A)*alpha,a0=(A+1.f)+(A-1.f)*c+beta;
    q.b0=A*((A+1.f)-(A-1.f)*c+beta)/a0;q.b1=2.f*A*((A-1.f)-(A+1.f)*c)/a0;q.b2=A*((A+1.f)-(A-1.f)*c-beta)/a0;
    q.a1=-2.f*((A-1.f)+(A+1.f)*c)/a0;q.a2=((A+1.f)+(A-1.f)*c-beta)/a0;
}

struct DspConfig {
    bool enabled=false,bass=false,virtualizer=false,outputGain=false,headroom=false,bypass=false;
    int bassStrength=0,virtualizerStrength=0;
    float outputGainDb=0.f;
    std::array<float,kMaxBands> bands{};
    std::array<Biquad,kMaxBands> eq{};
    Biquad bassFilter{};
    int sampleRate=48000;
    void build(int fs){
        sampleRate=std::max(8000,fs);
        for(int i=0;i<kMaxBands;++i) peaking(eq[i],float(sampleRate),kBandHz[i],enabled?bands[i]:0.f);
        lowShelf(bassFilter,float(sampleRate),105.f,enabled&&bass?10.f*float(bassStrength)/1000.f:0.f);
    }
};

class DspRuntime {
public:
    void reset(const DspConfig& c){cfg_=c;for(auto& q:cfg_.eq)q.reset();cfg_.bassFilter.reset();gain_=1.f;}
    void process(float& l,float& r){
        if(cfg_.enabled&&!cfg_.bypass){
            l=cfg_.bassFilter.L(l);r=cfg_.bassFilter.R(r);
            for(auto& q:cfg_.eq){l=q.L(l);r=q.R(r);}
            if(cfg_.outputGain){const float g=std::pow(10.f,cfg_.outputGainDb/20.f);l*=g;r*=g;}
            if(cfg_.virtualizer){const float width=1.f+1.25f*float(cfg_.virtualizerStrength)/1000.f;const float mid=.5f*(l+r),side=.5f*(l-r)*width;l=mid+side;r=mid-side;}
        }
        const float peak=std::max(std::abs(l),std::abs(r));
        const float target=peak>.92f?.92f/peak:1.f;
        const float a=std::exp(-1.f/(std::max(8000,cfg_.sampleRate)*.0015f));
        const float rel=std::exp(-1.f/(std::max(8000,cfg_.sampleRate)*.06f));
        gain_=target<gain_?a*gain_+(1.f-a)*target:rel*gain_+(1.f-rel)*target;
        l=std::clamp(l*gain_,-.999f,.999f);r=std::clamp(r*gain_,-.999f,.999f);
    }
private:DspConfig cfg_{};float gain_=1.f;
};

class FloatRing {
public:
    explicit FloatRing(size_t capacityFrames=48000*12):data_(nextPow2(capacityFrames)*kMaxChannels),mask_(nextPow2(capacityFrames)-1){}
    void reset(int channels){channels_=std::clamp(channels,1,kMaxChannels);read_.store(0);write_.store(0);}
    size_t available()const{return write_.load(std::memory_order_acquire)-read_.load(std::memory_order_acquire);}
    size_t free()const{return (mask_+1)-std::min(available(),mask_+1);}
    size_t write(const float*src,size_t frames){if(!src||!frames)return 0;const size_t n=std::min(frames,free());const auto w=write_.load(std::memory_order_relaxed);for(size_t i=0;i<n;++i){auto f=(w+i)&mask_;std::memcpy(&data_[f*channels_],&src[i*channels_],channels_*sizeof(float));}write_.store(w+n,std::memory_order_release);return n;}
    size_t read(float*dst,size_t frames){if(!dst||!frames)return 0;const size_t n=std::min(frames,available());const auto r=read_.load(std::memory_order_relaxed);for(size_t i=0;i<n;++i){auto f=(r+i)&mask_;std::memcpy(&dst[i*channels_],&data_[f*channels_],channels_*sizeof(float));}read_.store(r+n,std::memory_order_release);return n;}
private:
    static size_t nextPow2(size_t n){size_t p=1;while(p<n)p<<=1;return p;}
    std::vector<float> data_;size_t mask_;int channels_=2;std::atomic<size_t> read_{0},write_{0};
};

struct Spectrum {
    static constexpr int N=512,B=32;std::array<float,N> x{};std::array<float,B> level{};int pos=0,filled=0,hop=0,fs=48000;
    std::array<float,B> read()const{return level;}
    void reset(int rate){fs=std::max(8000,rate);x.fill(0);level.fill(0);pos=filled=hop=0;}
    void push(float s){x[pos]=s;pos=(pos+1)%N;filled=std::min(N,filled+1);if(++hop<128||filled<N)return;hop=0;float p=0;std::array<float,N> re{},im{};for(int i=0;i<N;++i){int idx=(pos+i)%N;float w=.5f-.5f*std::cos(2*kPi*i/(N-1));re[i]=x[idx]*w;p+=re[i]*re[i];}for(int i=1,j=0;i<N;++i){int bit=N>>1;for(;j&bit;bit>>=1)j^=bit;j^=bit;if(i<j){std::swap(re[i],re[j]);std::swap(im[i],im[j]);}}for(int len=2;len<=N;len<<=1){float a=-2*kPi/len,wr0=std::cos(a),wi0=std::sin(a);for(int i=0;i<N;i+=len){float wr=1,wi=0;for(int j=0;j<len/2;++j){int u=i+j,v=u+len/2;float vr=re[v]*wr-im[v]*wi,vi=re[v]*wi+im[v]*wr,ur=re[u],ui=im[u];re[u]=ur+vr;im[u]=ui+vi;re[v]=ur-vr;im[v]=ui-vi;float nwr=wr*wr0-wi*wi0;wi=wr*wi0+wi*wr0;wr=nwr;}}}float minHz=35,maxHz=std::min(18000.f,fs*.45f);for(int b=0;b<B;++b){float lo=minHz*std::pow(maxHz/minHz,float(b)/B),hi=minHz*std::pow(maxHz/minHz,float(b+1)/B);int a=std::max(1,int(std::floor(lo*N/fs))),z=std::min(N/2,int(std::ceil(hi*N/fs)));float peak=0;for(int k=a;k<=z;++k)peak=std::max(peak,std::sqrt(re[k]*re[k]+im[k]*im[k])/(N*.5f));level[b]=std::max(peak,level[b]*.78f);}}
    float rms()const{float p=0;for(float v:x)p+=v*v;return std::sqrt(p/N);}
};

static Spectrum gSpectrum;

class NativePlayback final:public oboe::AudioStreamDataCallback{
public:
    NativePlayback():ring_(48000*12){ring_.reset(2);}
    ~NativePlayback(){stop();}
    bool play(const std::string& url){stop();if(url.empty())return false;url_=url;stopRequested_=false;paused_=false;decoderEos_=false;status_=0;worker_=std::thread(&NativePlayback::decodeLoop,this);return true;}
    void pause(){paused_.store(true);}
    void resume(){paused_.store(false);}
    void stop(){stopRequested_.store(true);paused_.store(false);if(worker_.joinable())worker_.join();closeOutput();destroyCodec();destroyExtractor();ring_.reset(2);active_.store(false);decoderEos_.store(false);framesPlayed_.store(0);basePositionUs_.store(0);}
    void seek(int64_t us){pendingSeekUs_.store(std::max<int64_t>(0,us));}
    void volume(float v){volume_.store(std::clamp(v,0.f,1.f));}
    bool active()const{return active_.load();}
    int64_t position()const{int fs=outputRate_.load();if(fs<=0)return basePositionUs_.load();return basePositionUs_.load()+framesPlayed_.load()*1000000LL/fs;}
    int status()const{return status_.load();}
    void setConfig(std::shared_ptr<const DspConfig> c){std::atomic_store_explicit(&config_,std::move(c),std::memory_order_release);configVersion.fetch_add(1,std::memory_order_acq_rel);}
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*,void* data,int32_t frames)override{
        auto*out=static_cast<float*>(data);const int ch=outputChannels_.load();if(!out||ch<1||ch>2)return oboe::DataCallbackResult::Stop;
        const size_t n=static_cast<size_t>(frames);std::fill(out,out+n*ch,0.f);
        if(paused_.load())return oboe::DataCallbackResult::Continue;
        const size_t got=ring_.read(out,n);
        auto cfg=std::atomic_load_explicit(&config_,std::memory_order_acquire);if(cfg&&cfg->sampleRate!=outputRate_.load()){} // config is rebuilt by decoder on format changes
        if(cfgVersionSeen_!=configVersion_()){if(cfg){runtime_.reset(*cfg);cfgVersionSeen_=configVersion_();}}
        const float v=volume_.load(std::memory_order_relaxed);
        for(size_t i=0;i<got;++i){float l=out[i*ch]*v,r=(ch>1?out[i*ch+1]:l)*v;runtime_.process(l,r);out[i*ch]=l;if(ch>1)out[i*ch+1]=r;}
        framesPlayed_.fetch_add(static_cast<int64_t>(got));
        if(gSpectrumEnabled_){for(size_t i=0;i<got;++i)gSpectrum.push(.5f*(out[i*ch]+(ch>1?out[i*ch+1]:out[i*ch])));}
        if(decoderEos_.load()&&ring_.available()==0)return oboe::DataCallbackResult::Stop;
        return oboe::DataCallbackResult::Continue;
    }
private:
    uint64_t configVersion_()const{return configVersion.load(std::memory_order_acquire);} 
    bool setup(){
        extractor_=AMediaExtractor_new();if(!extractor_)return fail(10);if(AMediaExtractor_setDataSource(extractor_,url_.c_str())!=AMEDIA_OK)return fail(11);
        ssize_t track=-1;AMediaFormat*fmt=nullptr;for(size_t i=0;i<AMediaExtractor_getTrackCount(extractor_);++i){AMediaFormat*f=AMediaExtractor_getTrackFormat(extractor_,i);const char*m=nullptr;if(f&&AMediaFormat_getString(f,AMEDIAFORMAT_KEY_MIME,&m)&&m&&std::strncmp(m,"audio/",6)==0){track=static_cast<ssize_t>(i);fmt=f;break;}if(f)AMediaFormat_delete(f);}if(track<0||!fmt)return fail(12);
        if(AMediaExtractor_selectTrack(extractor_,track)!=AMEDIA_OK){AMediaFormat_delete(fmt);return fail(13);}const char*mime=nullptr;if(!AMediaFormat_getString(fmt,AMEDIAFORMAT_KEY_MIME,&mime)||!mime){AMediaFormat_delete(fmt);return fail(14);}
        codec_=AMediaCodec_createDecoderByType(mime);if(!codec_){AMediaFormat_delete(fmt);return fail(15);}if(AMediaCodec_configure(codec_,fmt,nullptr,nullptr,0)!=AMEDIA_OK){AMediaFormat_delete(fmt);return fail(16);}AMediaFormat_delete(fmt);if(AMediaCodec_start(codec_)!=AMEDIA_OK)return fail(17);
        int32_t sr=0,ch=2,enc=2;AMediaFormat*outFmt=AMediaCodec_getOutputFormat(codec_);if(outFmt){AMediaFormat_getInt32(outFmt,AMEDIAFORMAT_KEY_SAMPLE_RATE,&sr);AMediaFormat_getInt32(outFmt,AMEDIAFORMAT_KEY_CHANNEL_COUNT,&ch);AMediaFormat_getInt32(outFmt,AMEDIAFORMAT_KEY_PCM_ENCODING,&enc);AMediaFormat_delete(outFmt);}if(sr<=0)sr=48000;if(ch<=0)ch=2;decoderRate_=sr;decoderChannels_=std::clamp(ch,1,8);decoderEncoding_=enc;
        return openOutput(sr);
    }
    bool openOutput(int requestedRate){closeOutput();oboe::AudioStreamBuilder b;b.setDirection(oboe::Direction::Output).setFormat(oboe::AudioFormat::Float).setChannelCount(2).setSampleRate(requestedRate).setPerformanceMode(oboe::PerformanceMode::LowLatency).setSharingMode(oboe::SharingMode::Shared).setDataCallback(this);auto r=b.openStream(stream_);if(r!=oboe::Result::OK||!stream_){b.setPerformanceMode(oboe::PerformanceMode::None);r=b.openStream(stream_);}if(r!=oboe::Result::OK||!stream_)return fail(20);outputRate_.store(stream_->getSampleRate());outputChannels_.store(std::clamp(stream_->getChannelCount(),1,2));ring_.reset(outputChannels_.load());gSpectrum.reset(outputRate_.load());if(stream_->requestStart()!=oboe::Result::OK){closeOutput();return fail(21);}return true;}
    void decodeLoop(){active_.store(true);if(!setup()){active_.store(false);return;}std::vector<float> in;std::vector<float> converted;bool inputEos=false,outputEos=false;int64_t lastPts=0;
        while(!stopRequested_.load()&&!outputEos){
            int64_t seek=pendingSeekUs_.exchange(-1);if(seek>=0){if(!extractor_||!codec_)break;ring_.reset(outputChannels_.load());AMediaCodec_flush(codec_);AMediaExtractor_seekTo(extractor_,seek,AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);framesPlayed_.store(0);basePositionUs_.store(seek);decoderEos_.store(false);inputEos=false;outputEos=false;continue;}
            if(paused_.load()){std::this_thread::sleep_for(std::chrono::milliseconds(5));continue;}
            if(!inputEos&&ring_.free()>4096){ssize_t idx=AMediaCodec_dequeueInputBuffer(codec_,5000);if(idx>=0){size_t cap=0;auto*dst=AMediaCodec_getInputBuffer(codec_,idx,&cap);if(!dst||cap==0){status_.store(30);break;}ssize_t size=AMediaExtractor_readSampleData(extractor_,dst,cap);int64_t pts=AMediaExtractor_getSampleTime(extractor_);if(size<0){AMediaCodec_queueInputBuffer(codec_,idx,0,0,static_cast<uint64_t>(std::max<int64_t>(0,pts)),AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);inputEos=true;}else{AMediaCodec_queueInputBuffer(codec_,idx,0,size,static_cast<uint64_t>(std::max<int64_t>(0,pts)),0);AMediaExtractor_advance(extractor_);}}}
            AMediaCodecBufferInfo info{};ssize_t oi=AMediaCodec_dequeueOutputBuffer(codec_,&info,5000);if(oi==AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED){AMediaFormat*f=AMediaCodec_getOutputFormat(codec_);if(f){int32_t sr=0,ch=2,enc=2;AMediaFormat_getInt32(f,AMEDIAFORMAT_KEY_SAMPLE_RATE,&sr);AMediaFormat_getInt32(f,AMEDIAFORMAT_KEY_CHANNEL_COUNT,&ch);AMediaFormat_getInt32(f,AMEDIAFORMAT_KEY_PCM_ENCODING,&enc);AMediaFormat_delete(f);if(sr>0){decoderRate_=sr;decoderChannels_=std::clamp(int(ch),1,8);decoderEncoding_=enc;}}continue;}if(oi<0)continue;
            size_t size=0;auto*src=AMediaCodec_getOutputBuffer(codec_,oi,&size);if(src&&info.size>0){const size_t off=std::min<size_t>(info.offset,size),bytes=std::min<size_t>(info.size,size-off);const int ch=decoderChannels_;const bool isFloat=(decoderEncoding_==4);size_t samples=isFloat?bytes/sizeof(float):bytes/sizeof(int16_t);if(ch<=0)samples=0;size_t frames=samples/static_cast<size_t>(ch);in.resize(frames*ch);if(isFloat){auto*p=reinterpret_cast<const float*>(src+off);std::copy(p,p+frames*ch,in.begin());}else{auto*p=reinterpret_cast<const int16_t*>(src+off);for(size_t i=0;i<frames*ch;++i)in[i]=float(p[i])/32768.f;}
                const int outCh=outputChannels_.load();const int outRate=outputRate_.load();const double ratio=double(outRate)/double(std::max(1,decoderRate_));const size_t outFrames=static_cast<size_t>(std::ceil(frames*ratio));converted.resize(outFrames*outCh);if(frames>0){for(size_t j=0;j<outFrames;++j){double srcPos=double(j)/ratio;size_t i0=std::min(frames-1,static_cast<size_t>(srcPos));size_t i1=std::min(frames-1,i0+1);float t=float(srcPos-double(i0));auto sample=[&](size_t f,int c){return in[f*ch+std::min(c,ch-1)];};float l0=sample(i0,0),r0=ch>1?sample(i0,1):l0,l1=sample(i1,0),r1=ch>1?sample(i1,1):l1;float l=l0+(l1-l0)*t,r=r0+(r1-r0)*t;if(ch>2){l=0;r=0;for(int c=0;c<ch;++c){float s=sample(i0,c);if(c==0)l+=.7071f*s;else if(c==1)r+=.7071f*s;else{l+=.5f*s;r+=.5f*s;}}}converted[j*outCh]=l;if(outCh>1)converted[j*outCh+1]=r;}}
                size_t written=0;while(written<outFrames&&!stopRequested_.load()){size_t n=ring_.write(converted.data()+written*outCh,outFrames-written);written+=n;if(n==0)std::this_thread::sleep_for(std::chrono::milliseconds(2));}lastPts=info.presentationTimeUs;}
            AMediaCodec_releaseOutputBuffer(codec_,oi,false);if(info.flags&AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM){outputEos=true;decoderEos_.store(true);}
        }
        decoderEos_.store(true);active_.store(false);
    }
    bool fail(int code){status_.store(code);return false;}
    void destroyExtractor(){if(extractor_){AMediaExtractor_delete(extractor_);extractor_=nullptr;}}
    void destroyCodec(){if(codec_){AMediaCodec_stop(codec_);AMediaCodec_delete(codec_);codec_=nullptr;}}
    void closeOutput(){if(stream_){stream_->requestStop();stream_->close();stream_.reset();}}

    std::string url_;std::thread worker_;std::atomic<bool>stopRequested_{false},paused_{false},active_{false},decoderEos_{false};std::atomic<int64_t>pendingSeekUs_{-1},basePositionUs_{0},framesPlayed_{0};std::atomic<int>outputRate_{48000},outputChannels_{2},status_{0};std::atomic<float>volume_{1.f};
    AMediaExtractor*extractor_=nullptr;AMediaCodec*codec_=nullptr;int decoderRate_=48000,decoderChannels_=2,decoderEncoding_=2;FloatRing ring_;std::shared_ptr<oboe::AudioStream>stream_;
    std::shared_ptr<const DspConfig> config_{std::make_shared<DspConfig>()};std::atomic<uint64_t> configVersion{0};uint64_t cfgVersionSeen_=~0ULL;DspRuntime runtime_;bool gSpectrumEnabled_=true;
};

class DspEngine {public:DspEngine(){auto c=std::make_shared<DspConfig>();c->build(48000);std::shared_ptr<const DspConfig> cc=c;std::atomic_store(&cfg_,cc);}void configure(int fs){auto c=std::make_shared<DspConfig>(*std::atomic_load(&cfg_));c->build(fs);std::shared_ptr<const DspConfig> cc=c;std::atomic_store(&cfg_,cc);}void set(bool enabled,const std::vector<int>& bands,bool bass,int bassS,bool virt,int virtS,bool gain,int gainMb,bool headroom,bool bypass){auto c=std::make_shared<DspConfig>(*std::atomic_load(&cfg_));c->enabled=enabled;c->bass=bass;c->bassStrength=std::clamp(bassS,0,1000);c->virtualizer=virt;c->virtualizerStrength=std::clamp(virtS,0,1000);c->outputGain=gain;c->outputGainDb=float(gainMb)/100.f;c->headroom=headroom;c->bypass=bypass;c->bands.fill(0);if(!bands.empty())for(int i=0;i<kMaxBands;++i){float x=float(i)*(bands.size()-1)/float(kMaxBands-1);int lo=std::clamp(int(std::floor(x)),0,int(bands.size())-1),hi=std::min(lo+1,int(bands.size())-1);float t=x-lo;c->bands[i]=(bands[lo]*(1-t)+bands[hi]*t)/100.f;}c->build(c->sampleRate);std::shared_ptr<const DspConfig> cc=c;std::atomic_store(&cfg_,cc);}std::shared_ptr<const DspConfig>cfg()const{return std::atomic_load(&cfg_);}private:std::shared_ptr<const DspConfig>cfg_;};

static DspEngine* dsp(jlong h){return reinterpret_cast<DspEngine*>(h);}static NativePlayback* playback(jlong h){return reinterpret_cast<NativePlayback*>(h);}

}

extern "C" JNIEXPORT jlong JNICALL Java_com_jay_glossy_ui_player_NativeEngine_nCreate(JNIEnv*,jobject){return reinterpret_cast<jlong>(new DspEngine());}
extern "C" JNIEXPORT jboolean JNICALL Java_com_jay_glossy_ui_player_NativeEngine_nConfigure(JNIEnv*,jobject,jlong h,jint sr,jint,jint){auto*d=dsp(h);if(!d||sr<=0)return JNI_FALSE;d->configure(sr);return JNI_TRUE;}
extern "C" JNIEXPORT void JNICALL Java_com_jay_glossy_ui_player_NativeEngine_nSetDsp(JNIEnv*e,jobject,jlong h,jboolean en,jintArray a,jboolean be,jint bs,jboolean ve,jint vs,jboolean ge,jint gm,jboolean ah,jboolean bp){auto*d=dsp(h);if(!d)return;std::vector<int>b;if(a){jsize n=e->GetArrayLength(a);b.resize(n);if(n)e->GetIntArrayRegion(a,0,n,b.data());}d->set(en,b,be,bs,ve,vs,ge,gm,ah,bp);}
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_jay_glossy_ui_player_NativeEngine_nGetSpectrum(JNIEnv*e,jobject){jfloatArray a=e->NewFloatArray(Spectrum::B);if(!a)return nullptr;std::array<float,Spectrum::B>v{};for(int i=0;i<Spectrum::B;++i)v[i]=gSpectrum.level[i];e->SetFloatArrayRegion(a,0,Spectrum::B,v.data());return a;}
extern "C" JNIEXPORT jfloat JNICALL Java_com_jay_glossy_ui_player_NativeEngine_nGetRms(JNIEnv*,jobject){return gSpectrum.rms();}
extern "C" JNIEXPORT jlong JNICALL Java_com_jay_glossy_ui_player_NativeEngine_nCreatePlayback(JNIEnv*,jobject){return reinterpret_cast<jlong>(new NativePlayback());}
extern "C" JNIEXPORT void JNICALL Java_com_jay_glossy_ui_player_NativeEngine_nApplyPlaybackDsp(JNIEnv*,jobject,jlong ph,jlong dh){auto*p=playback(ph);auto*d=dsp(dh);if(p&&d)p->setConfig(d->cfg());}
extern "C" JNIEXPORT jboolean JNICALL Java_com_jay_glossy_ui_player_NativeEngine_nPlayUrl(JNIEnv*e,jobject,jlong h,jstring u){auto*p=playback(h);if(!p||!u)return JNI_FALSE;const char*s=e->GetStringUTFChars(u,nullptr);if(!s)return JNI_FALSE;bool ok=p->play(s);e->ReleaseStringUTFChars(u,s);return ok?JNI_TRUE:JNI_FALSE;}
extern "C" JNIEXPORT void JNICALL Java_com_jay_glossy_ui_player_NativeEngine_nPausePlayback(JNIEnv*,jobject,jlong h){if(auto*p=playback(h))p->pause();}
extern "C" JNIEXPORT void JNICALL Java_com_jay_glossy_ui_player_NativeEngine_nResumePlayback(JNIEnv*,jobject,jlong h){if(auto*p=playback(h))p->resume();}
extern "C" JNIEXPORT void JNICALL Java_com_jay_glossy_ui_player_NativeEngine_nStopPlayback(JNIEnv*,jobject,jlong h){if(auto*p=playback(h))p->stop();}
extern "C" JNIEXPORT void JNICALL Java_com_jay_glossy_ui_player_NativeEngine_nSeekPlayback(JNIEnv*,jobject,jlong h,jlong us){if(auto*p=playback(h))p->seek(us);}
extern "C" JNIEXPORT void JNICALL Java_com_jay_glossy_ui_player_NativeEngine_nSetPlaybackVolume(JNIEnv*,jobject,jlong h,jfloat v){if(auto*p=playback(h))p->volume(v);}
extern "C" JNIEXPORT jlong JNICALL Java_com_jay_glossy_ui_player_NativeEngine_nGetPlaybackPosition(JNIEnv*,jobject,jlong h){if(auto*p=playback(h))return p->position();return 0;}
extern "C" JNIEXPORT jboolean JNICALL Java_com_jay_glossy_ui_player_NativeEngine_nIsPlaybackActive(JNIEnv*,jobject,jlong h){if(auto*p=playback(h))return p->active()?JNI_TRUE:JNI_FALSE;return JNI_FALSE;}
extern "C" JNIEXPORT jint JNICALL Java_com_jay_glossy_ui_player_NativeEngine_nGetPlaybackStatus(JNIEnv*,jobject,jlong h){if(auto*p=playback(h))return p->status();return -1;}
extern "C" JNIEXPORT void JNICALL Java_com_jay_glossy_ui_player_NativeEngine_nReleasePlayback(JNIEnv*,jobject,jlong h){delete playback(h);}
extern "C" JNIEXPORT void JNICALL Java_com_jay_glossy_ui_player_NativeEngine_nReset(JNIEnv*,jobject,jlong){}
extern "C" JNIEXPORT void JNICALL Java_com_jay_glossy_ui_player_NativeEngine_nRelease(JNIEnv*,jobject,jlong h){delete dsp(h);}
