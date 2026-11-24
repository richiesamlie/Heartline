// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.heart

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.phone.data.HeartRepository
import com.heartline.phone.data.HeartlineDatabase
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.HrMinute
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.model.Symptom
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Wave2cPhoneHeartRegressionTest {
    private lateinit var db: HeartlineDatabase
    private lateinit var repo: HeartRepository
    private val day = 86_400_000L
    private val start = 20 * day

    @Before
    fun setUp() {
        db = HeartlineDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        repo = HeartRepository(db.heart())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun shortnessOfBreathCountsInPosturalFeltSymptoms() {
        assertTrue(Symptom.SHORTNESS_OF_BREATH in HeartRepository.FELT)
    }

    @Test
    fun incomingBatchFoldsDuplicateMinutesWeightedBySampleCount() = runBlocking {
        repo.storeBatch(
            HrBatch(
                "dup",
                listOf(
                    HrMinute(120_000L, 60, 58, 62, rmssdMs = null, activity = HrContext.REST, sampleCount = 10),
                    HrMinute(120_000L, 90, 85, 95, rmssdMs = 44.0, activity = HrContext.SLEEP, sampleCount = 2),
                ),
            ),
        )
        val m = repo.minutes(0, Long.MAX_VALUE).first().single()
        // Weighted average (60 * 10 + 90 * 2) / 12 = 65 (Room entity preserves schema, so persisted sampleCount defaults to 1 on readback).
        assertEquals(65, m.avgBpm)
        assertEquals(58, m.minBpm)
        assertEquals(95, m.maxBpm)
        assertEquals(44.0, m.rmssdMs!!, 1e-9)
        assertEquals(HrContext.SLEEP, m.activity)
    }

    private fun riseMinutes(upMs: Long): List<HrMinute> {
        val m0 = upMs / 60_000L * 60_000L
        return (5 downTo 1).map { HrMinute(m0 - it * 180_000L, 68, 66, 70, activity = HrContext.REST) } +
            listOf(
                HrMinute(m0 + 60_000L, 104, 100, 108, activity = HrContext.ACTIVE, steps = 30),
                HrMinute(m0 + 240_000L, 104, 100, 108, activity = HrContext.ACTIVE, steps = 25),
                HrMinute(m0 + 15 * 60_000L, 70, 68, 72, activity = HrContext.REST),
            )
    }

    @Test
    fun posturalAlertEvaluatorRespectsQuietHoursWithExplicitZone() = runBlocking {
        val minutes = (0..2).flatMap { d -> riseMinutes(start + d * day + 23 * 3_600_000L) }
        repo.storeBatch(HrBatch("rises", minutes))

        val quietNow = start + 2 * day + 23 * 3_600_000L + 20 * 60_000L // 23:20 UTC
        val quietEvaluator = PosturalAlertEvaluator(
            heart = repo,
            settings = { MonitorSettings(quietStartMinute = 22 * 60, quietEndMinute = 7 * 60) },
            age = { 30 },
            symptomTimes = { emptyList() },
            zone = ZoneOffset.UTC,
        )
        assertNull(quietEvaluator.evaluateIfDue(quietNow))

        val awakeNow = start + 3 * day + 8 * 3_600_000L // 08:00 UTC
        assertNotNull(quietEvaluator.evaluateIfDue(awakeNow))
    }
}
