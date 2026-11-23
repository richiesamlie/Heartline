// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.tile

import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.wear.tile.TileData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TileDataTest {
    @Test
    fun picksLatestEcgAndBloodPressure() {
        val recent = listOf(
            RecordMeta("b", RecordKind.BLOOD_PRESSURE, 3, 0, 0, 0, RecordSummary.BloodPressure(121, 79, 70)),
            RecordMeta("e", RecordKind.ECG, 2, 0, 500, 0, RecordSummary.Ecg(70, EcgResult.SINUS_RHYTHM, 0f)),
            RecordMeta("e0", RecordKind.ECG, 1, 0, 500, 0, RecordSummary.Ecg(90, EcgResult.AFIB_SIGNS, 0f)),
        )
        val data = TileData.from(recent, 64, 21) { it.result?.name }
        assertEquals("SINUS_RHYTHM", data.lastEcg)
        assertEquals("121/79", data.lastBp)
        assertEquals(64, data.heartRate)
        assertEquals(21, data.bpDaysLeft)
        assertNull(TileData.from(emptyList(), null, null) { null }.lastEcg)
    }

    @Test
    fun wellnessValuesAndTodaysRange() {
        val recent = listOf(
            RecordMeta("s", RecordKind.STRESS, 9, 0, 0, 0, RecordSummary.Stress(72, 18.0)),
            RecordMeta("o", RecordKind.SPO2, 8, 0, 0, 0, RecordSummary.Spo2(96, 70, false)),
            RecordMeta("t3", RecordKind.SKIN_TEMPERATURE, 7, 0, 0, 0, RecordSummary.SkinTemperature(33.4f, null)),
            RecordMeta("t2", RecordKind.SKIN_TEMPERATURE, 6, 0, 0, 0, RecordSummary.SkinTemperature(33.0f, null)),
            RecordMeta("t1", RecordKind.SKIN_TEMPERATURE, 5, 0, 0, 0, RecordSummary.SkinTemperature(33.1f, null)),
            RecordMeta("t0", RecordKind.SKIN_TEMPERATURE, 4, 0, 0, 0, RecordSummary.SkinTemperature(33.2f, null)),
        )
        val data = TileData.from(recent, 64, null, com.heartline.wear.monitor.HeartToday(70, 52, 118)) { null }
        assertEquals(70, data.heartRate)
        assertEquals(52, data.heartMin)
        assertEquals(118, data.heartMax)
        assertEquals(96, data.spo2)
        assertEquals(72, data.stressScore)
        assertEquals(com.heartline.shared.profile.StressLevel.HIGH, data.stressLevel)
        assertEquals(18, data.hrvMs)
        // 33.4 against the median of the previous readings (33.1).
        assertEquals("+0.3°", data.temperature)
    }
}

@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class TileDataLoaderTest {
    @Test
    fun loadsSummaryFromSingleHistoryRead() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = com.heartline.wear.data.WatchDatabase.inMemory(context)
        try {
            val dir = java.io.File(context.cacheDir, "tile-loader-test").apply { deleteRecursively() }
            val records = com.heartline.wear.data.WatchRecordStore(db.records(), dir, db.messages())
            val nowMs = 1_750_000_000_000L
            records.add(RecordMeta("ecg-1", RecordKind.ECG, nowMs - 1_000L, 30_000, 500, 0, RecordSummary.Ecg(68, EcgResult.SINUS_RHYTHM, 0f)), null)
            val settings = com.heartline.wear.monitor.WatchSettingsStore(context)
            val bp = com.heartline.wear.bp.WatchBpStore(context)
            val loaded = TileDataLoader(context, records, settings, bp, now = { nowMs }).load()
            assertEquals(EcgResult.SINUS_RHYTHM, loaded.ecgResult)
        } finally {
            db.close()
        }
    }
}

class HeartHoursTest {
    @Test
    fun hourlyRangesRoundTrip() {
        val h = com.heartline.wear.monitor.HeartHours
        var hours = h.empty()
        hours = h.record(hours, 9, 70)
        hours = h.record(hours, 9, 64)
        hours = h.record(hours, 10, 90)
        org.junit.Assert.assertEquals(64..70, hours[9])
        org.junit.Assert.assertEquals(hours, h.decode(h.encode(hours)))
        org.junit.Assert.assertEquals(h.empty(), h.decode("garbage"))
    }
}
