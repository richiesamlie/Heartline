// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.irn

import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HrSample
import com.heartline.shared.hr.IrnSensitivity
import com.heartline.shared.hr.RrFeatures
import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
data class WindowResult(val startMs: Long, val irregular: Boolean, val meanBpm: Int)

/**
 * Persisted detector state (a few dozen windows at most). [regularEcgAtMs]: an ECG taken soon after
 * the last alert looked regular, so the next alert waits longer and needs stronger evidence.
 */
@Serializable
data class IrnState(
    val windows: List<WindowResult> = emptyList(),
    val lastAlertMs: Long? = null,
    val regularEcgAtMs: Long? = null,
    /** The rule the [windows] were judged by ([IrnThresholds.RULE]); older ones are dropped. */
    val rule: Int = 1
) {
    /** Records an ECG result: a regular one within [IrregularRhythmDetector.ECG_FOLLOW_UP_MS] counts; an irregular one clears suppression. */
    fun withEcg(regular: Boolean, atMs: Long): IrnState {
        if (!regular) return copy(regularEcgAtMs = null)
        val alert = lastAlertMs ?: return this
        if (atMs < alert || atMs - alert > IrregularRhythmDetector.ECG_FOLLOW_UP_MS) return this
        return copy(regularEcgAtMs = atMs)
    }
}

/**
 * Irregularity thresholds for beat-to-beat intervals from the wrist (PPG) sensor
 * (docs/algorithms/HEART_MONITORING.md section 5). Irregularly irregular means all of: large
 * successive changes (nRMSSD), spread-out intervals (entropy), a random up-and-down pattern
 * (turning points, about 2/3 for random) and no correlation between neighbours ([maxLag1]).
 */
data class IrnThresholds(
    val minNRmssd: Double,
    val minEntropy: Double,
    val turningPoint: ClosedFloatingPointRange<Double>,
    val minBeats: Int,
    /** Drop isolated premature beats (and the pause after them) before judging. */
    val dropIsolatedEctopics: Boolean,
    /**
     * Neighbouring intervals correlated more than this are a smooth (breathing) rhythm, not an
     * irregular one: a real nap with high HRV passed every other test (nRMSSD 0.10–0.13) at +0.3–0.6.
     */
    val maxLag1: Double = 0.25
) {
    fun irregular(f: RrFeatures) = f.count >= minBeats &&
        f.nRmssd > minNRmssd &&
        f.shannonEntropy > minEntropy &&
        f.turningPointRatio in turningPoint &&
        kotlin.math.abs(f.lag1) < maxLag1

    companion object {
        /** Wrist intervals are noisier than ECG ones, and isolated extra beats are common and harmless. */
        val STANDARD = IrnThresholds(0.12, 0.65, 0.55..0.85, minBeats = 50, dropIsolatedEctopics = true)

        /**
         * Looser, for those who choose high sensitivity. Turning points as Dash et al. 2009 (0.54–0.77):
         * the earlier 0.45–0.95 let a smooth sinus rhythm in sleep (0.46–0.48) through.
         */
        val HIGH = IrnThresholds(0.10, 0.55, 0.54..0.77, minBeats = 40, dropIsolatedEctopics = false)

        /** Bumped when the rule changes: windows judged by an older rule are dropped (IrnState.rule). */
        const val RULE = 3

        fun of(sensitivity: IrnSensitivity) = if (sensitivity == IrnSensitivity.HIGH) HIGH else STANDARD
    }
}

/**
 * Whether a window of wrist samples is good enough to judge the rhythm at all. Off-wrist noise,
 * weak signal and arm movement make random intervals that look irregular, so a window is only
 * read when the watch was worn, still, and its intervals form a believable, gap-free beat series.
 */
object IbiWindowQuality {
    sealed interface Result {
        data class Readable(val ibisMs: List<Int>) : Result

        data class Unreadable(val reason: String) : Result
    }

    fun assess(samples: List<HrSample>): Result {
        if (samples.isEmpty()) return Result.Unreadable("empty")
        if (samples.any { !it.onBody }) return Result.Unreadable("off body")
        if (samples.any { it.moving }) return Result.Unreadable("moving")
        if (samples.count { !it.reliable } > samples.size * MAX_UNRELIABLE_SAMPLES) return Result.Unreadable("weak signal")
        val raw = samples.filter { it.reliable }.flatMap { it.ibiMs }
        val ibis = raw.filter { it in 300..2000 }
        val rejected = samples.sumOf { it.rejectedIbis } + (raw.size - ibis.size) + samples.filter { !it.reliable }.sumOf { it.ibiMs.size }
        if (ibis.isEmpty()) return Result.Unreadable("no beats")
        if (rejected > (ibis.size + rejected) * MAX_REJECTED) return Result.Unreadable("rejected $rejected of ${ibis.size + rejected}")
        val spanMs = samples.maxOf { it.tsMs } - samples.minOf { it.tsMs } + 1_000
        if (spanMs <= 0L) return Result.Unreadable("invalid timestamps")
        val coverage = ibis.sum().toDouble() / spanMs
        if (coverage !in MIN_COVERAGE..MAX_COVERAGE) return Result.Unreadable("coverage ${String.format(Locale.ROOT, "%.2f", coverage)}")
        val ibiBpm = 60_000.0 / ibis.average()
        if (ibiBpm !in 40.0..150.0) return Result.Unreadable("rate ${ibiBpm.toInt()}")
        val reported = samples.filter { it.reliable && it.bpm > 0 }.map { it.bpm }.sorted()
        if (reported.isNotEmpty()) {
            val median = reported[reported.size / 2].toDouble()
            if (kotlin.math.abs(ibiBpm - median) >
                median * MAX_RATE_MISMATCH
            ) {
                return Result.Unreadable("rate mismatch ${ibiBpm.toInt()}/${median.toInt()}")
            }
        }
        return Result.Readable(ibis)
    }

    /**
     * Removes short runs (one or two beats) more than 20 % away from the local median that sit
     * between steady beats: premature beats and their pauses. In an irregularly irregular rhythm
     * the neighbours are not steady, so those beats stay.
     */
    fun dropIsolatedEctopics(ibis: List<Int>): List<Int> {
        if (ibis.size < 8) return ibis
        val outlier = ibis.indices.map { i ->
            val window = ibis.subList(maxOf(0, i - 5), minOf(ibis.size, i + 6)).sorted()
            val median = window[window.size / 2]
            kotlin.math.abs(ibis[i] - median) > median * 0.2
        }
        val drop = BooleanArray(ibis.size)
        var i = 0
        while (i < ibis.size) {
            if (!outlier[i]) {
                i++
                continue
            }
            var end = i
            while (end + 1 < ibis.size && outlier[end + 1]) end++
            val steadyBefore = (1..2).all { k -> i - k < 0 || !outlier[i - k] }
            val steadyAfter = (1..2).all { k -> end + k >= ibis.size || !outlier[end + k] }
            if (end - i < 2 && steadyBefore && steadyAfter) (i..end).forEach { drop[it] = true }
            i = end + 1
        }
        return ibis.filterIndexed { idx, _ -> !drop[idx] }
    }

    /**
     * Drops isolated doubled intervals (~2 × local median) flanked by steady neighbours: a single
     * missed pulse peak rather than irregular intervals.
     */
    fun dropMissedPeaks(ibis: List<Int>): List<Int> {
        if (ibis.size < 6) return ibis
        val drop = BooleanArray(ibis.size)
        for (i in ibis.indices) {
            val left = listOfNotNull(ibis.getOrNull(i - 2), ibis.getOrNull(i - 1))
            val right = listOfNotNull(ibis.getOrNull(i + 1), ibis.getOrNull(i + 2))
            if (left.size < 2 || right.size < 2) continue
            val surrounding = (left + right).sorted()
            val median = (surrounding[1] + surrounding[2]) / 2.0
            val steady = surrounding.all { kotlin.math.abs(it - median) <= median * 0.15 }
            if (steady && ibis[i] in (median * 1.75).toInt()..(median * 2.25).toInt()) {
                drop[i] = true
            }
        }
        return ibis.filterIndexed { idx, _ -> !drop[idx] }
    }

    const val MAX_UNRELIABLE_SAMPLES = 0.05
    const val MAX_REJECTED = 0.10
    const val MIN_COVERAGE = 0.85
    const val MAX_COVERAGE = 1.15
    const val MAX_RATE_MISMATCH = 0.15
}

/**
 * Irregular rhythm notification logic: the watch checks one-minute tachograms while the wearer is
 * still. Windows that fail [IbiWindowQuality] are skipped. A readable window is irregular when its
 * intervals are irregularly irregular ([IrnThresholds]). An alert needs [required] irregular out of
 * the last [consider] readable windows within [lookbackMs], spread over at least [minSpanMs], and is
 * followed by a [cooldownMs] quiet period ([cooldownAfterRegularEcgMs] and all [consider] windows
 * irregular when an ECG soon after the last alert looked regular).
 */
class IrregularRhythmDetector(
    private val required: Int = 5,
    private val consider: Int = 6,
    private val lookbackMs: Long = 48 * HOUR,
    private val cooldownMs: Long = 24 * HOUR,
    private val cooldownAfterRegularEcgMs: Long = 48 * HOUR,
    private val stricterAfterRegularEcgMs: Long = 7 * 24 * HOUR,
    private val minSpanMs: Long = HOUR
) {
    /** Analyses one window of samples; returns the new state and an alert if one fires. */
    fun onWindow(
        state: IrnState,
        samples: List<HrSample>,
        idFactory: () -> String,
        sensitivity: IrnSensitivity = IrnSensitivity.STANDARD
    ): Pair<IrnState, HealthAlert?> {
        // Windows judged by an earlier rule don't count towards a notice.
        @Suppress("NAME_SHADOWING")
        val state = if (state.rule < IrnThresholds.RULE) state.copy(windows = emptyList(), rule = IrnThresholds.RULE) else state
        val ibis = when (val quality = IbiWindowQuality.assess(samples)) {
            is IbiWindowQuality.Result.Readable -> quality.ibisMs
            is IbiWindowQuality.Result.Unreadable -> {
                lastSkipReason = quality.reason
                return state to null
            }
        }
        val thresholds = IrnThresholds.of(sensitivity)
        val noMissed = IbiWindowQuality.dropMissedPeaks(ibis)
        val beats = if (thresholds.dropIsolatedEctopics) IbiWindowQuality.dropIsolatedEctopics(noMissed) else noMissed
        val features = RrFeatures.of(beats.map { it.toDouble() })
        if (features == null || beats.size < thresholds.minBeats) {
            lastSkipReason = "too few beats (${beats.size})"
            return state to null
        }
        val start = samples.minOf { it.tsMs }
        val sampleNow = samples.maxOf { it.tsMs }
        val futureLimit = sampleNow + FUTURE_CLOCK_TOLERANCE_MS
        val existing = state.windows.filter { it.startMs <= futureLimit }
        if (existing.any { kotlin.math.abs(it.startMs - start) < MIN_WINDOW_GAP_MS }) {
            lastSkipReason = "too close to previous window"
            return state to null
        }
        lastSkipReason = null
        val patterned = RrFeatures.isLorenzPatterned(listOf(beats.map { it.toDouble() }))
        val window = WindowResult(start, thresholds.irregular(features) && !patterned, (60_000 / features.meanMs).toInt())
        val windows = (existing.filter { it.startMs != start } + window)
            .sortedBy { it.startMs }
            .filter { sampleNow - it.startMs <= lookbackMs }
            .takeLast(consider * 4)
        var next = state.copy(windows = windows)

        val regularEcg = state.regularEcgAtMs?.takeIf { ecg -> state.lastAlertMs?.let { ecg >= it } == true }
        val cooldown = if (regularEcg != null) cooldownAfterRegularEcgMs else cooldownMs
        val need = if (regularEcg != null && sampleNow - regularEcg < stricterAfterRegularEcgMs) consider else required
        val cooldownUntil = state.lastAlertMs?.let { it + cooldown }
        val inCooldown = cooldownUntil != null && sampleNow < cooldownUntil
        val eligible = if (cooldownUntil != null) windows.filter { it.startMs >= cooldownUntil } else windows
        val recent = eligible.takeLast(consider)
        val irregular = recent.filter { it.irregular }
        val spread = irregular.size >= 2 && irregular.last().startMs - irregular.first().startMs >= minSpanMs
        if (!inCooldown && window.irregular && irregular.size >= need && spread) {
            val alert =
                HealthAlert(
                    idFactory(),
                    AlertKind.IRREGULAR_RHYTHM,
                    sampleNow,
                    irregular.map {
                        it.meanBpm
                    }.average().toInt(),
                    irregular.map { it.startMs }
                )
            next = next.copy(lastAlertMs = sampleNow, windows = emptyList())
            return next to alert
        }
        return next to null
    }

    /** Why the last window was not read (null when it was), for the diagnostic log. */
    var lastSkipReason: String? = null
        private set

    companion object {
        const val HOUR = 3_600_000L

        /** An ECG within this time after an alert counts as its follow-up. */
        const val ECG_FOLLOW_UP_MS = 2 * HOUR

        /** Maximum ahead-of-wall-clock skew accepted before rejecting future-dated windows. */
        private const val FUTURE_CLOCK_TOLERANCE_MS = 5 * 60_000L

        /** Minimum gap between distinct counted IRN windows (prevents rapid re-counting). */
        const val MIN_WINDOW_GAP_MS = 5 * 60_000L
    }
}
