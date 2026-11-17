// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ecg

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.shared.model.EcgResult
import com.heartline.wear.data.WatchDatabase
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.sensor.FakeEcgSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
}
