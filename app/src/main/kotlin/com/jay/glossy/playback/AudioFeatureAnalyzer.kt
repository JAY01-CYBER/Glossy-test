package com.jay.glossy.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure JVM audio-feature estimator for AutoMix. It deliberately runs off the audio thread.
 * BPM is estimated from an onset/energy envelope; key is estimated from FFT chroma against
 * Krumhansl-Schmuckler major/minor profiles. Results are intentionally nullable: when the signal
 * is too quiet/ambiguous, AutoMix falls back to the configured crossfade instead of guessing.
 */
object AudioFeatureAnalyzer {
    private const val ANALYSIS_RATE = 11025
    private const val MAX_SECONDS = 8
    private const val FFT_SIZE = 2048
    private const val HOP = 512

    private val majorProfile = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val minorProfile = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
    private val keyNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    fun analyze(input: FloatArray, sampleRate: Int): AudioFeatures {
        if (input.size < sampleRate * 4 || sampleRate <= 0) return AudioFeatures(null, null, null)
        val mono = resampleMono(input, sampleRate)
        return analyzeMono(mono, ANALYSIS_RATE)
    }

    fun analyzeMono(samples: FloatArray, sampleRate: Int): AudioFeatures {
        if (samples.size < sampleRate * 4 || sampleRate <= 0) return AudioFeatures(null, null, null)
        val normalized = if (sampleRate == ANALYSIS_RATE) samples else resampleMono(samples, sampleRate)
        val bpm = estimateBpm(normalized)
        val key = estimateKey(normalized)
        return AudioFeatures(bpm, key?.first, key?.second)
    }

    private fun resampleMono(input: FloatArray, sampleRate: Int): FloatArray {
        val sourceFrames = input.size / 2
        val stride = max(1, sampleRate / ANALYSIS_RATE)
        val outFrames = min(sourceFrames, ANALYSIS_RATE * MAX_SECONDS)
        val out = FloatArray(outFrames)
        var src = 0
        var dst = 0
        while (dst < out.size && src < sourceFrames) {
            val i = src * 2
            out[dst++] = ((input[i] + input[i + 1]) * 0.5f).coerceIn(-1f, 1f)
            src += stride
        }
        return if (dst == out.size) out else out.copyOf(dst)
    }

    private fun estimateBpm(samples: FloatArray): Int? {
        val frame = 256
        val hop = 128
        val count = (samples.size - frame) / hop
        if (count < 40) return null
        val energy = DoubleArray(count)
        for (i in 0 until count) {
            var sum = 0.0
            val start = i * hop
            for (j in 0 until frame) {
                val x = samples[start + j].toDouble()
                sum += x * x
            }
            energy[i] = sum / frame
        }
        val onset = DoubleArray(count - 1)
        for (i in onset.indices) onset[i] = max(0.0, energy[i + 1] - energy[i])
        val mean = onset.average()
        for (i in onset.indices) onset[i] -= mean

        val frameRate = ANALYSIS_RATE.toDouble() / hop
        var bestBpm = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (bpm in 60..180) {
            val lag = (frameRate * 60.0 / bpm).roundToIntSafe()
            if (lag < 1 || lag >= onset.size / 2) continue
            var score = 0.0
            var weight = 0.0
            var i = lag
            while (i < onset.size) {
                val w = 1.0 - (i.toDouble() / onset.size) * 0.35
                score += onset[i] * onset[i - lag] * w
                weight += w
                i += 1
            }
            score /= max(1.0, weight)
            if (score > bestScore) { bestScore = score; bestBpm = bpm }
        }
        if (bestBpm == 0 || bestScore <= 0.0) return null
        // Beat trackers commonly lock to a half-time pulse. Prefer the musical 80-180 BPM
        // interpretation when the doubled candidate is available; the AutoMix layer still
        // normalizes genuine halftime/doubletime relationships before applying any ramp.
        if (bestBpm < 80 && bestBpm * 2 <= 180) return bestBpm * 2
        return bestBpm
    }

    private fun estimateKey(samples: FloatArray): Pair<String, String>? {
        if (samples.size < FFT_SIZE * 4) return null
        val chroma = DoubleArray(12)
        val window = DoubleArray(FFT_SIZE)
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        var windows = 0
        var start = 0
        while (start + FFT_SIZE <= samples.size && windows < 180) {
            for (i in 0 until FFT_SIZE) {
                window[i] = samples[start + i].toDouble() * (0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1)))
                re[i] = window[i]
                im[i] = 0.0
            }
            fft(re, im)
            for (bin in 2 until FFT_SIZE / 2) {
                val freq = bin.toDouble() * ANALYSIS_RATE / FFT_SIZE
                if (freq !in 65.0..1800.0) continue
                val magnitude = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
                if (magnitude <= 1e-8) continue
                val midi = (69.0 + 12.0 * log2(freq / 440.0)).roundToIntSafe()
                val pc = ((midi % 12) + 12) % 12
                val weight = 1.0 / (1.0 + abs(log2(freq / 440.0)) * 0.08)
                chroma[pc] += magnitude * weight
            }
            windows++
            start += HOP
        }
        if (windows < 10) return null
        val norm = sqrt(chroma.sumOf { it * it })
        if (norm <= 1e-9) return null
        for (i in chroma.indices) chroma[i] /= norm

        var bestScore = Double.NEGATIVE_INFINITY
        var bestKey = 0
        var bestMinor = false
        for (root in 0 until 12) {
            val major = profileScore(chroma, majorProfile, root)
            val minor = profileScore(chroma, minorProfile, root)
            if (major > bestScore) { bestScore = major; bestKey = root; bestMinor = false }
            if (minor > bestScore) { bestScore = minor; bestKey = root; bestMinor = true }
        }
        if (bestScore < 0.25) return null
        return keyNames[bestKey] to if (bestMinor) "MINOR" else "MAJOR"
    }

    private fun profileScore(chroma: DoubleArray, profile: DoubleArray, root: Int): Double {
        var sum = 0.0
        for (i in 0 until 12) {
            sum += chroma[(i + root) % 12] * profile[i]
        }
        return sum
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n ushr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit ushr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half = len ushr 1
            val angle = -2.0 * PI / len
            val wr0 = cos(angle)
            val wi0 = sin(angle)
            var base = 0
            while (base < n) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until half) {
                    val u = base + k
                    val v = u + half
                    val vr = re[v] * wr - im[v] * wi
                    val vi = re[v] * wi + im[v] * wr
                    val ur = re[u]
                    val ui = im[u]
                    re[u] = ur + vr; im[u] = ui + vi
                    re[v] = ur - vr; im[v] = ui - vi
                    val nwr = wr * wr0 - wi * wi0
                    wi = wr * wi0 + wi * wr0
                    wr = nwr
                }
                base += len
            }
            len = len shl 1
        }
    }

    private fun Double.roundToIntSafe(): Int = kotlin.math.round(this).toInt()
}
