// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.bp

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.CalibrationPoint
import com.heartline.shared.bp.PpgFeatures
import com.heartline.shared.sample.SyntheticPpg
import com.heartline.wear.data.WatchDatabase
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.sensor.FakePpgSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BpSessionRunnerTest {
    private lateinit var db: WatchDatabase
    private lateinit var records: WatchRecordStore
    private lateinit var bp: WatchBpStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("bp", 0).edit().clear().commit()
        db = WatchDatabase.inMemory(context)
        records = WatchRecordStore(db.records(), File(context.cacheDir, "bp-runner-test").apply { deleteRecursively() }, db.messages())
        bp = WatchBpStore(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun uncalibratedPrepareReturnsNullAndCalibratedRunPersistsWithoutViewModel() = runBlocking {
        var scheduled = 0
        val runner = BpSessionRunner(FakePpgSource(chunkDelayMs = 0), bp, records, { scheduled++ }, uiIntervalMs = 0)
        assertNull(runner.prepare())

        val features = PpgFeatures.extract(SyntheticPpg.generate(20.0, 68.0, 0.5), 100)!!
        bp.setCalibration(BpCalibration("c", System.currentTimeMillis(), List(3) { CalibrationPoint(features, 122, 80, 68) }))
        val session = runner.prepare()!!
        val states = mutableListOf<BpRunState>()
        runner.run(session) { states += it }

        assertTrue(states.last() is BpRunState.Done)
        assertEquals(1, records.pending().size)
        assertTrue(scheduled >= 1)
    }
}
