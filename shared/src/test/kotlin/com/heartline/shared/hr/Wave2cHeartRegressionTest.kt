// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.hr

import com.heartline.shared.irn.HeartRateAlertRules
import com.heartline.shared.irn.IbiWindowQuality
import com.heartline.shared.irn.IrnState
import com.heartline.shared.irn.IrnThresholds
import com.heartline.shared.irn.IrregularRhythmDetector
import com.heartline.shared.pots.OrthostaticEventDetector
import com.heartline.shared.pots.StandProbePolicy
import java.time.ZoneOffset
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Wave2cHeartRegressionTest {
    private val zone = ZoneOffset.UTC
    private val dayMs = 86_400_000L

    private fun ibiWindow(ibis: List<Int>, startMs: Long = 0L): List<HrSample> {
        var ts = startMs
        return ibis.map { ibi ->
            ts += ibi
            HrSample(tsMs = ts, bpm = (60_000.0 / ibi).roundToInt(), ibiMs = listOf(ibi))
        }
    }

    @Test
    fun irnRuleIsThreeAndSymmetricLag1RejectsAlternatingBigeminy() {
        assertEquals(3, IrnThresholds.RULE)

        // Alternating 520 ms / 1080 ms (lag-1 strongly negative): must not be classed irregular or alert.
        val alternating = (0 until 320).map { if (it % 2 == 0) 520 else 1080 }
        val detector = IrregularRhythmDetector()
        val (state, alert) = detector.onWindow(IrnState(), ibiWindow(alternating), { "id" }, IrnSensitivity.HIGH)
        assertNull(alert)
        assertTrue(state.windows.isEmpty() || !state.windows.last().irregular)
        assertTrue(RrFeatures.isLorenzPatterned(listOf(alternating.map { it.toDouble() })))
    }

    @Test
    fun irnRejectsLorenzClusteredTrigeminyDropsIsolatedMissedPeakAndRejectsOverCoverage() {
        // Repeating regular trigeminy (600, 600, 1200) forms tight Lorenz clusters -> rejected as patterned.
        val trigeminy = (0 until 120).flatMap { listOf(600, 600, 1200) }
        assertTrue(RrFeatures.isLorenzPatterned(listOf(trigeminy.map { it.toDouble() })))

        val detector = IrregularRhythmDetector()
        val (triState, triAlert) = detector.onWindow(IrnState(), ibiWindow(trigeminy), { "tri" }, IrnSensitivity.HIGH)
        assertNull(triAlert)
        assertTrue(triState.windows.isEmpty() || !triState.windows.last().irregular)

        // Isolated missed optical peak (~2*800 = 1600 ms) surrounded by steady ~800 ms beats is collapsed.
        val steadyWithMissedPeak = MutableList(100) { 800 }
        steadyWithMissedPeak[50] = 1600
        val cleaned = IbiWindowQuality.dropMissedPeaks(steadyWithMissedPeak)
        assertEquals(99, cleaned.size)
        assertFalse(cleaned.contains(1600))

        // Coverage > 1.15 (over-packed beats for the window duration) must be unreadable.
        val overPacked = (0..60).map { i ->
            HrSample(tsMs = i * 5_000L, bpm = 75, ibiMs = List(9) { 800 })
        }
        assertTrue(IbiWindowQuality.assess(overPacked) is IbiWindowQuality.Result.Unreadable)
    }

    @Test
    fun irnEnforcesMinWindowGapAndClearsRegularEcgTimestampOnNonRegularEcg() {
        val cleared = IrnState().withEcg(regular = true, atMs = 1_000L).withEcg(regular = false, atMs = 2_000L)
        assertNull(cleared.regularEcgAtMs)

        // Build two windows whose start times are 2 minutes apart (< 5 min minimum spacing).
        val detector = IrregularRhythmDetector()
        val rng = java.util.Random(17)
        val irregular1 = List(340) { 550 + rng.nextInt(450) }
        val irregular2 = List(340) { 550 + rng.nextInt(450) }
        val (s1, _) = detector.onWindow(IrnState(), ibiWindow(irregular1, startMs = 0L), { "w1" })
        val firstCount = s1.windows.size
        assertEquals(1, firstCount)

        val (s2, _) = detector.onWindow(s1, ibiWindow(irregular2, startMs = 120_000L), { "w2" })
        assertEquals(firstCount, s2.windows.size)
    }

    @Test
    fun backgroundWindowUnpacksOverlappingBatchesRejectsMovingTracksBeatGapsAndFiltersOutliers() {
        assertEquals(25, BackgroundWindow.HRV_MIN_PAIRS)

        // Packed batches arriving at t = 10s (10x800ms) and t = 14s (10x800ms) overlap from 6s..10s.
        val emptyDup = HrSample(tsMs = 10_000L, bpm = 75, ibiMs = emptyList())
        val batch1 = HrSample(tsMs = 10_000L, bpm = 75, ibiMs = List(10) { 800 })
        val batch2 = HrSample(tsMs = 14_000L, bpm = 75, ibiMs = List(10) { 800 })
        val unpacked = BackgroundWindow.unpack(listOf(emptyDup, batch1, batch2))
        val unpackedSum = unpacked.sumOf { it.ibiMs.sum() }
        assertTrue("Expected overlap deduplicated near 12_000 ms, got $unpackedSum", unpackedSum in 11_000..13_000)

        // Moving sample in the middle of a beat-by-beat stretch splits cleanly without trusting bogus diffs,
        // and an isolated artifact outlier is damped by the local-median filter.
        val cleanStretch = (0 until 45).map { i ->
            val ibi = if (i == 22) {
                1550
            } else if (i % 2 == 0) {
                800
            } else {
                840
            }
            HrSample(tsMs = i * 820L, bpm = 73, ibiMs = listOf(ibi), moving = false)
        }
        val cleanHrv = BackgroundWindow.hrv(cleanStretch)
        assertNotNull(cleanHrv)
        assertTrue("Local median should prevent single artifact from inflating RMSSD: ${cleanHrv!!.rmssdMs}", cleanHrv.rmssdMs < 120.0)

        val movingEveryOther = (0 until 45).map { i ->
            HrSample(tsMs = i * 820L, bpm = 73, ibiMs = listOf(820), moving = i % 2 == 1)
        }
        assertNull(BackgroundWindow.hrv(movingEveryOther))
        assertNull(Hrv.fromSamples(movingEveryOther))

        // Beatless gap with moving=false must still prevent trusting an elapsed timestamp jump across the gap.
        val gapped = (0 until 20).map { i ->
            HrSample(tsMs = i * 800L, bpm = 75, ibiMs = listOf(800))
        } + listOf(
            HrSample(tsMs = 20_000L, bpm = 75, ibiMs = emptyList()),
            HrSample(tsMs = 21_500L, bpm = 75, ibiMs = emptyList())
        ) + (0 until 20).map { i ->
            HrSample(tsMs = 23_000L + i * 800L, bpm = 75, ibiMs = listOf(800))
        }
        val gappedHrv = Hrv.fromSamples(gapped)
        assertNotNull(gappedHrv)
        assertTrue("Beatless gap should not invent large RMSSD: ${gappedHrv!!.rmssdMs}", gappedHrv.rmssdMs < 10.0)
    }

    @Test
    fun minuteMergeUsesSampleCountAndPrecedenceAndHistoryAvoidsDoubleCountAndHandlesPregnancyAndEveningSleep() {
        val a = HrMinute(0L, avgBpm = 60, minBpm = 58, maxBpm = 64, rmssdMs = null, activity = HrContext.REST, sampleCount = 10)
        val b = HrMinute(0L, avgBpm = 90, minBpm = 80, maxBpm = 95, rmssdMs = 42.0, activity = HrContext.SLEEP, sampleCount = 2)
        val merged = MinuteAggregator.merge(a, b)
        // Weighted avg: (60 * 10 + 90 * 2) / 12 = 65
        assertEquals(65, merged.avgBpm)
        assertEquals(12, merged.sampleCount)
        assertEquals(42.0, merged.rmssdMs!!, 1e-9)
        assertEquals(HrContext.SLEEP, merged.activity)
        assertEquals(HrContext.EXERCISE, MinuteAggregator.merge(merged, a.copy(activity = HrContext.EXERCISE)).activity)

        // Replacing an updated minute in HeartHistory does not double-count, and out-of-range BPMs are ignored.
        var history = HeartHistory()
        val m0 = HrMinute(dayMs + 10 * 3_600_000L, 62, 60, 64, activity = HrContext.REST)
        val m0Updated = m0.copy(avgBpm = 66)
        history = history.record(m0, zone)
        history = history.record(m0Updated, zone, previous = m0)
        history = history.record(HrMinute(dayMs + 11 * 3_600_000L, 18, 18, 18, activity = HrContext.REST), zone)
        history = history.record(HrMinute(dayMs + 12 * 3_600_000L, 245, 245, 245, activity = HrContext.REST), zone)
        val recDay = history.days.single { it.day == 1L }
        assertEquals(1, Histogram(recDay.rest).count)
        assertEquals(66, Histogram(recDay.rest).median()?.roundToInt())

        // Evening sleep (18:00-23:59 on day 5 -> night ending day 6) contributes when calling limits for today = 5.
        var eveningSleepHistory = HeartHistory()
        (0 until 30).forEach { i ->
            eveningSleepHistory = eveningSleepHistory.record(
                HrMinute(5 * dayMs + 22 * 3_600_000L + i * 60_000L, 52, 50, 54, activity = HrContext.SLEEP),
                zone
            )
        }
        val eveningLimits = HeartBaseline.limits(
            eveningSleepHistory,
            today = 5L,
            sensitivity = AlertSensitivity.STANDARD,
            age = 35,
            sex = null,
            health = HealthContext()
        )
        assertTrue(eveningLimits.sleepNormal < 57)
        assertEquals(54, eveningLimits.sleepNormal)
        assertTrue(eveningLimits.sleepConfidence > 0.5)

        // Pregnancy widens baseline comparison window from 28/21 days to 14 days so days 25..28 ago are excluded.
        var pregHistory = HeartHistory()
        // Day 1..10: lower resting BPM 56 (beyond 14 days before day 30, inside 28 days).
        for (d in 1L..10L) {
            repeat(20) { k ->
                pregHistory =
                    pregHistory.record(HrMinute(d * dayMs + 12 * 3_600_000L + k * 60_000L, 56, 54, 58, activity = HrContext.REST), zone)
                pregHistory =
                    pregHistory.record(HrMinute(d * dayMs + 2 * 3_600_000L + k * 60_000L, 50, 48, 52, activity = HrContext.SLEEP), zone)
            }
        }
        // Day 17..30 (last 14 days): higher resting BPM 66, sleep 58.
        for (d in 17L..30L) {
            repeat(20) { k ->
                pregHistory =
                    pregHistory.record(HrMinute(d * dayMs + 12 * 3_600_000L + k * 60_000L, 66, 64, 68, activity = HrContext.REST), zone)
                pregHistory =
                    pregHistory.record(HrMinute(d * dayMs + 2 * 3_600_000L + k * 60_000L, 58, 56, 60, activity = HrContext.SLEEP), zone)
            }
        }
        // Recent 3 days high rest/sleep vs prior days 17..23 (for pregnant) -> if day 28..30 has same 66 as days 17..27, pregnant sees 0 change.
        assertFalse(HeartBaseline.nightRaised(pregHistory, day = 30L, health = HealthContext(pregnant = true)))
        assertNull(HeartBaseline.restingTrend(pregHistory, today = 30L, health = HealthContext(pregnant = true)))

        // MaxHr at age 115 uses Tanaka ((208 - 0.7 * 115).toInt() = 127), not unknown-age fallback (190).
        assertEquals(127, MaxHr.predicted(115))
    }

    @Test
    fun alertRulesSeparateRestAndSleepRunsAndOrthostaticRequiresTrueRestSpanAndRejectsWalking() {
        val rules = HeartRateAlertRules()
        val limits =
            HeartLimits(
                restNormal = 62,
                sleepNormal = 54,
                high = 100,
                low = 45,
                sleepLow = 38,
                exerciseMax = 180,
                restConfidence = 0.9,
                sleepConfidence = 0.9
            )
        // Mix of high SLEEP (105, above SLEEP threshold?) Wait: sleep high limit is not resting limit; even alternating REST/SLEEP
        // within 10+ minutes shouldn't merge into a single calm run across context transitions.
        val mixed = listOf(
            HrMinute(0L, 110, 108, 112, activity = HrContext.REST),
            HrMinute(5 * 60_000L, 110, 108, 112, activity = HrContext.SLEEP),
            HrMinute(10 * 60_000L, 110, 108, 112, activity = HrContext.REST),
            HrMinute(15 * 60_000L, 110, 108, 112, activity = HrContext.SLEEP)
        )
        assertTrue(rules.evaluate(mixed, MonitorSettings(), limits, emptyMap()) { "a" }.isEmpty())

        // Orthostatic: 2 samples only 1 min apart before stand do not satisfy rest span (>= 3-5 min).
        val base = 10_000_000L
        val shortRestMinutes = listOf(
            HrMinute(base - 2 * 60_000L, 65, 63, 67, activity = HrContext.REST),
            HrMinute(base - 1 * 60_000L, 65, 63, 67, activity = HrContext.REST),
            HrMinute(base + 1 * 60_000L, 105, 100, 108, activity = HrContext.ACTIVE, steps = 20),
            HrMinute(base + 4 * 60_000L, 104, 100, 107, activity = HrContext.ACTIVE, steps = 20),
            HrMinute(base + 15 * 60_000L, 70, 68, 72, activity = HrContext.REST)
        )
        assertTrue(OrthostaticEventDetector().detect(shortRestMinutes, base + 20 * 60_000L).isEmpty())

        // Proper still span (9 min), but 2+ min at >= 60 spm after stand -> walking, rejected.
        val walkMinutes = listOf(
            HrMinute(base - 9 * 60_000L, 65, 63, 67, activity = HrContext.REST),
            HrMinute(base - 3 * 60_000L, 65, 63, 67, activity = HrContext.REST),
            HrMinute(base + 1 * 60_000L, 105, 100, 108, activity = HrContext.ACTIVE, steps = 65),
            HrMinute(base + 4 * 60_000L, 104, 100, 107, activity = HrContext.ACTIVE, steps = 65),
            HrMinute(base + 15 * 60_000L, 70, 68, 72, activity = HrContext.REST)
        )
        assertTrue(OrthostaticEventDetector().detect(walkMinutes, base + 20 * 60_000L).isEmpty())

        // Multi-batch step spans 10s apart coalesce into one bout rather than failing recency or cadence checks.
        val timeline = ActivityTimeline(
            steps = listOf(
                StepSpan(base + 1_000L, base + 15_000L, 15),
                StepSpan(base + 22_000L, base + 40_000L, 15)
            )
        )
        val bout = StandProbePolicy.gettingUpFromSteps(timeline, nowMs = base + 55_000L)
        assertEquals(base + 1_000L to null, bout)
    }
}
