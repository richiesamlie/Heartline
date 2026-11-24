// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.metric

import com.heartline.phone.format.RecordFormatter
import com.heartline.phone.summary.BackgroundVitals
import com.heartline.shared.hr.HrContext
import com.heartline.shared.vitals.TempSample
import com.heartline.shared.vitals.VitalsLimits
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Wave2dPhoneVitalsRegressionTest {
    private val zone = ZoneOffset.UTC
    private val today = 20_050L
    private val formatter = RecordFormatter("Today", "Yesterday", zone = zone, locale = Locale.US) {
        LocalDate.ofEpochDay(today)
    }

    private fun at(day: Long, hour: Int, minute: Int = 0) = day * 86_400_000L + hour * 3_600_000L + minute * 60_000L

    @Test
    fun backgroundTemperatureConvertsToFahrenheitScalesDeltaAndIgnoresStaleSyncedLimits() {
        val samples = buildList {
            for (d in (today - 3)..(today - 1)) {
                repeat(5) { k -> add(TempSample(at(d, 2, k * 10), 34.0f, 22f, HrContext.SLEEP, counted = true)) }
            }
            repeat(5) { k -> add(TempSample(at(today, 2, k * 10), 35.0f, 22f, HrContext.SLEEP, counted = true)) }
        }

        // Stale synced limits (from yesterday) must be ignored in favor of recomputed baseline & deviation.
        val staleLimits = VitalsLimits(
            spo2Low = 90,
            spo2DayNormal = 96,
            spo2NightNormal = 95,
            spo2NightDrop = 3,
            tempBaseline = 31.0f,
            tempNights = 1,
            tempRise = 1.0f,
            lastNightDeviation = 9.0f,
            asOfDay = today - 1,
        )

        val fSummary = BackgroundVitals.temperature(
            samples = samples,
            limits = staleLimits,
            today = today,
            formatter = formatter,
            zoneId = zone,
            fahrenheit = true,
        )!!
        assertTrue(fSummary.fahrenheit)
        assertFalse(fSummary.learning)
        // 34.0 C -> 93.2 F; +1.0 C delta -> +1.8 F (never +33.8 F)
        assertEquals(93.2f, fSummary.usualNight!!, 0.05f)
        assertEquals(1.8f, fSummary.lastNight!!, 0.05f)
        // 35.0 C -> 95.0 F on last night bar & latest reading
        assertEquals(95.0f, fSummary.nights.last()!!, 0.05f)
        assertTrue(fSummary.recent.first().second.endsWith("°F"))
        assertEquals("95.0 °F", fSummary.recent.first().second)
    }
}
