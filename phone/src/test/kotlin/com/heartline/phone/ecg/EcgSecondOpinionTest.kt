// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ecg

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.phone.data.HeartlineDatabase
import com.heartline.phone.data.RecordRepository
import com.heartline.phone.data.WaveStore
import com.heartline.phone.ecg.EcgFounder
import com.heartline.phone.ecg.EcgSecondOpinion
import com.heartline.shared.ecg.EcgAnalyzer
import com.heartline.shared.ecg.EcgFounderInput
import com.heartline.shared.ecg.EcgSession
import com.heartline.shared.ecg.RhythmFeatures
import com.heartline.shared.ecg.RhythmModel
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sample.SyntheticEcg
import java.io.File
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
class EcgSecondOpinionTest {
    private lateinit var db: HeartlineDatabase
    private lateinit var records: RecordRepository
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val model by lazy { RhythmModel.parse(context.assets.open(EcgSecondOpinion.MODEL_ASSET).use { it.readBytes().decodeToString() }) }

    @Before
    fun setUp() {
        db = HeartlineDatabase.inMemory(context)
        records = RecordRepository(db.records(), WaveStore(File(context.cacheDir, "ecg-second").apply { deleteRecursively() }))
    }

    @After
    fun tearDown() = db.close()

    /** A stand-in for ECGFounder: "normal sinus rhythm" labels high, everything else low. */
    private val normalFounder = EcgFounder { windows ->
        assertTrue(windows.all { it.size == EcgFounderInput.WINDOW })
        DoubleArray(150) { if (it in 1..3) 0.95 else 0.02 }
    }

    private suspend fun ecgRecord(): Pair<RecordMeta, FloatArray> {
        val wave = SyntheticEcg.generate(30.0, 70.0, seed = 3).let { s -> FloatArray(s.size) { s[it] * 0.4f } }
        val a = EcgAnalyzer.analyze(wave, 500, EcgSession())
        val meta = RecordMeta("e1", RecordKind.ECG, 0, 30_000, 500, wave.size, RecordSummary.Ecg(a.averageBpm, a.result, 0f, metrics = a.metrics))
        records.save(meta, wave)
        return meta to wave
    }

    @Test
    fun theSecondOpinionModelMatchesTheFeatures() {
        val expectedAppFeatures = RhythmFeatures.NAMES.filterNot { it == "amplitude_mv" || it == "intervals" }
        assertEquals(expectedAppFeatures, model.features.filterNot { it.startsWith("ef_") })
        assertEquals(150, model.features.count { it.startsWith("ef_") })
        assertEquals(listOf("normal", "af", "other", "noisy"), model.classes)
    }

    @Test
    fun aSecondOpinionIsAddedToTheRecord() = runBlocking {
        val (meta, wave) = ecgRecord()
        EcgSecondOpinion({ normalFounder }, { model }).onRecordSaved(records, meta, wave)
        val m = (records.get("e1")!!.summary as RecordSummary.Ecg).metrics!!
        assertNotNull(m.secondOpinion)
        assertNotNull(m.secondOpinionAfProbability)
    }

    @Test
    fun withoutEcgFounderNothingChanges() = runBlocking {
        val (meta, wave) = ecgRecord()
        EcgSecondOpinion({ null }, { model }).onRecordSaved(records, meta, wave)
        assertNull((records.get("e1")!!.summary as RecordSummary.Ecg).metrics!!.secondOpinion)
    }

    @Test
    fun invalidSampleRateReturnsNullBeforeFounder() {
        val opinion = EcgSecondOpinion({ error("founder should not be called") }, { model })
        assertNull(opinion.assess(FloatArray(5000), fs = 0))
    }

    @Test
    fun onRecordSavedPreservesConcurrentSymptomsAndNoteAndIsIdempotent() = runBlocking {
        val (meta, wave) = ecgRecord()
        var founderCalls = 0
        val racingFounder = EcgFounder { windows ->
            founderCalls++
            // Simulate user tagging symptoms and notes on the UI while founder inference runs.
            runBlocking {
                records.updateEcgSymptoms(meta.id, listOf(com.heartline.shared.model.Symptom.PALPITATIONS), "felt flutter")
            }
            normalFounder.probabilities(windows)
        }
        val opinion = EcgSecondOpinion({ racingFounder }, { model })
        opinion.onRecordSaved(records, meta, wave)

        val stored = records.get(meta.id)!!
        val storedEcg = stored.summary as RecordSummary.Ecg
        assertNotNull(storedEcg.metrics!!.secondOpinion)
        assertEquals(listOf(com.heartline.shared.model.Symptom.PALPITATIONS), storedEcg.symptoms)
        assertEquals("felt flutter", stored.note)
        assertEquals(1, founderCalls)

        // Second pass with already-enriched meta is a no-op (WorkManager retry idempotency).
        val enrichedMeta = meta.copy(summary = storedEcg)
        opinion.onRecordSaved(records, enrichedMeta, wave)
        assertEquals(1, founderCalls)
    }

    @Test
    fun retryableHolderRetriesAfterCooldownAndClosesResource() {
        var now = 1_000L
        var attempts = 0
        var closed = false
        val resource = AutoCloseable { closed = true }
        val holder = com.heartline.phone.ml.RetryableHolder(
            minRetryIntervalMs = 60_000L,
            clockMs = { now },
        ) {
            attempts++
            if (attempts == 1) null else resource
        }

        assertNull(holder.get())
        assertEquals(1, attempts)
        now += 10_000L
        assertNull(holder.get())
        assertEquals(1, attempts)

        now += 60_000L
        assertEquals(resource, holder.get())
        assertEquals(resource, holder.get())
        assertEquals(2, attempts)

        holder.close()
        assertTrue(closed)
    }

    @Test
    fun extractModelAssetReextractsOnVersionStampChange() {
        val target = File(context.noBackupFilesDir, EcgSecondOpinion.MODEL_ASSET.substringAfterLast('/'))
        val stamp = File(context.noBackupFilesDir, "${target.name}.stamp")
        target.delete()
        stamp.delete()

        val first = com.heartline.phone.ml.extractModelAsset(context, EcgSecondOpinion.MODEL_ASSET, versionStamp = "1:0.1.0")
        assertTrue(first.exists())
        assertTrue(stamp.readText().startsWith("1:0.1.0:"))

        // Marker byte overwritten in extracted file would not be touched if stamp still matched, so test stamp change.
        stamp.writeText("stale:0")
        val second = com.heartline.phone.ml.extractModelAsset(context, EcgSecondOpinion.MODEL_ASSET, versionStamp = "2:0.2.0")
        assertTrue(second.exists())
        assertTrue(stamp.readText().startsWith("2:0.2.0:"))
    }

    @Test
    fun leadOffSignalReasonShortCircuitsBeforeFounderWhileCleanSignalProceeds() {
        val wave = SyntheticEcg.generate(30.0, 70.0, seed = 4).let { s -> FloatArray(s.size) { s[it] * 0.4f } }
        var calls = 0
        val countingFounder = EcgFounder { windows ->
            calls++
            normalFounder.probabilities(windows)
        }
        val opinion = EcgSecondOpinion({ countingFounder }, { model })

        // Signal-quality failure (lead-off > 0.5) returns POOR_RECORDING without invoking ECGFounder.
        val poor = opinion.assess(wave, 500, leadOffRatio = 0.6f)
        assertEquals(com.heartline.shared.model.EcgResult.POOR_RECORDING to 0.0, poor)
        assertEquals(0, calls)

        // Clean signal invokes ECGFounder and evaluates the model.
        val clean = opinion.assess(wave, 500, leadOffRatio = 0.0f)
        assertNotNull(clean)
        assertEquals(1, calls)
    }
}
