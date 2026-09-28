package com.jay.glossy.ui.player

import java.nio.ByteBuffer

/** Lightweight JNI bridge for Glossy's per-player real-time C++ DSP state. */
class NativeEngine : AutoCloseable {
    private var handle: Long = createHandleSafely()

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
        spatialEnabled: Boolean,
        spatialStrength: Int,
        crossfeedEnabled: Boolean,
        crossfeedStrength: Int,
        reverbEnabled: Boolean,
        reverbMix: Int,
        clarityEnabled: Boolean,
        clarityStrength: Int,
        compressorEnabled: Boolean,
        compressorStrength: Int,
        limiterEnabled: Boolean,
        limiterStrength: Int,
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
            spatialEnabled,
            spatialStrength,
            crossfeedEnabled,
            crossfeedStrength,
            reverbEnabled,
            reverbMix,
            clarityEnabled,
            clarityStrength,
            compressorEnabled,
            compressorStrength,
            limiterEnabled,
            limiterStrength,
            outputGainEnabled,
            outputGainMb,
            autoHeadroom,
            bypass,
        )
    }

    fun getSpectrum(): FloatArray = nGetSpectrum()

    fun getRms(): Float = nGetRms()

    fun reset() {
        if (handle != 0L) nReset(handle)
    }

    override fun close() {
        if (handle != 0L) {
            nRelease(handle)
            handle = 0L
        }
    }

    private fun ensureHandle() {
        if (handle == 0L) handle = createHandleSafely()
        check(handle != 0L) { "Failed to create Glossy native audio engine" }
    }

    private fun createHandleSafely(): Long = try {
        nCreate()
    } catch (_: UnsatisfiedLinkError) {
        0L
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
        spatialEnabled: Boolean,
        spatialStrength: Int,
        crossfeedEnabled: Boolean,
        crossfeedStrength: Int,
        reverbEnabled: Boolean,
        reverbMix: Int,
        clarityEnabled: Boolean,
        clarityStrength: Int,
        compressorEnabled: Boolean,
        compressorStrength: Int,
        limiterEnabled: Boolean,
        limiterStrength: Int,
        outputGainEnabled: Boolean,
        outputGainMb: Int,
        autoHeadroom: Boolean,
        bypass: Boolean,
    )
    private external fun nGetSpectrum(): FloatArray
    private external fun nGetRms(): Float
    private external fun nReset(handle: Long)
    private external fun nRelease(handle: Long)

    companion object {
        init {
            try {
                System.loadLibrary("glossy_engine")
            } catch (_: UnsatisfiedLinkError) {
                // Native DSP is optional for the ExoPlayer path.
                // Keep the app alive and let the processor fall back to normal PCM.
            }
        }
    }
}
