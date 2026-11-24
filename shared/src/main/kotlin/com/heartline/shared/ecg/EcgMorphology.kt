// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.ecg

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * Wave timing on the median beat (algorithm 4, docs/algorithms/ECG_ALGORITHM.md): QRS duration
 * and PR interval of the dominant, non-early beats, measured on one lead as a 12-lead device
 * would on its global beat, only less precisely. Wellness observations, not a diagnosis.
 */
object EcgMorphology {
    const val PRE_S = 0.40
    const val POST_S = 0.60

    /** QRS onset/offset: where the slope stays below this share of the QRS's steepest slope. */
    const val SLOPE_SHARE = 0.10

    /** P onset: where the P wave falls below this share of its peak. */
    const val P_SHARE = 0.15

    /**
     * The median beat as the long-PR model reads it: −300…+196 ms around R at 4 ms
     * steps (against the beat's median, per peak-to-peak range) and the steepest slope in each
     * 10 ms of −120…+160 ms (per range and 2 ms). All relative to the beat's own size, so the
     * wrist's smaller signal reads the same as a clinical lead I.
     */
    val FEATURES = List(125) { String.format(Locale.ROOT, "t%03d", it) } +
        List(28) { String.format(Locale.ROOT, "slope%02d", it) }

    fun features(t: DoubleArray, fs: Int): DoubleArray {
        val r = (PRE_S * fs).toInt()
        val at = { ms: Double -> t[(r + Math.round(ms * fs / 1000.0).toInt()).coerceIn(0, t.size - 1)] }
        val median = t.sorted()[t.size / 2]
        val range = (t.max() - t.min()).takeIf { it > 0 } ?: 1.0
        val slope = { from: Double ->
            // 5 samples of 2 ms; at another rate, the same span with the slope rescaled to 2 ms.
            (0 until 5).maxOf { k -> abs(at(from + 2.0 * (k + 1)) - at(from + 2.0 * k)) } / range
        }
        return DoubleArray(FEATURES.size) { i ->
            if (i < 125) (at(-300.0 + 4.0 * i) - median) / range else slope(-120.0 + 10.0 * (i - 125))
        }
    }

    data class Timing(
        val qrsMs: Double,
        /** Null when no clear P wave was found. */
        val prMs: Double?
    )

    /** Median beat of [beats] (R positions) over −[PRE_S]…+[POST_S] s, or null with fewer than 5 beats. */
    fun template(clean: FloatArray, beats: IntArray, fs: Int): DoubleArray? {
        val pre = (PRE_S * fs).toInt()
        val post = (POST_S * fs).toInt()
        val usable = beats.filter { it - pre >= 0 && it + post < clean.size }
        if (usable.size < 5) return null
        val column = DoubleArray(usable.size)
        return DoubleArray(pre + post) { k ->
            for (i in usable.indices) column[i] = clean[usable[i] - pre + k].toDouble()
            column.sort()
            column[column.size / 2]
        }
    }

    fun timing(clean: FloatArray, beats: IntArray, fs: Int, medianRrS: Double? = null): Timing? =
        template(clean, beats, fs)?.let { timing(it, fs, medianRrS) }

    /** Timing measured on a median beat [t] (from [template]). */
    fun timing(t: DoubleArray, fs: Int, medianRrS: Double? = null): Timing {
        val r = (PRE_S * fs).toInt()
        val d = DoubleArray(t.size) { if (it == 0) 0.0 else t[it] - t[it - 1] }
        val near = (0.05 * fs).toInt()
        val steepest = (r - near..r + near).maxOf { abs(d[it]) }.coerceAtLeast(1e-9)
        // A slope run below the threshold this long counts as flat (avoids notches inside the QRS).
        val hold = (0.012 * fs).toInt().coerceAtLeast(2)
        fun flat(i: Int) = (i until i + hold).all { abs(d[it.coerceIn(1, d.size - 1)]) < SLOPE_SHARE * steepest }

        var onset = r
        val earliest = r - (0.12 * fs).toInt()
        while (onset > earliest && !flat(onset - hold)) onset--
        var offset = r
        val latest = r + (0.16 * fs).toInt()
        while (offset < latest && !flat(offset)) offset++
        val qrs = (offset - onset) * 1000.0 / fs

        // P wave: the largest deflection 60–300 ms before QRS onset (capped by 0.45*RR so fast rates
        // never reach into the prior T wave), against the PR baseline.
        // Tuned on PTB-XL against PTB-XL+'s 12SL PR interval (tools/ml/ecg-eval, ECG_ALGORITHM.md).
        val baseFrom = (onset - (0.04 * fs).toInt()).coerceAtLeast(0)
        val baseline = (baseFrom until onset).map { t[it] }.sorted().let { it[it.size / 2] }
        val maxLookback = medianRrS?.let { minOf(0.30, 0.45 * it) } ?: 0.30
        val from = (onset - (maxLookback * fs).toInt()).coerceAtLeast(0)
        val to = onset - (0.06 * fs).toInt()
        if (to <= from) return Timing(qrs, null)
        val peak = (from until to).maxBy { abs(t[it] - baseline) }
        val range = t.max() - t.min()
        if (range <= 0 || abs(t[peak] - baseline) / range < RhythmClassifier.P_WAVE_PRESENT_RATIO) return Timing(qrs, null)
        val before = ((peak - (0.12 * fs).toInt()).coerceAtLeast(0) until (peak - (0.08 * fs).toInt()).coerceAtLeast(1)).map { t[it] }
        val tp = before.sorted().let { it[it.size / 2] }
        val height = abs(t[peak] - tp)
        var pOnset = peak
        while (pOnset > 0 && abs(t[pOnset - 1] - tp) > P_SHARE * height) pOnset--
        return Timing(qrs, (onset - pOnset) * 1000.0 / fs)
    }
}

/**
 * Atrial activity between the beats (algorithm 4): the dominant beats' median QRS–T is subtracted
 * from each of them and other beats are blanked, leaving the f waves of AFib, the F waves of
 * flutter or a flat line. Its spectrum over 3–10 Hz gives the dominant atrial frequency, how
 * concentrated the power is around it (flutter's regular F waves: one sharp peak; AFib: spread) and
 * how strong it is against the whole ECG.
 */
object AtrialActivity {
    const val LO_HZ = 3.0
    const val HI_HZ = 10.0
    const val STEP_HZ = 0.25

    data class Spectrum(val peakHz: Double, val concentration: Double, val ratio: Double)

    val NONE = Spectrum(0.0, 0.0, 0.0)

    fun of(clean: FloatArray, peaks: IntArray, dominant: BooleanArray, fs: Int): Spectrum {
        val pre = (0.10 * fs).toInt()
        val post = (0.45 * fs).toInt()
        val inside = peaks.indices.filter { dominant[it] && peaks[it] - pre >= 0 && peaks[it] + post < clean.size }.toSet()
        if (inside.size < 5) return NONE
        val members = inside.toList()
        val column = DoubleArray(members.size)
        val template = DoubleArray(pre + post) { k ->
            for (i in members.indices) column[i] = clean[peaks[members[i]] - pre + k].toDouble()
            column.sort()
            column[column.size / 2]
        }
        val residual = clean.copyOf()
        for (i in peaks.indices) {
            val p = peaks[i]
            val lo = if (i > 0) maxOf(p - pre, (peaks[i - 1] + p) / 2) else maxOf(0, p - pre)
            val hi = if (i + 1 < peaks.size) minOf(p + post, (p + peaks[i + 1]) / 2) else minOf(clean.size, p + post)
            for (j in lo until hi) {
                val k = j - (p - pre)
                if (k !in template.indices) continue
                residual[j] = if (i in inside) (clean[j] - template[k]).toFloat() else 0f
            }
        }
        val win = (4 * fs).coerceAtMost(residual.size)
        if (win < 2) return NONE
        val step = maxOf(1, win / 2)
        val hann = DoubleArray(win) { m -> 0.5 * (1.0 - cos(2 * PI * m / (win - 1))) }
        val hannNorm = hann.sumOf { it * it }.coerceAtLeast(1e-12)
        val starts = mutableListOf<Int>()
        var s = 0
        while (s + win <= residual.size) {
            starts += s
            s += step
        }
        if (starts.isEmpty()) starts += 0
        val segScale = residual.size.toDouble() / hannNorm / starts.size
        val windowed = FloatArray(win)
        val freqs = mutableListOf<Double>().apply {
            var f = LO_HZ
            while (f <= HI_HZ + 1e-9) {
                add(f)
                f += STEP_HZ
            }
        }
        val acc = DoubleArray(freqs.size)
        for (start in starts) {
            for (m in 0 until win) windowed[m] = (residual[start + m] * hann[m]).toFloat()
            for (fi in freqs.indices) acc[fi] += goertzel(windowed, fs, freqs[fi])
        }
        val powers = freqs.indices.map { fi -> freqs[fi] to acc[fi] * segScale }
        val total = powers.sumOf { it.second }
        if (total <= 0) return NONE
        val (peakHz, _) = powers.maxBy { it.second }
        val around = powers.filter { abs(it.first - peakHz) <= 0.5 + 1e-9 }.sumOf { it.second }
        val ecg = EcgQuality.bandPower(clean, fs, 1.0, 40.0).coerceAtLeast(1e-12)
        // bandPower sums 0.5 Hz bins; scale the 0.25 Hz residual bins to the same density.
        return Spectrum(peakHz, around / total, total / 2 / ecg)
    }

    private fun goertzel(x: FloatArray, fs: Int, f: Double): Double {
        val coeff = 2 * cos(2 * PI * f / fs)
        var s1 = 0.0
        var s2 = 0.0
        for (v in x) {
            val s0 = v + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }
}
