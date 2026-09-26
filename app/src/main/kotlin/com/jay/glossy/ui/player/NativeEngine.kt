package com.jay.glossy.ui.player

import java.nio.ByteBuffer

/** Lightweight JNI bridge for Glossy's per-player real-time C++ DSP state. */
class NativeEngine : AutoCloseable {
    private var handle: Long = nCreate()
    private var playbackHandle: Long = 0L

    fun configure(sampleRate: Int, channelCount: Int, encoding: Int): Boolean {
        ensureHandle()
        return nConfigure(handle, sampleRate, channelCount, encoding)
    }

    fun process(input: ByteBuffer, output: ByteBuffer, bytes: Int): Int {
        ensureHandle()
        return nProcess(handle, input, output, bytes)
    }

    fun setDsp(
        enabled: Boolean,
        bandsMb: IntArray,
        bassEnabled: Boolean,
        bassStrength: Int,
        virtualizerEnabled: Boolean,
        virtualizerStrength: Int,
        outputGainEnabled: Boolean,
        outputGainMb: Int,
        autoHeadroom: Boolean,
        bypass: Boolean,
    ) {
        ensureHandle()
        nSetDsp(
            handle,
            enabled,
            bandsMb,
            bassEnabled,
            bassStrength,
            virtualizerEnabled,
            virtualizerStrength,
            outputGainEnabled,
            outputGainMb,
            autoHeadroom,
            bypass,
        )
        if (playbackHandle != 0L) nApplyPlaybackDsp(playbackHandle, handle)
    }

    fun getSpectrum(): FloatArray = nGetSpectrum()

    fun getRms(): Float = nGetRms()

    /** Starts the real native C++ playback pipeline (extractor -> MediaCodec -> PCM ring -> Oboe). */
    fun playUrl(url: String): Boolean {
        ensurePlaybackHandle()
        nApplyPlaybackDsp(playbackHandle, handle)
        return nPlayUrl(playbackHandle, url)
    }

    fun pausePlayback() {
        if (playbackHandle != 0L) nPausePlayback(playbackHandle)
    }

    fun resumePlayback() {
        if (playbackHandle != 0L) nResumePlayback(playbackHandle)
    }

    fun stopPlayback() {
        if (playbackHandle != 0L) nStopPlayback(playbackHandle)
    }

    fun seekPlayback(positionUs: Long) {
        if (playbackHandle != 0L) nSeekPlayback(playbackHandle, positionUs)
    }

    fun setPlaybackVolume(volume: Float) {
        if (playbackHandle != 0L) nSetPlaybackVolume(playbackHandle, volume)
    }

    fun getPlaybackPosition(): Long =
        if (playbackHandle != 0L) nGetPlaybackPosition(playbackHandle) else 0L

    fun isPlaybackActive(): Boolean =
        playbackHandle != 0L && nIsPlaybackActive(playbackHandle)

    fun playbackStatus(): Int =
        if (playbackHandle != 0L) nGetPlaybackStatus(playbackHandle) else -1

    fun reset() {
        if (handle != 0L) nReset(handle)
    }

    override fun close() {
        if (playbackHandle != 0L) {
            nReleasePlayback(playbackHandle)
            playbackHandle = 0L
        }
        if (handle != 0L) {
            nRelease(handle)
            handle = 0L
        }
    }

    private fun ensurePlaybackHandle() {
        if (playbackHandle == 0L) playbackHandle = nCreatePlayback()
        check(playbackHandle != 0L) { "Failed to create Glossy native playback engine" }
    }

    private fun ensureHandle() {
        if (handle == 0L) handle = nCreate()
        check(handle != 0L) { "Failed to create Glossy native audio engine" }
    }

    private external fun nCreate(): Long
    private external fun nConfigure(handle: Long, sampleRate: Int, channels: Int, encoding: Int): Boolean
    private external fun nProcess(handle: Long, input: ByteBuffer, output: ByteBuffer, bytes: Int): Int
    private external fun nSetDsp(
        handle: Long,
        enabled: Boolean,
        bandsMb: IntArray,
        bassEnabled: Boolean,
        bassStrength: Int,
        virtualizerEnabled: Boolean,
        virtualizerStrength: Int,
        outputGainEnabled: Boolean,
        outputGainMb: Int,
        autoHeadroom: Boolean,
        bypass: Boolean,
    )
    private external fun nGetSpectrum(): FloatArray
    private external fun nGetRms(): Float
    private external fun nCreatePlayback(): Long
    private external fun nPlayUrl(handle: Long, url: String): Boolean
    private external fun nApplyPlaybackDsp(playbackHandle: Long, dspHandle: Long)
    private external fun nPausePlayback(handle: Long)
    private external fun nResumePlayback(handle: Long)
    private external fun nStopPlayback(handle: Long)
    private external fun nSeekPlayback(handle: Long, positionUs: Long)
    private external fun nSetPlaybackVolume(handle: Long, volume: Float)
    private external fun nGetPlaybackPosition(handle: Long): Long
    private external fun nIsPlaybackActive(handle: Long): Boolean
    private external fun nGetPlaybackStatus(handle: Long): Int
    private external fun nReleasePlayback(handle: Long)
    private external fun nReset(handle: Long)
    private external fun nRelease(handle: Long)

    companion object {
        init {
            System.loadLibrary("glossy_engine")
        }
    }
}
