// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.data

import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HeartTrend
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.HrMinute
import com.heartline.shared.hr.MinuteAggregator
import com.heartline.shared.hr.VitalAlert
import com.heartline.shared.model.Symptom
import com.heartline.shared.pots.StandProbe
import com.heartline.shared.pots.StandReading
import com.heartline.shared.stress.StressSample
import com.heartline.shared.sync.Protocol
import com.heartline.shared.vitals.Spo2Outcome
import com.heartline.shared.vitals.Spo2Sample
import com.heartline.shared.vitals.TempSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

/** Stored heart or vitals alert exposed by [HeartRepository]. */
data class AlertRecord(
    val id: String,
    val kind: AlertKind,
    val atMs: Long,
    val bpm: Int?,
    val windowCount: Int,
    val read: Boolean = false,
    val threshold: Int? = null,
    val context: HrContext? = null,
    val normal: Int? = null,
    val trend: HeartTrend? = null,
    val vital: VitalAlert? = null,
    val value: Float? = null,
    val watchVersion: String? = null,
)

/** Stored background SpO2 try exposed by [HeartRepository]. */
data class Spo2AttemptRecord(
    val tsMs: Long,
    val outcome: Spo2Outcome,
    val percent: Int? = null,
    val retryAtMs: Long? = null,
)

/** Heart-rate trends, vitals, stress samples, and alerts stored in Room on the phone. */
class HeartRepository internal constructor(
    private val dao: HeartDao,
) {
    /** Background stress readings since [fromMs], and the latest awake one. */
    fun stressSince(fromMs: Long): Flow<List<StressSample>> = dao.stressSince(fromMs).map { list -> list.map { it.toSample() } }

    val latestStress: Flow<StressSample?> = dao.latestStress().map { it?.toSample() }

    /** Background blood oxygen and skin temperature since [fromMs]. */
    fun spo2Since(fromMs: Long): Flow<List<Spo2Sample>> = dao.spo2Since(fromMs).map { list -> list.map { it.toSample() } }

    fun tempsSince(fromMs: Long): Flow<List<TempSample>> = dao.tempsSince(fromMs).map { list -> list.map { it.toSample() } }

    /** The watch's background SpO2 tries since [fromMs]. */
    fun spo2AttemptsSince(fromMs: Long): Flow<List<Spo2AttemptRecord>> =
        dao.spo2AttemptsSince(fromMs).map { list -> list.map { it.toRecord() } }

    /** The newest background reading of each kind (Home tiles, widgets). */
    val backgroundLatest: Flow<BackgroundLatest> =
        kotlinx.coroutines.flow.combine(dao.latestSpo2(), dao.latestTemp(), dao.latestStress()) { o, t, s ->
            BackgroundLatest(o?.toSample(), t?.toSample(), s?.toSample())
        }

    /** Every background reading, for the data export. */
    suspend fun backgroundAll() = BackgroundAll(
        dao.allSpo2().map { it.toSample() },
        dao.allTemps().map { it.toSample() },
        dao.allStress().map { it.toSample() },
    )

    fun minutes(fromMs: Long, toMs: Long): Flow<List<HrMinute>> = dao.minutes(fromMs, toMs).map { list -> list.map { it.toMinute() } }

    val latestMinute: Flow<HrMinute?> = dao.latestMinute().map { it?.toMinute() }

    val alerts: Flow<List<AlertRecord>> = dao.alerts().map { list -> list.map { it.toRecord() } }

    /**
     * The same minute can arrive twice: from the all-day stream and from a rhythm check (which alone
     * has HRV), or closed early and then completed. Both are combined rather than one replacing the other.
     *
     * @return the newest data timestamp across stored minutes/probes when any minute or stand probe arrived.
     */
    suspend fun storeBatch(batch: HrBatch): Long? {
        val incoming = batch.minutes
        if (incoming.isNotEmpty()) {
            val existing = incoming.map { it.minuteStartMs }.chunked(500).flatMap { dao.minutesAt(it) }
                .associate { it.minuteStartMs to it.toMinute() }
            dao.insertMinutes(incoming.map { m -> (existing[m.minuteStartMs]?.let { merge(it, m) } ?: m).toEntity() })
        }
        if (batch.spo2.isNotEmpty()) dao.insertSpo2(batch.spo2.map { Spo2SampleEntity(it.tsMs, it.percent, it.context, it.confirmation, it.lowConfidence) })
        if (batch.skinTemp.isNotEmpty()) dao.insertTemps(batch.skinTemp.map { TempSampleEntity(it.tsMs, it.skinC, it.ambientC, it.context, it.counted) })
        if (batch.stress.isNotEmpty()) dao.insertStress(batch.stress.map { StressSampleEntity(it.tsMs, it.rmssdMs, it.bpm, it.score, it.context) })
        if (batch.spo2Attempts.isNotEmpty()) {
            dao.insertSpo2Attempts(batch.spo2Attempts.map { Spo2AttemptEntity(it.tsMs, it.outcome, it.percent, it.retryAtMs) })
            dao.deleteSpo2AttemptsBefore(batch.spo2Attempts.maxOf { it.tsMs } - ATTEMPTS_KEPT_MS)
        }
        if (batch.stands.isNotEmpty()) dao.insertStandProbes(batch.stands.map { it.toEntity() })
        // As of the newest data stored: a probe can arrive before the still minutes it needs.
        return if (incoming.isNotEmpty() || batch.stands.isNotEmpty()) {
            listOfNotNull(dao.newestMinuteMs()?.plus(MINUTE), dao.newestStandProbeMs()).maxOrNull()
        } else {
            null
        }
    }

    /** The minutes and stand probes from [fromMs] on, for the report of rises after getting up. */
    fun posturalSince(fromMs: Long): Flow<Pair<List<HrMinute>, List<StandProbe>>> =
        kotlinx.coroutines.flow.combine(dao.minutes(fromMs, Long.MAX_VALUE), dao.standProbesSince(fromMs)) { m, p ->
            m.map { it.toMinute() } to p.map { it.toProbe() }
        }

    /** How the wearer felt at each rise after getting up, by the rise's start. */
    val riseSymptoms: Flow<Map<Long, List<Symptom>>> = dao.riseSymptoms().map { list -> list.associate { it.startMs to decodeSymptoms(it.symptoms) } }

    suspend fun setRiseSymptoms(startMs: Long, symptoms: List<Symptom>) {
        if (symptoms.isEmpty()) dao.clearRiseSymptoms(startMs) else dao.setRiseSymptoms(RiseSymptomsEntity(startMs, symptoms.joinToString(",") { it.name }))
    }

    internal suspend fun lastPosturalAlertMs(): Long? = dao.lastTrendAlert(HeartTrend.POSTURAL_RISE.name)

    internal suspend fun minutesBetween(fromMs: Long, toMs: Long): List<HrMinute> =
        dao.minutesBetween(fromMs, toMs).map { it.toMinute() }

    internal suspend fun standProbesBetween(fromMs: Long, toMs: Long): List<StandProbe> =
        dao.standProbesBetween(fromMs, toMs).map { it.toProbe() }

    internal suspend fun feltRiseSymptomsSince(fromMs: Long): List<Long> =
        dao.riseSymptomsSince(fromMs).filter { r -> decodeSymptoms(r.symptoms).any { it in FELT } }.map { it.startMs }

    /**
     * Stores an alert idempotently (the watch resends alerts until acknowledged).
     *
     * @return true when [alert] is newly stored and should notify; false when already stored.
     */
    suspend fun storeAlert(alert: HealthAlert, watchVersion: String? = null): Boolean {
        val row = dao.insertAlert(
            AlertEntity(
                alert.id,
                alert.kind,
                alert.atMs,
                alert.bpm,
                alert.windowStartsMs.size,
                threshold = alert.threshold,
                context = alert.context,
                normal = alert.normal,
                trend = alert.trend,
                vital = alert.vital,
                value = alert.value,
                watchVersion = watchVersion,
            ),
        )
        return row != -1L
    }

    suspend fun markAlertsRead() = dao.markAlertsRead()

    companion object {
        /** The SpO2 tries are for the "today" card and a look back; two weeks is plenty. */
        const val ATTEMPTS_KEPT_MS = 14 * 86_400_000L
        private const val MINUTE = 60_000L

        /** The pattern's two weeks, and the still minutes before its first day's rise. */
        const val PATTERN_LOOKBACK_MS = 14 * 86_400_000L + 15 * MINUTE

        /** How the wearer may feel at a rise; tagged on a rise or on an ECG, they describe the notice. */
        val FELT = setOf(Symptom.DIZZINESS, Symptom.PALPITATIONS, Symptom.FAINTING, Symptom.FATIGUE)

        internal fun HrMinuteEntity.toMinute() = HrMinute(minuteStartMs, avgBpm, minBpm, maxBpm, rmssdMs, resting, activity, steps)

        internal fun HrMinute.toEntity() =
            HrMinuteEntity(minuteStartMs, avgBpm, minBpm, maxBpm, rmssdMs, resting, activity, steps)

        internal fun Spo2SampleEntity.toSample() = Spo2Sample(tsMs, percent, context, confirmation, lowConfidence)

        internal fun TempSampleEntity.toSample() = TempSample(tsMs, skinC, ambientC, context, counted)

        internal fun StressSampleEntity.toSample() = StressSample(tsMs, rmssdMs, bpm, score, context)

        internal fun Spo2AttemptEntity.toRecord() = Spo2AttemptRecord(tsMs, outcome, percent, retryAtMs)

        internal fun AlertEntity.toRecord() =
            AlertRecord(id, kind, atMs, bpm, windowCount, read, threshold, context, normal, trend, vital, value, watchVersion)

        internal fun StandProbe.toEntity() = StandProbeEntity(startMs, Protocol.json.encodeToString(ListSerializer(StandReading.serializer()), readings), stepsPerMinute)

        internal fun StandProbeEntity.toProbe() = StandProbe(startMs, runCatching { Protocol.json.decodeFromString(ListSerializer(StandReading.serializer()), readingsJson) }.getOrDefault(emptyList()), stepsPerMinute)

        fun decodeSymptoms(text: String): List<Symptom> = text.split(',').mapNotNull { name -> Symptom.entries.firstOrNull { it.name == name } }

        internal fun merge(old: HrMinute, new: HrMinute) = new.copy(
            avgBpm = (old.avgBpm + new.avgBpm) / 2,
            minBpm = minOf(old.minBpm, new.minBpm),
            maxBpm = maxOf(old.maxBpm, new.maxBpm),
            rmssdMs = new.rmssdMs ?: old.rmssdMs,
            resting = old.resting && new.resting,
            activity = if (new.activity == HrContext.REST) old.activity else new.activity,
            steps = MinuteAggregator.maxSteps(old.steps, new.steps),
        )
    }

    suspend fun deleteDemoData() {
        dao.deleteDemoAlerts()
        // Demo minutes have no ids; no real minute could have arrived before this fix (the link never worked).
        dao.deleteMinutes()
    }

    suspend fun deleteAll() {
        dao.deleteSpo2()
        dao.deleteSpo2Attempts()
        dao.deleteTemps()
        dao.deleteStress()
        dao.deleteMinutes()
        dao.deleteStandProbes()
        dao.deleteRiseSymptoms()
        dao.deleteAlerts()
    }
}

/** The newest background blood oxygen, skin temperature and stress reading, if any. */
data class BackgroundLatest(val spo2: Spo2Sample? = null, val temp: TempSample? = null, val stress: StressSample? = null)

data class BackgroundAll(val spo2: List<Spo2Sample> = emptyList(), val temps: List<TempSample> = emptyList(), val stress: List<StressSample> = emptyList())
