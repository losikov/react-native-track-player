package com.doublesymmetry.kotlinaudio.models

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * The arithmetic of a remote speed control, kept apart from the service so it can be tested on the
 * JVM. The iOS twin is `PlayerCore.snap(_:to:)` / `PlayerCore.nextRate(after:in:)`.
 */
object PlaybackRates {
    /** The nearest of [rates] to [rate]; [rate] itself when [rates] is empty. */
    fun snap(rate: Float, rates: List<Float>): Float =
        rates.minByOrNull { abs(it - rate) } ?: rate

    /**
     * The first of [rates] faster than [current], wrapping to the first (slowest) after the last;
     * null when [rates] is empty. [rates] is in ascending order. A rate the list does not hold — a
     * custom speed the app set, 1.35 — moves up to the next one in the list, 1.4.
     */
    fun next(current: Float, rates: List<Float>): Float? {
        if (rates.isEmpty()) return null
        return rates.firstOrNull { it > current + EPSILON } ?: rates.first()
    }

    /** [rate] when it is one of [rates], to within float noise; null otherwise. */
    fun matching(rate: Float, rates: List<Float>): Float? = rates.firstOrNull { abs(it - rate) < EPSILON }

    private const val EPSILON = 0.001f

    /** `0.8`, `1`, `1.25`, `1.35` — the number as a person writes it, to two decimals at most. */
    fun label(rate: Float): String =
        BigDecimal(rate.toString()).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
