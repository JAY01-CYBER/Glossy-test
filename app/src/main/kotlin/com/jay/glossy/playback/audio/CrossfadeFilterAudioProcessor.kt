package com.jay.glossy.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import com.jay.glossy.playback.AudioFeatureAnalyzer
import com.jay.glossy.playback.AudioFeatures
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-player, runtime-controllable low/high-pass filter used by DJ crossfades.
 *
 * The processor stays in the AudioSink chain so a crossfade filter changes the decoded
 * PCM itself rather than relying only on ExoPlayer's coarse volume control. This mirrors
 * the useful part of SimpMusic's CrossfadeFilterAudioProcessor while remaining independent
 * of its code and data model.
 */
@UnstableApi
@Suppress("DEPRECATION")
class CrossfadeFilterAudioProcessor(
    private val onFeatures: (String, AudioFeatures) -> Unit = { _, _ -> },
) : AudioProcessor {
    enum class FilterType { LOW_PASS, HIGH_PASS }

    @Volatile
    var enabled: Boolean = false

    @Volatile
    var cutoffFrequencyHz: Float = 20_000f
        set(value) {
            field = value.coerceAtLeast(20f)
            coefficientsDirty = true
        }

    @Volatile
    var filterType: FilterType = FilterType.LOW_PASS
        set(value) {
            field = value
            coefficientsDirty = true
        }

    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID
    private var bytesPerSample = 0
    private var inputEnded = false
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER

    @Volatile
    var analysisMediaId: String? = null
        set(value) {
            if (field != value) {
                field = value
                resetAnalysisCapture()
            }
        }

    @Volatile
    var analysisEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) resetAnalysisCapture()
        }

    private var analysisSamples = FloatArray(0)
    private var analysisSampleCount = 0
    private var analysisFrameStride = 1
    private var analysisSampleIndex = 0L
    private val analysisRunning = AtomicBoolean(false)

    @Volatile
    private var coefficientsDirty = true

    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    private var x1L = 0.0
    private var x2L = 0.0
    private var y1L = 0.0
    private var y2L = 0.0
    private var x1R = 0.0
    private var x2R = 0.0
    private var y1R = 0.0
    private var y2R = 0.0

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }
        if (sampleRate <= 0 || channelCount <= 0 || bytesPerSample == 0) {
            // Keep unsupported formats in the normal Media3 path.
            return inputAudioFormat
        }
        coefficientsDirty = true
        analysisFrameStride = maxOf(1, sampleRate / 11025)
        analysisSamples = FloatArray(11025 * 8)
        resetState()
        resetAnalysisCapture()
        return inputAudioFormat
    }

    override fun isActive(): Boolean = sampleRate > 0 && channelCount > 0 && bytesPerSample > 0

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return

        val output = replaceOutputBuffer(size)
        val input = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)
        if (analysisEnabled && analysisMediaId != null && analysisSampleCount < analysisSamples.size && !analysisRunning.get()) {
            collectAnalysis(input.duplicate().order(ByteOrder.LITTLE_ENDIAN))
        }

        if (!enabled) {
            output.put(input)
            output.flip()
            inputBuffer.position(inputBuffer.limit())
            outputBuffer = output
            return
        }

        if (coefficientsDirty) {
            updateCoefficients()
            coefficientsDirty = false
        }

        when (encoding) {
            C.ENCODING_PCM_16BIT -> process16(input, output)
            C.ENCODING_PCM_FLOAT -> processFloat(input, output)
            else -> output.put(input)
        }
        output.flip()
        inputBuffer.position(inputBuffer.limit())
        outputBuffer = output
    }

    private fun collectAnalysis(input: ByteBuffer) {
        while (input.remaining() >= bytesPerFrame()) {
            var mono = 0f
            repeat(channelCount) {
                mono += if (encoding == C.ENCODING_PCM_FLOAT) input.float else input.short / 32768f
            }
            mono /= channelCount.coerceAtLeast(1)
            if (analysisSampleIndex % analysisFrameStride == 0L && analysisSampleCount < analysisSamples.size) {
                analysisSamples[analysisSampleCount++] = mono
            }
            analysisSampleIndex++
        }
        if (analysisSampleCount >= analysisSamples.size && analysisRunning.compareAndSet(false, true)) {
            val id = analysisMediaId
            val data = analysisSamples.copyOf(analysisSampleCount)
            ANALYSIS_EXECUTOR.execute {
                try {
                    if (!id.isNullOrBlank()) {
                        val result = AudioFeatureAnalyzer.analyzeMono(data, 11025)
                        if (result.isUsable) onFeatures(id, result)
                    }
                } finally {
                    analysisRunning.set(false)
                }
            }
        }
    }

    private fun bytesPerFrame(): Int = when (encoding) {
        C.ENCODING_PCM_FLOAT -> channelCount * 4
        C.ENCODING_PCM_16BIT -> channelCount * 2
        else -> Int.MAX_VALUE
    }

    private fun resetAnalysisCapture() {
        analysisSampleCount = 0
        analysisSampleIndex = 0L
        analysisRunning.set(false)
    }

    private fun process16(input: ByteBuffer, output: ByteBuffer) {
        when (channelCount) {
            1 -> while (input.remaining() >= 2) {
                val sample = input.short.toDouble() / 32768.0
                output.putShort((processLeft(sample).coerceIn(-1.0, 1.0) * 32768.0).toInt().toShort())
            }
            2 -> while (input.remaining() >= 4) {
                val left = input.short.toDouble() / 32768.0
                val right = input.short.toDouble() / 32768.0
                output.putShort((processLeft(left).coerceIn(-1.0, 1.0) * 32768.0).toInt().toShort())
                output.putShort((processRight(right).coerceIn(-1.0, 1.0) * 32768.0).toInt().toShort())
            }
            else -> output.put(input)
        }
    }

    private fun processFloat(input: ByteBuffer, output: ByteBuffer) {
        when (channelCount) {
            1 -> while (input.remaining() >= 4) output.putFloat(processLeft(input.float.toDouble()).toFloat())
            2 -> while (input.remaining() >= 8) {
                output.putFloat(processLeft(input.float.toDouble()).toFloat())
                output.putFloat(processRight(input.float.toDouble()).toFloat())
            }
            else -> output.put(input)
        }
    }

    private fun processLeft(x: Double): Double {
        val y = b0 * x + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L
        x2L = x1L
        x1L = x
        y2L = y1L
        y1L = y
        return y
    }

    private fun processRight(x: Double): Double {
        val y = b0 * x + b1 * x1R + b2 * x2R - a1 * y1R - a2 * y2R
        x2R = x1R
        x1R = x
        y2R = y1R
        y1R = y
        return y
    }

    private fun updateCoefficients() {
        val nyquist = (sampleRate * 0.49f).coerceAtLeast(40f)
        val frequency = cutoffFrequencyHz.coerceIn(20f, nyquist).toDouble()
        val omega = 2.0 * PI * frequency / sampleRate.toDouble()
        val cosOmega = cos(omega)
        val sinOmega = sin(omega)
        val q = 0.7071067811865476
        val alpha = sinOmega / (2.0 * q)

        when (filterType) {
            FilterType.LOW_PASS -> {
                val bb0 = (1.0 - cosOmega) / 2.0
                val bb1 = 1.0 - cosOmega
                val bb2 = bb0
                val aa0 = 1.0 + alpha
                val aa1 = -2.0 * cosOmega
                val aa2 = 1.0 - alpha
                normalize(bb0, bb1, bb2, aa0, aa1, aa2)
            }
            FilterType.HIGH_PASS -> {
                val bb0 = (1.0 + cosOmega) / 2.0
                val bb1 = -(1.0 + cosOmega)
                val bb2 = bb0
                val aa0 = 1.0 + alpha
                val aa1 = -2.0 * cosOmega
                val aa2 = 1.0 - alpha
                normalize(bb0, bb1, bb2, aa0, aa1, aa2)
            }
        }
    }

    private fun normalize(bb0: Double, bb1: Double, bb2: Double, aa0: Double, aa1: Double, aa2: Double) {
        b0 = bb0 / aa0
        b1 = bb1 / aa0
        b2 = bb2 / aa0
        a1 = aa1 / aa0
        a2 = aa2 / aa0
    }

    private fun replaceOutputBuffer(size: Int): ByteBuffer {
        if (outputBuffer.capacity() < size) {
            outputBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN)
        } else {
            outputBuffer.clear()
        }
        return outputBuffer
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val result = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return result
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === EMPTY_BUFFER

    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        inputEnded = false
        resetState()
        resetAnalysisCapture()
        coefficientsDirty = true
    }

    override fun reset() {
        flush()
        sampleRate = 0
        channelCount = 0
        encoding = C.ENCODING_INVALID
        bytesPerSample = 0
        enabled = false
        analysisEnabled = false
        analysisMediaId = null
        analysisSamples = FloatArray(0)
        cutoffFrequencyHz = 20_000f
        filterType = FilterType.LOW_PASS
    }

    private fun resetState() {
        x1L = 0.0
        x2L = 0.0
        y1L = 0.0
        y2L = 0.0
        x1R = 0.0
        x2R = 0.0
        y1R = 0.0
        y2R = 0.0
    }

    companion object {
        private val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN)
        private val ANALYSIS_EXECUTOR = Executors.newSingleThreadExecutor { r -> Thread(r, "Glossy-AutoMix-Analyzer").apply { isDaemon = true } }
    }
}
