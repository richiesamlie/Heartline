// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.heartline.phone.bp.BpDatasetExporter
import com.heartline.phone.bp.BpRefiner
import com.heartline.phone.data.BpRepository
import com.heartline.phone.data.BpValidation
import com.heartline.phone.data.HeartlineDatabase
import com.heartline.phone.data.RecordRepository
import com.heartline.phone.data.WaveStore
import com.heartline.phone.sync.RecordEnrichmentWorker
import com.heartline.phone.sync.RecordIngestionCoordinator
import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.BpSafety
import com.heartline.shared.bp.CalibrationPoint
import com.heartline.shared.bp.MorphologyEmbedder
import com.heartline.shared.bp.PpgEmbedder
import com.heartline.shared.bp.PpgFeatures
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sample.SyntheticPpg
import com.heartline.shared.sync.InMemoryTransport
import com.heartline.shared.sync.PhoneSyncSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BpLearningTest {
    private lateinit var db: HeartlineDatabase
    private lateinit var records: RecordRepository
    private lateinit var sender: PhoneSyncSender
    private lateinit var bp: BpRepository
    private lateinit var refiner: BpRefiner
    private lateinit var exporter: BpDatasetExporter
    private val alerts = mutableListOf<RecordSummary.BloodPressure>()
    private val now = 10 * BpCalibration.DAY_MS

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = HeartlineDatabase.inMemory(context)
        records = RecordRepository(db.records(), WaveStore(File(context.cacheDir, "bp-learning").apply { deleteRecursively() }))
        sender = PhoneSyncSender(InMemoryTransport.pair().first, records)
        bp = BpRepository(db.bp(), records, now = { now }, sender = sender)
        refiner = BpRefiner(bp, records)
        exporter = BpDatasetExporter(bp, records)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun calibrate() {
        val points = (1..3).map { CalibrationPoint(PpgFeatures.extract(SyntheticPpg.generate(20.0, 68.0, 0.45, seed = it), 100)!!, 120, 78, 68) }
        bp.saveCalibration(BpCalibration("cal", now - BpCalibration.DAY_MS, points))
    }

    private suspend fun reading(id: String, stiffness: Double, summary: RecordSummary.BloodPressure, seed: Int): RecordMeta {
        val wave = SyntheticPpg.generate(20.0, 70.0, stiffness, seed = seed)
        val meta = RecordMeta(id, RecordKind.BLOOD_PRESSURE, now - 60_000, 20_000, 100, wave.size, summary)
        val coordinator = com.heartline.phone.sync.RecordIngestionCoordinator(
            records = records,
            bpRefiner = refiner,
            onBpSafety = { alerts += it },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )
        records.save(meta, wave)
        coordinator.onRecordSaved(meta, wave)
        return meta
    }

    @Test
    fun aCuffCheckBecomesACalibrationPointAndIsExportable() = runBlocking {
        calibrate()
        reading("r1", 0.8, RecordSummary.BloodPressure(128, 82, 70, 9, algorithm = 3, beyondCalibration = true), seed = 11)
        bp.addValidation(BpValidation("v1", "r1", now, 128, 82, 146, 90))
        val cal = bp.calibration.first()!!
        assertEquals(1, cal.extraPoints.size)
        assertEquals(146, cal.extraPoints.single().cuffSystolic)
        assertEquals(120..146, cal.systolicSpan)
        val data = exporter.dataset()!!
        assertEquals(1, data.entries.size)
        assertEquals(2000, data.entries.single().ppg.size)
        assertEquals(146, data.entries.single().cuffSystolic)
    }

    @Test
    fun aConfirmedVeryHighReadingRaisesANotice() = runBlocking {
        calibrate()
        reading("r1", 0.9, RecordSummary.BloodPressure(184, 104, 90, 14, algorithm = 3, beyondCalibration = true), seed = 12)
        assertTrue(alerts.isEmpty())
        reading("r2", 0.9, RecordSummary.BloodPressure(186, 106, 90, 14, algorithm = 3, beyondCalibration = true, confirmed = true), seed = 13)
        assertEquals(186, alerts.single().systolic)
    }

    @Test
    fun readingsAreRefinedOnlyOnceThePersonalModelEarnsIt() = runBlocking {
        calibrate()
        // Too few checks: the watch's reading stands.
        reading("first", 0.5, RecordSummary.BloodPressure(121, 79, 70, 6, algorithm = 3), seed = 20)
        assertEquals(3, (records.get("first")!!.summary as RecordSummary.BloodPressure).algorithm)

        // This user reads systematically low on stiff pulses; the checks teach the model that
        // (algorithm 6.4: it takes 12 checks before the personal model may correct anything).
        for (i in 0 until 12) {
            val stiffness = 0.2 + 0.06 * i
            reading("c$i", stiffness, RecordSummary.BloodPressure(122, 79, 70, 6, algorithm = 3), seed = 30 + i)
            bp.addValidation(BpValidation("v$i", "c$i", now, 122, 79, (122 + 40 * (stiffness - 0.5)).toInt(), 79))
        }
        reading("new", 0.85, RecordSummary.BloodPressure(122, 79, 70, 6, algorithm = 3), seed = 50)
        val refined = records.get("new")!!.summary as RecordSummary.BloodPressure
        assertEquals(4, refined.algorithm)
        assertEquals(122, refined.watchSystolic)
        assertTrue("$refined", refined.systolic > 128)
        assertNull((records.get("first")!!.summary as RecordSummary.BloodPressure).watchSystolic)
    }

    @Test
    fun deleteAllAlsoClearsSavedRawSessionFiles() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sessions = File(context.cacheDir, "bp-delete-sessions").apply { deleteRecursively(); mkdirs() }
        val repo = BpRepository(db.bp(), records, sessionsDir = sessions, now = { now }, sender = sender)
        repo.saveSession("s1", com.heartline.shared.bp.BpSessionRecorder("s1", "measure", now) { now }.build().encode())
        assertTrue(File(sessions, "s1.hlbp").exists())
        repo.deleteAll()
        assertTrue(sessions.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun bpRepositoryRejectsPathTraversalSessionIds() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val base = File(context.cacheDir, "bp-traversal").apply { deleteRecursively(); mkdirs() }
        val sessions = File(base, "sessions").apply { mkdirs() }
        val repo = BpRepository(db.bp(), records, sessionsDir = sessions, now = { now }, sender = sender)
        val encoded = com.heartline.shared.bp.BpSessionRecorder("s1", "measure", now) { now }.build().encode()
        repo.saveSession("../escape", encoded)
        repo.annotateSession("../escape", 120, 80, 70)
        assertTrue(!File(base, "escape.hlbp").exists())
        assertNull(repo.session("../escape"))
    }

    @Test
    fun concurrentSaveSessionAndAnnotateSessionPreserveCuffWithoutCorruption() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sessions = File(context.cacheDir, "bp-concurrent-sessions").apply { deleteRecursively(); mkdirs() }
        val repo = BpRepository(db.bp(), records, sessionsDir = sessions, now = { now }, sender = sender)
        for (i in 1..20) {
            val id = "sess-$i"
            val encoded = com.heartline.shared.bp.BpSessionRecorder(id, "measure", now) { now }.build().encode()
            val j1 = launch(Dispatchers.IO) { repo.saveSession(id, encoded) }
            val j2 = launch(Dispatchers.IO) { repo.annotateSession(id, 130, 84, 72) }
            j1.join()
            j2.join()
            val stored = repo.session(id)
            assertEquals(130, stored?.header?.cuffSystolic)
            assertEquals(84, stored?.header?.cuffDiastolic)
            assertEquals(72, stored?.header?.cuffPulse)
        }
    }

    @Test
    fun bpRefinerCachesEmbeddingsAndTrainedModelAcrossReadings() = runBlocking {
        calibrate()
        for (i in 0 until 12) {
            val stiffness = 0.2 + 0.06 * i
            reading("c$i", stiffness, RecordSummary.BloodPressure(122, 79, 70, 6, algorithm = 3), seed = 30 + i)
            bp.addValidation(BpValidation("v$i", "c$i", now, 122, 79, (122 + 40 * (stiffness - 0.5)).toInt(), 79))
        }
        var embedCalls = 0
        val countingEmbedder = PpgEmbedder { ppg, fs, features ->
            embedCalls++
            MorphologyEmbedder.embed(ppg, fs, features)
        }
        val cachedRefiner = BpRefiner(bp, records, embedders = { listOf(countingEmbedder) })

        val wave1 = SyntheticPpg.generate(20.0, 70.0, 0.85, seed = 61)
        val meta1 = RecordMeta("new-1", RecordKind.BLOOD_PRESSURE, now + 1_000, 20_000, 100, wave1.size, RecordSummary.BloodPressure(122, 79, 70, 6, algorithm = 3))
        records.save(meta1, wave1)
        val refined1 = cachedRefiner.refineIfReady(meta1, wave1)!!
        assertEquals(4, refined1.algorithm)
        val callsAfterFirst = embedCalls
        assertEquals(13, callsAfterFirst)

        // Second reading with unchanged validations reuses both sample embeddings and the trained HybridBpModel.
        val wave2 = SyntheticPpg.generate(20.0, 70.0, 0.85, seed = 62)
        val meta2 = RecordMeta("new-2", RecordKind.BLOOD_PRESSURE, now + 2_000, 20_000, 100, wave2.size, RecordSummary.BloodPressure(122, 79, 70, 6, algorithm = 3))
        records.save(meta2, wave2)
        val refined2 = cachedRefiner.refineIfReady(meta2, wave2)!!
        assertEquals(4, refined2.algorithm)
        assertEquals(callsAfterFirst + 1, embedCalls)
    }

    @Test
    fun recordIngestionCoordinatorNotifiesImmediateBpSafetyAndEnrichWorkerRunsCleanly() = runBlocking {
        calibrate()
        val enqueued = mutableListOf<Pair<String, BpSafety?>>()
        val notified = mutableListOf<RecordSummary.BloodPressure>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coord = RecordIngestionCoordinator(
            records = records,
            bpRefiner = refiner,
            onBpSafety = { notified += it },
            scope = scope,
            enqueueEnrichment = { id, safety -> enqueued += id to safety },
        )
        val wave = SyntheticPpg.generate(20.0, 70.0, 0.9, seed = 71)
        val meta = RecordMeta(
            "urgent-bp",
            RecordKind.BLOOD_PRESSURE,
            now - 1_000,
            20_000,
            100,
            wave.size,
            RecordSummary.BloodPressure(186, 106, 90, 14, algorithm = 3, beyondCalibration = true, confirmed = true),
        )
        coord.save(meta, wave)
        assertEquals(1, notified.size)
        assertEquals(listOf("urgent-bp" to BpSafety.VERY_HIGH), enqueued)

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        stopKoin()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        try {
            startKoin {
                modules(module { single { coord } })
            }
            val data = Data.Builder()
                .putString(RecordEnrichmentWorker.KEY_RECORD_ID, "urgent-bp")
                .putString(RecordEnrichmentWorker.KEY_NOTIFIED_SAFETY, BpSafety.VERY_HIGH.name)
                .build()
            val worker = TestListenableWorkerBuilder<RecordEnrichmentWorker>(context)
                .setInputData(data)
                .build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            // Same safety tier was already notified immediately on save, so enrichment does not duplicate the alert.
            assertEquals(1, notified.size)
        } finally {
            scope.cancel()
            stopKoin()
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }

    @Test
    fun algorithm6WatchReadingsAreRefinedAndIdempotent() = runBlocking {
        calibrate()
        for (i in 0 until 12) {
            val stiffness = 0.2 + 0.06 * i
            reading(
                "alg6-$i",
                stiffness,
                RecordSummary.BloodPressure(122, 79, 70, 6, algorithm = 6, bodyState = "STEADY"),
                seed = 80 + i,
            )
            bp.addValidation(BpValidation("va$i", "alg6-$i", now, 122, 79, (122 + 40 * (stiffness - 0.5)).toInt(), 79))
        }
        val wave = SyntheticPpg.generate(20.0, 70.0, 0.85, seed = 99)
        val meta = RecordMeta(
            "alg6-new",
            RecordKind.BLOOD_PRESSURE,
            now - 30_000,
            20_000,
            100,
            wave.size,
            RecordSummary.BloodPressure(122, 79, 70, 6, algorithm = 6, bodyState = "STEADY"),
        )
        records.save(meta, wave)
        val first = refiner.refineIfReady(meta, wave)!!
        assertEquals(6, first.algorithm)
        assertEquals(122, first.watchSystolic)
        assertEquals(79, first.watchDiastolic)
        assertTrue("$first", first.systolic > 128)

        // Calling refineIfReady again on the already-refined record does not re-apply the correction.
        val second = refiner.refineIfReady(meta.copy(summary = first), wave)!!
        assertEquals(first, second)
    }

    @Test
    fun nonSteadyCuffChecksAreExcludedFromPersonalTraining() = runBlocking {
        calibrate()
        for (i in 0 until 12) {
            val stiffness = 0.2 + 0.06 * i
            reading(
                "s-$i",
                stiffness,
                RecordSummary.BloodPressure(122, 79, 70, 6, algorithm = 6, bodyState = "STEADY"),
                seed = 110 + i,
            )
            bp.addValidation(BpValidation("vs-$i", "s-$i", now, 122, 79, (122 + 40 * (stiffness - 0.5)).toInt(), 79))
        }
        reading(
            "transient",
            0.7,
            RecordSummary.BloodPressure(122, 79, 70, 6, algorithm = 6, bodyState = "TRANSIENT"),
            seed = 130,
        )
        bp.addValidation(BpValidation("v-transient", "transient", now, 122, 79, 145, 92))
        reading(
            "range-only",
            0.7,
            RecordSummary.BloodPressure(122, 79, 70, 15, algorithm = 6, bodyState = "STEADY", rangeOnly = true),
            seed = 131,
        )
        bp.addValidation(BpValidation("v-range", "range-only", now, 122, 79, 145, 92))

        val samples = refiner.trainingSamples()
        assertEquals(12, samples.size)
    }
}
