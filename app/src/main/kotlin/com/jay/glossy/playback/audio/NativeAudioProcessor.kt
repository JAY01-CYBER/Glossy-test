package com.jay.glossy.playback.audio

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.jay.glossy.ui.player.NativeEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sends Media3's already-decoded PCM through the native Glossy DSP engine.
 * No network/codec/audio-device work happens in native code.
 */
@UnstableApi
@Suppress("DEPRECATION")
class NativeAudioProcessor : AudioProcessor {
    private val engine = NativeEngine()
    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID
    private var active = false
    @Volatile private var routingEnabled = false

    fun setRoutingEnabled(enabled: Boolean) {
        routingEnabled = enabled
    }
    private var inputEnded = false
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var appliedDspVersion = -1L

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding

        if (channelCount <= 0 || sampleRate <= 0) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        val configured = try {
            engine.configure(sampleRate, channelCount, encoding)
        } catch (_: UnsatisfiedLinkError) {
            false
        }

        // If the optional native DSP bridge is unavailable, leave this processor
        // inactive so Media3 continues with its normal decoded PCM path instead
        // of crashing the app on a JNI symbol mismatch.
        if (!configured) {
            active = false
            return inputAudioFormat
        }

        active = true
        return inputAudioFormat
    }

    override fun isActive(): Boolean = active

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return

        val out = replaceOutputBuffer(bytes)
        val input = inputBuffer.slice().order(ByteOrder.nativeOrder())

        if (!routingEnabled || !active) {
            out.put(input)
            out.flip()
            inputBuffer.position(inputBuffer.limit())
            outputBuffer = out
            return
        }

        try {
            syncDspSettings()
        } catch (_: UnsatisfiedLinkError) {
            routingEnabled = false
            out.put(input)
            out.flip()
            inputBuffer.position(inputBuffer.limit())
            outputBuffer = out
            return
        }

        val written = try {
            engine.process(input, out, bytes)
        } catch (_: UnsatisfiedLinkError) {
            routingEnabled = false
            0
        }
        inputBuffer.position(inputBuffer.limit())
        if (written > 0) {
            out.position(0)
            out.limit(written)
            outputBuffer = out
        } else {
            // Never turn a native DSP failure into silence. Preserve the decoded PCM.
            out.clear()
            out.put(input)
            out.flip()
            outputBuffer = out
        }
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === EMPTY_BUFFER

    @Deprecated("Deprecated in AudioProcessor")
    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        inputEnded = false
        if (sampleRate > 0) engine.reset()
        appliedDspVersion = -1L
    }

    @Deprecated("Deprecated in AudioProcessor")
    override fun reset() {
        flush()
        sampleRate = 0
        channelCount = 0
        encoding = C.ENCODING_INVALID
        active = false
        engine.close()
    }

    private fun syncDspSettings() {
        val (version, settings) = NativeDspController.snapshot()
        if (version == appliedDspVersion) return
        val bands = settings.bandsMb.toIntArray()
        engine.setDsp(
            enabled = settings.enabled,
            bandsMb = bands,
            bassEnabled = settings.bassBoostEnabled,
            bassStrength = settings.bassBoostStrength,
            virtualizerEnabled = settings.virtualizerEnabled,
            virtualizerStrength = settings.virtualizerStrength,
            spatialEnabled = settings.spatialEnabled,
            spatialStrength = settings.spatialStrength,
            crossfeedEnabled = settings.crossfeedEnabled,
            crossfeedStrength = settings.crossfeedStrength,
            reverbEnabled = settings.reverbEnabled,
            reverbMix = settings.reverbMix,
            clarityEnabled = settings.clarityEnabled,
            clarityStrength = settings.clarityStrength,
            compressorEnabled = settings.compressorEnabled,
            compressorStrength = settings.compressorStrength,
            limiterEnabled = settings.limiterEnabled,
            limiterStrength = settings.limiterStrength,
            outputGainEnabled = settings.outputGainEnabled,
            outputGainMb = settings.outputGainMb,
            autoHeadroom = settings.autoHeadroomEnabled,
            bypass = settings.bypass,
        )
        appliedDspVersion = version
    }

    private fun replaceOutputBuffer(size: Int): ByteBuffer {
        if (outputBuffer.capacity() < size) {
            outputBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        return outputBuffer
    }

    companion object {
        private val EMPTY_BUFFER = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}
