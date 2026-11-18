// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ecg

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.shared.model.EcgResult
import com.heartline.shared.sample.SyntheticSession
import com.heartline.wear.data.WatchDatabase
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.sensor.EcgChunk
import com.heartline.wear.sensor.EcgSource
import com.heartline.wear.sensor.FakeEcgSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EcgSessionRunnerTest {
    private lateinit var db: WatchDatabase
    private lateinit var store: WatchRecordStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = WatchDatabase.inMemory(context)
        store = WatchRecordStore(db.records(), File(context.cacheDir, "ecg-runner-test").apply { deleteRecursively() })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun runsDirectlyWithoutViewModel() = runBlocking {
        var scheduled = 0
        var notedResult: EcgResult? = null
        val runner = EcgSessionRunner(
            FakeEcgSource(heartRateBpm = 72.0, chunkDelayMs = 0),
            store,
            { scheduled++ },
            uiIntervalMs = 0,
            onResult = { r, _ -> notedResult = r },
        )
        val states = mutableListOf<EcgRunState>()
        runner.run(onState = { states += it })

        assertTrue(states.any { it is EcgRunState.Analyzing })
        val done = states.last() as EcgRunState.Done
        assertEquals(notedResult, done.result)
        assertEquals(1, scheduled)
        assertEquals(1, store.pending().size)
    }

    @Test
    fun singleMissingPpgBatchDoesNotDiscardPulseArrivalTime() = runBlocking {
        val precise = SyntheticSession.generate(SyntheticSession.Spec(precise = true, seconds = 40.0, seed = 42)).input.precise!!
        val partialNullSource = object : EcgSource {
            override fun stream(): Flow<EcgChunk> = flow {
                val n = 50
                for (i in 0 until 10) {
                    emit(EcgChunk(FloatArray(n), leadOff = true, timestampMs = i * 100L))
                }
                var chunkIndex = 0
                for (offset in 0 until precise.ecg.size - n step n) {
                    val lastMs = 1_000L + ((offset + n) * 1000L / precise.fs)
                    val ppg = if (chunkIndex == 100) null else precise.ppg.copyOfRange(offset, offset + n)
                    emit(EcgChunk(precise.ecg.copyOfRange(offset, offset + n), leadOff = false, timestampMs = lastMs, ppg = ppg))
                    chunkIndex++
                }
            }
        }
        val runnerWithGap = EcgSessionRunner(partialNullSource, store, {}, uiIntervalMs = 0)
        val statesWithGap = mutableListOf<EcgRunState>()
        runnerWithGap.run(onState = { statesWithGap += it })
        val doneWithGap = statesWithGap.last() as EcgRunState.Done
        assertNotNull("Single null PPG batch must not discard PAT", doneWithGap.metrics?.pulseArrivalMs)

        val allNullSource = object : EcgSource {
            override fun stream(): Flow<EcgChunk> = flow {
                val n = 50
                for (i in 0 until 10) {
                    emit(EcgChunk(FloatArray(n), leadOff = true, timestampMs = i * 100L))
                }
                for (offset in 0 until precise.ecg.size - n step n) {
                    val lastMs = 1_000L + ((offset + n) * 1000L / precise.fs)
                    emit(EcgChunk(precise.ecg.copyOfRange(offset, offset + n), leadOff = false, timestampMs = lastMs, ppg = null))
                }
            }
        }
        val runnerWithoutPpg = EcgSessionRunner(allNullSource, store, {}, uiIntervalMs = 0)
        val statesWithoutPpg = mutableListOf<EcgRunState>()
        runnerWithoutPpg.run(onState = { statesWithoutPpg += it })
        val doneWithoutPpg = statesWithoutPpg.last() as EcgRunState.Done
        assertNull(doneWithoutPpg.metrics?.pulseArrivalMs)
    }
}
