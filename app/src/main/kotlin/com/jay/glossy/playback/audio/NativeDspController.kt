package com.jay.glossy.playback.audio

import com.jay.glossy.eq.soundfx.SoundFxSettings
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide snapshot of Glossy's native DSP settings.
 * Audio threads only read an immutable snapshot; DataStore/UI never touches the audio callback.
 */
object NativeDspController {
    data class Snapshot(
        val enabled: Boolean = false,
        val bandsMb: List<Int> = emptyList(),
        val bassBoostEnabled: Boolean = false,
        val bassBoostStrength: Int = 0,
        val virtualizerEnabled: Boolean = false,
        val virtualizerStrength: Int = 0,
        val outputGainEnabled: Boolean = false,
        val outputGainMb: Int = 0,
        val autoHeadroomEnabled: Boolean = false,
        val bypass: Boolean = false,
    )

    private val version = AtomicLong(0)
    private val snapshot = AtomicReference(Snapshot())

    fun apply(settings: SoundFxSettings) {
        snapshot.set(
            Snapshot(
                enabled = settings.enabled,
                bandsMb = settings.bandLevelsMb.toList(),
                bassBoostEnabled = settings.bassBoostEnabled,
                bassBoostStrength = settings.bassBoostStrength,
                virtualizerEnabled = settings.virtualizerEnabled,
                virtualizerStrength = settings.virtualizerStrength,
                outputGainEnabled = settings.outputGainEnabled,
                outputGainMb = settings.effectiveOutputGainMb(null),
                autoHeadroomEnabled = settings.autoHeadroomEnabled,
                bypass = settings.bypass,
            ),
        )
        version.incrementAndGet()
    }

    fun snapshot(): Pair<Long, Snapshot> = version.get() to snapshot.get()
}
