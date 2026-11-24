// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.bp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.phone.data.BpRepository
import com.heartline.phone.data.BpValidation
import com.heartline.phone.data.HeartlineDatabase
import com.heartline.phone.data.RecordRepository
import com.heartline.phone.data.WaveStore
import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.BpPipeline
import com.heartline.shared.bp.BpSessionHeader
import com.heartline.shared.bp.BpSessionLog
import com.heartline.shared.bp.CalibrationPoint
import com.heartline.shared.bp.ImuStreams
import com.heartline.shared.bp.PpgFeatures
import com.heartline.shared.bp.SensorStream
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sample.SyntheticPpg
import com.heartline.shared.sync.InMemoryTransport
import com.heartline.shared.sync.PhoneSyncSender
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Wave2aPhoneBpRegressionTest {
    private lateinit var db: HeartlineDatabase
    private lateinit var records: RecordRepository
    private lateinit var bp: BpRepository
    private lateinit var sessionsDir: File
    private lateinit var refiner: BpRefiner
    private lateinit var exporter: BpDatasetExporter
    private val calCreatedAt = 10 * BpCalibration.DAY_MS

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = HeartlineDatabase.inMemory(context)
        val root = File(context.cacheDir, "wave2a-phone-bp").apply { deleteRecursively(); mkdirs() }
        sessionsDir = File(root, "sessions").apply { mkdirs() }
        records = RecordRepository(db.records(), WaveStore(File(root, "waves")))
        val sender = PhoneSyncSender(InMemoryTransport.pair().first, records)
        bp = BpRepository(db.bp(), records, sessionsDir = sessionsDir, now = { calCreatedAt + 5 * BpCalibration.DAY_MS }, sender = sender)
        refiner = BpRefiner(bp, records)
        exporter = BpDatasetExporter(bp, records)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun saveCalibration(extra: List<CalibrationPoint> = emptyList()) {
        val points = (1..3).map { CalibrationPoint(PpgFeatures.extract(SyntheticPpg.generate(20.0, 68.0, 0.45, seed = it), 100)!!, 120, 78, 68) }
        bp.saveCalibration(BpCalibration("cal", calCreatedAt, points, extraPoints = extra))
    }

    private suspend fun saveReading(id: String, startedAtMs: Long, summary: RecordSummary.BloodPressure, seed: Int = 10): RecordMeta {
        val wave = SyntheticPpg.generate(20.0, 70.0, 0.55, seed = seed)
        val meta = RecordMeta(id, RecordKind.BLOOD_PRESSURE, startedAtMs, 20_000, 100, wave.size, summary)
        records.save(meta, wave)
        return meta
    }

    private suspend fun saveHlbpSession(sessionId: String, startedAtMs: Long, hr: Double = 70.0) {
        val wave = SyntheticPpg.generate(20.0, hr, 0.55, seed = 4)
        val ts = LongArray(wave.size) { i -> i * 10_000_000L }
        val log = BpSessionLog(
            BpSessionHeader(sessionId, startedAtMs, "measure"),
            listOf(SensorStream("ppg_green", listOf("value"), ts, wave)),
        )
        bp.saveSession(sessionId, log.encode())
    }

    @Test
    fun refinerFiltersPreCalibrationEpochKeepsMissingHlbpFallbackAndExporterUsesWatchValues() = runBlocking {
        saveCalibration()
        // Before calibration: excluded from trainingSamples.
        saveReading("pre", calCreatedAt - 60_000, RecordSummary.BloodPressure(118, 76, 68, 5, algorithm = 3, sessionId = null))
        bp.addValidation(BpValidation("v-pre", "pre", calCreatedAt, 118, 76, 120, 78))

        // After calibration, WITHOUT a session file on disk yet: kept via fallback (not dropped), and watchSystolic exported.
        saveReading(
            "post",
            calCreatedAt + 60_000,
            RecordSummary.BloodPressure(125, 81, 70, 6, algorithm = 4, watchSystolic = 121, watchDiastolic = 79, sessionId = "missing-hlbp"),
        )
        bp.addValidation(BpValidation("v-post", "post", calCreatedAt + 120_000, 121, 79, 126, 82))

        val samples = refiner.trainingSamples()
        assertEquals(1, samples.size)
        assertEquals(121, samples.single().classicalSys)
        assertEquals(79, samples.single().classicalDia)

        val dataset = exporter.dataset()!!
        val postEntry = dataset.entries.first { it.atMs == calCreatedAt + 60_000 }
        assertEquals(121, postEntry.watchSystolic)
        assertEquals(79, postEntry.watchDiastolic)
    }

    @Test
    fun refinerCausalReplayExcludesFutureExtraPointsAndInvalidatesWhenSessionArrives() = runBlocking {
        val rTime = calCreatedAt + 2 * BpCalibration.DAY_MS
        val futureCuff = CalibrationPoint(
            PpgFeatures.extract(SyntheticPpg.generate(20.0, 68.0, 0.5, seed = 9), 100)!!,
            180,
            110,
            68,
            atMs = rTime + BpCalibration.DAY_MS,
            sessionId = "future-s",
        )
        saveCalibration(extra = listOf(futureCuff))
        saveReading("r1", rTime, RecordSummary.BloodPressure(121, 79, 70, 6, algorithm = 3, sessionId = "sess-1"))
        bp.addValidation(BpValidation("v1", "r1", rTime + 1_000, 121, 79, 122, 80))

        // First call without .hlbp uses stored 121.
        val beforeSession = refiner.trainingSamples()
        assertEquals(1, beforeSession.size)
        assertEquals(121, beforeSession.single().classicalSys)

        // When .hlbp arrives later, datasetKey invalidates cache and classical replay runs without leaking futureCuff (180 mmHg).
        saveHlbpSession("sess-1", rTime)
        val afterSession = refiner.trainingSamples()
        assertEquals(1, afterSession.size)
        assertTrue("classicalSys=${afterSession.single().classicalSys} must not reflect future 180 mmHg cuff", afterSession.single().classicalSys < 135)
    }
}
