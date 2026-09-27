#pragma once
#include <cstdint>

extern "C" {
void* glossy_dsp_create();
bool glossy_dsp_configure(void* handle, int sampleRate, int channels, int encoding);
void glossy_dsp_set(void* handle, bool enabled, const int* bandsMb, int bandCount,
                    bool bassEnabled, int bassStrength,
                    bool virtualizerEnabled, int virtualizerStrength,
                    bool outputGainEnabled, int outputGainMb,
                    bool autoHeadroom, bool bypass,
                    bool spatialEnabled, int spatialStrength,
                    bool crossfeedEnabled, int crossfeedStrength,
                    bool reverbEnabled, int reverbMix);
int glossy_dsp_process(void* handle, const void* input, void* output, int bytes);
void glossy_dsp_reset(void* handle);
void glossy_dsp_release(void* handle);
}
