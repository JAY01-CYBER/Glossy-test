/**
 * Pure crossfade gain curves. The returned values are linear amplitude gains in [0, 1].
 * Keeping this class free of Android/ExoPlayer dependencies makes the transition math easy
 * to reason about and safe to reuse from either audio-engine mode.
 */
package com.jay.glossy.playback

import com.jay.glossy.constants.CrossfadeStyle
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class CrossfadeGains(
    val fadeIn: Float,
    val fadeOut: Float,
)

object CrossfadeCurve {
    private const val HALF_PI = 1.5707963267948966

    fun gains(style: CrossfadeStyle, progress: Float): CrossfadeGains {
        val p = progress.coerceIn(0f, 1f)

        return when (style) {
            CrossfadeStyle.LINEAR -> CrossfadeGains(
                fadeIn = p,
                fadeOut = 1f - p,
            )

            // This is the same quadratic envelope Glossy used before styles were added,
            // preserving the existing default sound while making it selectable.
            CrossfadeStyle.SMOOTH -> {
                // Preserve Glossy's previous default envelope: quadratic ease-out on the
                // incoming track and the complementary ease-in on the outgoing track.
                val fadeIn = 1f - (1f - p).pow(2f)
                CrossfadeGains(
                    fadeIn = fadeIn,
                    fadeOut = 1f - fadeIn,
                )
            }

            CrossfadeStyle.EQUAL_POWER -> when (p) {
                0f -> CrossfadeGains(fadeIn = 0f, fadeOut = 1f)
                1f -> CrossfadeGains(fadeIn = 1f, fadeOut = 0f)
                else -> CrossfadeGains(
                    fadeIn = sin(p * HALF_PI).toFloat(),
                    fadeOut = cos(p * HALF_PI).toFloat(),
                )
            }

            CrossfadeStyle.FAST_IN -> {
                val fadeIn = sqrt(p)
                CrossfadeGains(
                    fadeIn = fadeIn,
                    fadeOut = 1f - fadeIn,
                )
            }

            CrossfadeStyle.SLOW_IN -> {
                val fadeIn = p.pow(2f)
                CrossfadeGains(
                    fadeIn = fadeIn,
                    fadeOut = 1f - fadeIn,
                )
            }

            CrossfadeStyle.FAST_OUT -> {
                val fadeOut = (1f - p).pow(3f)
                CrossfadeGains(
                    fadeIn = 1f - fadeOut,
                    fadeOut = fadeOut,
                )
            }

            CrossfadeStyle.SLOW_OUT -> {
                val fadeOut = sqrt(1f - p)
                CrossfadeGains(
                    fadeIn = 1f - fadeOut,
                    fadeOut = fadeOut,
                )
            }

            CrossfadeStyle.DJ_PUNCH -> {
                // Early next-track presence + decisive old-track exit. Both curves start/end
                // exactly at 0/1, so there is no discontinuity at the transition boundaries.
                CrossfadeGains(
                    fadeIn = p.pow(0.35f),
                    fadeOut = (1f - p).pow(1.8f),
                )
            }
        }.let { gains ->
            CrossfadeGains(
                fadeIn = gains.fadeIn.coerceIn(0f, 1f),
                fadeOut = gains.fadeOut.coerceIn(0f, 1f),
            )
        }
    }
}
