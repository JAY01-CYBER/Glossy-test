package com.jay.glossy.ui.player

import java.util.concurrent.atomic.AtomicBoolean
import com.jay.glossy.playback.audio.NativeDspController

/** Standalone native playback bridge: native extractor/codec + PCM ring + Oboe output. */
class NativePlayer : AutoCloseable {
    private var handle: Long = create()
    private val available = AtomicBoolean(handle != 0L)

    fun playUrl(url: String, positionMs: Long = 0L): Boolean {
        if (!available.get()) return false
        val requested = runCatching { nPlayUrl(handle, url, positionMs) }.getOrDefault(false)
        if (!requested) return false
        return runCatching { nWaitUntilReady(handle, 15_000) }.getOrDefault(false)
    }

    fun pause() { if (available.get()) runCatching { nPause(handle) } }
    fun resume() { if (available.get()) runCatching { nResume(handle) } }
    fun stop() { if (available.get()) runCatching { nStop(handle) } }
    fun seekTo(positionMs: Long): Boolean = available.get() && runCatching { nSeekTo(handle, positionMs) }.getOrDefault(false)
    fun position(): Long = if (available.get()) runCatching { nPosition(handle) }.getOrDefault(0L) else 0L
    fun duration(): Long = if (available.get()) runCatching { nDuration(handle) }.getOrDefault(0L) else 0L
    fun hasError(): Boolean = available.get() && runCatching { nHasError(handle) }.getOrDefault(false)
    fun isPlaying(): Boolean = available.get() && runCatching { nIsPlaying(handle) }.getOrDefault(false)
    fun setVolume(volume: Float) { if (available.get()) runCatching { nSetVolume(handle, volume) } }

    fun syncDsp() {
        if (!available.get()) return
        val (_, settings) = NativeDspController.snapshot()
        runCatching {
            nSetDsp(
                handle, settings.enabled, settings.bandsMb.toIntArray(),
                settings.bassBoostEnabled, settings.bassBoostStrength,
                settings.virtualizerEnabled, settings.virtualizerStrength,
                settings.spatialEnabled, settings.spatialStrength,
                settings.crossfeedEnabled, settings.crossfeedStrength,
                settings.reverbEnabled, settings.reverbMix,
                settings.clarityEnabled, settings.clarityStrength,
                settings.compressorEnabled, settings.compressorStrength,
                settings.limiterEnabled, settings.limiterStrength,
                settings.outputGainEnabled, settings.outputGainMb,
                settings.autoHeadroomEnabled, settings.bypass,
            )
        }
    }

    override fun close() {
        if (available.compareAndSet(true, false)) {
            runCatching { nStop(handle) }
            runCatching { nRelease(handle) }
            handle = 0L
        }
    }

    private fun create(): Long = runCatching { nCreate() }.getOrDefault(0L)

    private external fun nCreate(): Long
    private external fun nPlayUrl(handle: Long, url: String, positionMs: Long): Boolean
    private external fun nPause(handle: Long)
    private external fun nResume(handle: Long)
    private external fun nStop(handle: Long)
    private external fun nSeekTo(handle: Long, positionMs: Long): Boolean
    private external fun nPosition(handle: Long): Long
    private external fun nDuration(handle: Long): Long
    private external fun nHasError(handle: Long): Boolean
    private external fun nWaitUntilReady(handle: Long, timeoutMs: Int): Boolean
    private external fun nIsPlaying(handle: Long): Boolean
    private external fun nSetVolume(handle: Long, volume: Float)
    private external fun nSetDsp(handle: Long, enabled: Boolean, bandsMb: IntArray, bassEnabled: Boolean, bassStrength: Int, virtualizerEnabled: Boolean, virtualizerStrength: Int, spatialEnabled: Boolean, spatialStrength: Int, crossfeedEnabled: Boolean, crossfeedStrength: Int, reverbEnabled: Boolean, reverbMix: Int, clarityEnabled: Boolean, clarityStrength: Int, compressorEnabled: Boolean, compressorStrength: Int, limiterEnabled: Boolean, limiterStrength: Int, outputGainEnabled: Boolean, outputGainMb: Int, autoHeadroom: Boolean, bypass: Boolean)
    private external fun nRelease(handle: Long)

    companion object {
        init {
            runCatching { System.loadLibrary("glossy_engine") }
        }
    }
}
