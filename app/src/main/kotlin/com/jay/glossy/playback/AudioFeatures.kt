package com.jay.glossy.playback

/** Lightweight audio-analysis result used by the AutoMix transition engine. */
data class AudioFeatures(
    val bpm: Int?,
    val key: String?,
    val keyScale: String?,
) {
    val isUsable: Boolean
        get() = bpm != null || key != null
}
