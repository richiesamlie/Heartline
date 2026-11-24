// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.vitals

import com.heartline.shared.body.BodyComposition
import com.heartline.shared.body.BodyLevel
import com.heartline.shared.body.BodyRange
import com.heartline.shared.hr.AlertSensitivity
import com.heartline.shared.hr.DayStats
import com.heartline.shared.hr.HealthContext
import com.heartline.shared.hr.HeartHistory
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.profile.StressIndex
import com.heartline.shared.stress.StressBaseline
import com.heartline.shared.stress.StressHistory
import com.heartline.shared.stress.StressMonitor
import java.time.ZoneOffset
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Wave2dVitalsStressBodyRegressionTest {
    private val zone = ZoneOffset.UTC
    private val day0 = 20_050L

    private fun at(day: Long, hour: Int, minute: Int = 0) = day * 86_400_000L + hour * 3_600_000L + minute * 60_000L

    @Test
    fun vitalsConfidenceIsMonotonicAcrossDayAndNightAndCarriesAsOfDayAndPregnancySafety() {
        val monitor = VitalsMonitor(zone) { "v" }
        var h = VitalsHistory()
        repeat(5) { i ->
            h = monitor.onSpo2(h, Spo2Sample(at(day0, 12 + i), 97, HrContext.REST), emptyList(), MonitorSettings()).first
        }
        val dayOnlyLimits = VitalsBaseline.limits(h, day0, AlertSensitivity.STANDARD, 35)
        assertEquals(day0, dayOnlyLimits.asOfDay)

        val withOneNight = monitor.onSpo2(h, Spo2Sample(at(day0, 2), 96, HrContext.SLEEP), emptyList(), MonitorSettings()).first
        val bothLimits = VitalsBaseline.limits(withOneNight, day0, AlertSensitivity.STANDARD, 35)
        assertTrue(
            "Adding a night reading must increase or preserve spo2Confidence: day=${dayOnlyLimits.spo2Confidence} both=${bothLimits.spo2Confidence}",
            bothLimits.spo2Confidence > dayOnlyLimits.spo2Confidence
        )

        // Pregnancy suppresses temperature / combined alerts even with warm skin + raised night HR.
        var tempHist = VitalsHistory()
        for (d in (day0 - 10)..(day0 - 1)) {
            repeat(6) { k ->
                tempHist =
                    monitor.onTemp(tempHist, TempSample(at(d - 1, 23) + k * 40 * 60_000L, 34.0f, 22f, HrContext.SLEEP, counted = true))
            }
        }
        repeat(6) { k ->
            tempHist =
                monitor.onTemp(tempHist, TempSample(at(day0 - 1, 23) + k * 40 * 60_000L, 35.4f, 22f, HrContext.SLEEP, counted = true))
        }
        val night55 = (54..56).associateWith { 15 }
        val night68 = (67..69).associateWith { 15 }
        val heartHist = HeartHistory((1..10).map { DayStats(day0 - it, sleep = night55) } + DayStats(day0, sleep = night68))
        val pregnant = MonitorSettings(health = HealthContext(pregnant = true))
        assertTrue(monitor.afterNight(tempHist, heartHist, pregnant, at(day0, 10)).second.isEmpty())
    }

    @Test
    fun stressUsesRateLoweringWeightsRaisesSkipLearnThresholdAndGuardsBadInputs() {
        assertEquals(60, StressMonitor.MIN_SKIP_LEARN_WINDOWS)

        val monitor = StressMonitor(zone) { "s" }
        var h = StressHistory()
        // 40 calm windows (< MIN_SKIP_LEARN_WINDOWS = 60): a high-stress window is still learnt into awakeLn.
        for (i in 0 until 40) {
            h =
                monitor.onWindow(
                    h,
                    at(day0 - 2 + i / 20, 9 + (i % 20) / 4, (i % 4) * 15),
                    40.0,
                    66,
                    HrContext.REST,
                    MonitorSettings()
                ).first
        }
        val before40 = h.day(day0).awakeLn.size
        val hAfterLowAt40 = monitor.onWindow(h, at(day0, 10, 0), 12.0, 86, HrContext.REST, MonitorSettings()).first
        assertEquals(before40 + 1, hAfterLowAt40.day(day0).awakeLn.size)

        // Add 25 more calm windows (total > 60): now high-stress windows are skipped from learning.
        var h65 = h
        for (i in 0 until 25) {
            h65 = monitor.onWindow(h65, at(day0 - 1, 10 + i / 4, (i % 4) * 15), 40.0, 66, HrContext.REST, MonitorSettings()).first
        }
        val sizeBeforeSkip = h65.day(day0).awakeLn.size
        val hAfterSkip = monitor.onWindow(h65, at(day0, 15, 0), 12.0, 86, HrContext.REST, MonitorSettings()).first
        assertEquals(sizeBeforeSkip, hAfterSkip.day(day0).awakeLn.size)

        // Check asOfDay and rate-lowering weights (HR shift damped, RMSSD drop emphasized).
        val limits = StressBaseline.limits(h65, day0)
        assertEquals(day0, limits.asOfDay)
        val normal = StressBaseline.normal(h65, day0)!!
        val hrSpikeStandard = StressBaseline.personalScore(exp(normal.ln), (normal.bpm + 18).toInt(), normal, rateLowering = false)
        val hrSpikeBeta = StressBaseline.personalScore(exp(normal.ln), (normal.bpm + 18).toInt(), normal, rateLowering = true)
        assertTrue(hrSpikeBeta < hrSpikeStandard)

        val rmssdDropStandard = StressBaseline.personalScore(exp(normal.ln) * 0.5, normal.bpm.toInt(), normal, rateLowering = false)
        val rmssdDropBeta = StressBaseline.personalScore(exp(normal.ln) * 0.5, normal.bpm.toInt(), normal, rateLowering = true)
        assertTrue(rmssdDropBeta > rmssdDropStandard)

        // StressIndex non-finite & out-of-range EDA guards.
        assertEquals(0, StressIndex.score(Double.NaN))
        assertEquals(0, StressIndex.score(-5.0))
        assertEquals(0, StressIndex.score(Double.POSITIVE_INFINITY))
        assertEquals(0f, StressIndex.edaNudge(Float.NaN), 1e-6f)
        assertEquals(0f, StressIndex.edaNudge(-1f), 1e-6f)
        assertEquals(0f, StressIndex.edaNudge(150f), 1e-6f)
        assertEquals(15f, StressIndex.edaNudge(10f), 1e-6f)
    }

    @Test
    fun bodyCompositionLeavesSexDependentRangesNullWhenSexMissingAndGuardsNonFiniteValues() {
        val ranges = BodyComposition.ranges(sex = null, age = 32)
        assertNull(ranges.bodyFat)
        assertNull(ranges.muscle)
        assertNull(ranges.water)
        assertNull(ranges.phaseAngle)
        assertEquals(BodyLevel.STANDARD, ranges.bmi.level(22f))

        val report = BodyComposition.report(
            RecordSummary.BodyComposition(22f, 30f, 40f, 1500, weightKg = 70f, heightCm = 175f, phaseAngleDeg = -6.0f),
            sex = null,
            age = 32
        )
        assertNull(report.fatLevel)
        assertNull(report.muscleLevel)
        assertNull(report.bodyType)
        assertEquals(22.86f, report.bmi!!, 0.05f)

        val br = BodyRange(0f, 10f, 20f, 40f, veryHigh = 30f)
        assertEquals(BodyLevel.STANDARD, br.level(Float.NaN))
        assertEquals(BodyLevel.STANDARD, br.level(Float.POSITIVE_INFINITY))
        assertEquals(BodyLevel.STANDARD, br.level(Float.NEGATIVE_INFINITY))
    }
}
