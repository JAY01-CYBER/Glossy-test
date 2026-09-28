/*
 * Sound FX models ported from ArchiveTune (2026) © Rukamori — github.com/rukamori (GPL-3.0).
 * Adapted for Glossy's system audio effect equalizer.
 */

package com.jay.glossy.eq.soundfx

import androidx.datastore.preferences.core.Preferences
import com.jay.glossy.constants.SoundFxBandLevelsMbKey
import com.jay.glossy.constants.SoundFxBassBoostEnabledKey
import com.jay.glossy.constants.SoundFxBassBoostStrengthKey
import com.jay.glossy.constants.SoundFxControlModeKey
import com.jay.glossy.constants.SoundFxEnabledKey
import com.jay.glossy.constants.SoundFxOutputGainEnabledKey
import com.jay.glossy.constants.SoundFxOutputGainMbKey
import com.jay.glossy.constants.SoundFxAutoHeadroomKey
import com.jay.glossy.constants.SoundFxBypassKey
import com.jay.glossy.constants.SoundFxVirtualizerEnabledKey
import com.jay.glossy.constants.SoundFxVirtualizerStrengthKey
import com.jay.glossy.constants.SoundFxSpatialEnabledKey
import com.jay.glossy.constants.SoundFxSpatialStrengthKey
import com.jay.glossy.constants.SoundFxCrossfeedEnabledKey
import com.jay.glossy.constants.SoundFxCrossfeedStrengthKey
import com.jay.glossy.constants.SoundFxClarityEnabledKey
import com.jay.glossy.constants.SoundFxClarityStrengthKey
import com.jay.glossy.constants.SoundFxCompressorEnabledKey
import com.jay.glossy.constants.SoundFxCompressorStrengthKey
import com.jay.glossy.constants.SoundFxLimiterEnabledKey
import com.jay.glossy.constants.SoundFxLimiterStrengthKey
import com.jay.glossy.constants.SoundFxReverbEnabledKey
import com.jay.glossy.constants.SoundFxReverbMixKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.ceil
import kotlin.math.floor

enum class SoundFxControlMode(
    val storageValue: String,
) {
    BASIC("basic"),
    ADVANCED("advanced"),
    ;

    companion object {
        fun fromStorage(value: String?): SoundFxControlMode = entries.firstOrNull { it.storageValue == value } ?: BASIC
    }
}

enum class SoundFxTone {
    BASS,
    MIDRANGE,
    TREBLE,
}

/**
 * What the device's system equalizer supports. Populated once the audio effect
 * is attached to a live audio session (i.e. playback has started).
 */
data class SoundFxCapabilities(
    val bandCount: Int,
    val bandCenterFreqHz: List<Int>,
    val minBandLevelMb: Int,
    val maxBandLevelMb: Int,
    val presetNames: List<String>,
)

data class SoundFxSettings(
    val enabled: Boolean = false,
    val bandLevelsMb: List<Int> = emptyList(),
    val outputGainEnabled: Boolean = false,
    val outputGainMb: Int = 0,
    val bassBoostEnabled: Boolean = false,
    val bassBoostStrength: Int = 0,
    val virtualizerEnabled: Boolean = false,
    val virtualizerStrength: Int = 0,
    val spatialEnabled: Boolean = false,
    val spatialStrength: Int = 0,
    val crossfeedEnabled: Boolean = false,
    val crossfeedStrength: Int = 0,
    val reverbEnabled: Boolean = false,
    val reverbMix: Int = 0,
    val clarityEnabled: Boolean = false,
    val clarityStrength: Int = 0,
    val compressorEnabled: Boolean = true,
    val compressorStrength: Int = 350,
    val limiterEnabled: Boolean = true,
    val limiterStrength: Int = 650,
    val autoHeadroomEnabled: Boolean = false,
    val bypass: Boolean = false,
) {
    /**
     * Output gain actually sent to [LoudnessEnhancer]. When auto headroom is on,
     * the positive band and bass boost is subtracted so the boosted signal no
     * longer drives the output into hard clipping.
     */
    fun effectiveOutputGainMb(capabilities: SoundFxCapabilities?): Int {
        if (!autoHeadroomEnabled) return outputGainMb
        val maxBandBoostMb = bandLevelsMb.maxOrNull()?.coerceAtLeast(0) ?: 0
        val bassReserveMb = if (bassBoostEnabled) bassBoostStrength.coerceIn(0, MAX_EFFECT_STRENGTH) else 0
        return (outputGainMb - maxBandBoostMb - bassReserveMb).coerceIn(MIN_OUTPUT_GAIN_MB, MAX_OUTPUT_GAIN_MB)
    }

    companion object {
        const val MIN_OUTPUT_GAIN_MB = -1500
        const val MAX_OUTPUT_GAIN_MB = 1500
        const val MAX_EFFECT_STRENGTH = 1000

        fun fromPreferences(prefs: Preferences): SoundFxSettings =
            SoundFxSettings(
                enabled = prefs[SoundFxEnabledKey] ?: false,
                bandLevelsMb = decodeBandLevels(prefs[SoundFxBandLevelsMbKey]),
                outputGainEnabled = prefs[SoundFxOutputGainEnabledKey] ?: false,
                outputGainMb = (prefs[SoundFxOutputGainMbKey] ?: 0).coerceIn(MIN_OUTPUT_GAIN_MB, MAX_OUTPUT_GAIN_MB),
                bassBoostEnabled = prefs[SoundFxBassBoostEnabledKey] ?: false,
                bassBoostStrength = (prefs[SoundFxBassBoostStrengthKey] ?: 0).coerceIn(0, MAX_EFFECT_STRENGTH),
                virtualizerEnabled = prefs[SoundFxVirtualizerEnabledKey] ?: false,
                virtualizerStrength = (prefs[SoundFxVirtualizerStrengthKey] ?: 0).coerceIn(0, MAX_EFFECT_STRENGTH),
                spatialEnabled = prefs[SoundFxSpatialEnabledKey] ?: false,
                spatialStrength = (prefs[SoundFxSpatialStrengthKey] ?: 0).coerceIn(0, MAX_EFFECT_STRENGTH),
                crossfeedEnabled = prefs[SoundFxCrossfeedEnabledKey] ?: false,
                crossfeedStrength = (prefs[SoundFxCrossfeedStrengthKey] ?: 0).coerceIn(0, MAX_EFFECT_STRENGTH),
                reverbEnabled = prefs[SoundFxReverbEnabledKey] ?: false,
                reverbMix = (prefs[SoundFxReverbMixKey] ?: 0).coerceIn(0, 350),
                clarityEnabled = prefs[SoundFxClarityEnabledKey] ?: false,
                clarityStrength = (prefs[SoundFxClarityStrengthKey] ?: 0).coerceIn(0, MAX_EFFECT_STRENGTH),
                compressorEnabled = prefs[SoundFxCompressorEnabledKey] ?: true,
                compressorStrength = (prefs[SoundFxCompressorStrengthKey] ?: 350).coerceIn(0, MAX_EFFECT_STRENGTH),
                limiterEnabled = prefs[SoundFxLimiterEnabledKey] ?: true,
                limiterStrength = (prefs[SoundFxLimiterStrengthKey] ?: 650).coerceIn(0, MAX_EFFECT_STRENGTH),
                autoHeadroomEnabled = prefs[SoundFxAutoHeadroomKey] ?: false,
                bypass = prefs[SoundFxBypassKey] ?: false,
            )
    }
}

/**
 * A saved sound fx setup. Field names match ArchiveTune's exported profile JSON
 * so profiles are interchangeable between the two apps.
 */
@Serializable
data class SoundFxProfile(
    val id: String,
    val name: String,
    val bandCenterFreqHz: List<Int> = emptyList(),
    val bandLevelsMb: List<Int> = emptyList(),
    val outputGainMb: Int = 0,
    val outputGainEnabled: Boolean? = null,
    val bassBoostStrength: Int = 0,
    val bassBoostEnabled: Boolean? = null,
    val virtualizerStrength: Int = 0,
    val virtualizerEnabled: Boolean? = null,
    val spatialStrength: Int = 0,
    val spatialEnabled: Boolean? = null,
    val crossfeedStrength: Int = 0,
    val crossfeedEnabled: Boolean? = null,
    val reverbMix: Int = 0,
    val reverbEnabled: Boolean? = null,
    val clarityStrength: Int = 0,
    val clarityEnabled: Boolean? = null,
    val compressorStrength: Int = 350,
    val compressorEnabled: Boolean? = null,
    val limiterStrength: Int = 650,
    val limiterEnabled: Boolean? = null,
    val autoHeadroomEnabled: Boolean = false,
)

@Serializable
data class SoundFxProfilesPayload(
    val profiles: List<SoundFxProfile>,
)

val SoundFxJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

data class SoundFxConfiguration(
    val controlMode: SoundFxControlMode,
    val selectedProfileId: String,
    val settings: SoundFxSettings,
    val capabilities: SoundFxCapabilities?,
    val profiles: List<SoundFxProfile>,
)

/**
 * Resample stored band levels to the device's actual band count using linear
 * interpolation (ported from ArchiveTune).
 */
internal fun resampleLevels(
    levelsMb: List<Int>,
    targetCount: Int,
): List<Int> {
    if (targetCount <= 0) return emptyList()
    if (levelsMb.isEmpty()) return List(targetCount) { 0 }
    if (levelsMb.size == targetCount) return levelsMb
    if (targetCount == 1) return listOf(levelsMb.average().toInt())
    val lastIndex = levelsMb.lastIndex.toFloat().coerceAtLeast(1f)
    return List(targetCount) { index ->
        val position = index * lastIndex / (targetCount - 1)
        val lower = floor(position).toInt().coerceIn(0, levelsMb.lastIndex)
        val upper = ceil(position).toInt().coerceIn(0, levelsMb.lastIndex)
        (levelsMb[lower] + ((levelsMb[upper] - levelsMb[lower]) * (position - lower))).toInt()
    }
}

/**
 * Map a tone (bass / midrange / treble) to the equalizer band indices it
 * affects, preferring the device's real center frequencies over a positional
 * split (ported from ArchiveTune).
 */
internal fun soundFxToneIndices(
    tone: SoundFxTone,
    frequenciesHz: List<Int>,
    bandCount: Int,
): List<Int> {
    if (bandCount <= 0) return emptyList()
    val frequencyMatches =
        frequenciesHz
            .takeIf { it.size == bandCount && it.any { frequency -> frequency > 0 } }
            ?.indices
            ?.filter { index ->
                when (tone) {
                    SoundFxTone.BASS -> frequenciesHz[index] <= 250
                    SoundFxTone.MIDRANGE -> frequenciesHz[index] in 251..4_000
                    SoundFxTone.TREBLE -> frequenciesHz[index] > 4_000
                }
            }.orEmpty()
    if (frequencyMatches.isNotEmpty()) return frequencyMatches

    val firstBoundary = ceil(bandCount / 3.0).toInt()
    val secondBoundary = ceil(bandCount * 2 / 3.0).toInt()
    val range =
        when (tone) {
            SoundFxTone.BASS -> 0 until firstBoundary
            SoundFxTone.MIDRANGE -> firstBoundary until secondBoundary
            SoundFxTone.TREBLE -> secondBoundary until bandCount
        }.toList()
    if (range.isNotEmpty()) return range
    return listOf(
        when (tone) {
            SoundFxTone.BASS -> 0
            SoundFxTone.MIDRANGE -> (bandCount - 1) / 2
            SoundFxTone.TREBLE -> bandCount - 1
        },
    )
}

/**
 * Reasonable 5-band layout used by the UI before the device's real
 * capabilities become available (they load as soon as playback starts).
 */
val GLOSSY_31_BAND_FREQUENCIES_HZ: List<Int> = listOf(
    20, 25, 31, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500,
    630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000,
    10000, 12500, 16000, 20000,
)

fun glossy31BandCapabilities(presetNames: List<String> = emptyList()): SoundFxCapabilities =
    SoundFxCapabilities(
        bandCount = GLOSSY_31_BAND_FREQUENCIES_HZ.size,
        bandCenterFreqHz = GLOSSY_31_BAND_FREQUENCIES_HZ,
        minBandLevelMb = -1500,
        maxBandLevelMb = 1500,
        presetNames = presetNames,
    )



/**
 * Glossy built-in Sound FX pack.
 *
 * These are intentionally device-agnostic listening presets rather than
 * headphone correction profiles. Device-specific AutoEQ/RTINGS profiles should
 * be imported separately when the exact transducer is known.
 */
val GLOSSY_BUILT_IN_SOUND_FX: List<SoundFxProfile> = emptyList()

private fun glossyPreset(
    id: String,
    name: String,
    bassDb: Double,
    midDb: Double,
    trebleDb: Double,
    presenceDb: Double,
    presenceHz: Double = 2800.0,
    bassBoost: Int = 0,
    virtualizer: Int = 0,
    spatial: Int = 0,
    crossfeed: Int = 0,
    reverb: Int = 0,
): SoundFxProfile =
    SoundFxProfile(
        id = "builtin_$id",
        name = name,
        bandCenterFreqHz = GLOSSY_31_BAND_FREQUENCIES_HZ,
        bandLevelsMb = glossyCurve(bassDb, midDb, trebleDb, presenceDb, presenceHz),
        bassBoostStrength = bassBoost,
        bassBoostEnabled = bassBoost > 0,
        virtualizerStrength = virtualizer,
        virtualizerEnabled = virtualizer > 0,
        spatialStrength = spatial,
        spatialEnabled = spatial > 0,
        crossfeedStrength = crossfeed,
        crossfeedEnabled = crossfeed > 0,
        reverbMix = reverb,
        reverbEnabled = reverb > 0,
        autoHeadroomEnabled = true,
    )

private fun glossyCurve(
    bassDb: Double,
    midDb: Double,
    trebleDb: Double,
    presenceDb: Double,
    presenceHz: Double,
): List<Int> {
    fun gaussian(logHz: Double, center: Double, width: Double): Double {
        val x = (logHz - kotlin.math.log10(center)) / width
        return kotlin.math.exp(-0.5 * x * x)
    }
    return GLOSSY_31_BAND_FREQUENCIES_HZ.map { hz ->
        val logHz = kotlin.math.log10(hz.toDouble())
        val bass = bassDb * (1.0 / (1.0 + kotlin.math.exp((logHz - kotlin.math.log10(180.0)) * 7.0)))
        val treble = trebleDb * (1.0 / (1.0 + kotlin.math.exp(-(logHz - kotlin.math.log10(5000.0)) * 7.0)))
        val mid = midDb * gaussian(logHz, 900.0, 0.42)
        val presence = presenceDb * gaussian(logHz, presenceHz, 0.16)
        ((bass + mid + treble + presence) * 1000.0).toInt().coerceIn(-1500, 1500)
    }
}


fun fallbackSoundFxCapabilities(): SoundFxCapabilities =
    SoundFxCapabilities(
        bandCount = 5,
        bandCenterFreqHz = listOf(60, 230, 910, 3_600, 14_000),
        minBandLevelMb = -1500,
        maxBandLevelMb = 1500,
        presetNames = emptyList(),
    )

fun decodeBandLevels(raw: String?): List<Int> =
    raw
        ?.takeIf(String::isNotBlank)
        ?.let { runCatching { SoundFxJson.decodeFromString<List<Int>>(it) }.getOrNull() }
        .orEmpty()
