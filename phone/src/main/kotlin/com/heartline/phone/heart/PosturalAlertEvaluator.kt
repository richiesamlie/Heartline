// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.heart

import com.heartline.logging.HLog
import com.heartline.phone.data.HeartRepository
import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HeartTrend
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.hr.describe
import com.heartline.shared.pots.OrthostaticEventDetector
import com.heartline.shared.pots.PosturalPatternRules
import com.heartline.shared.pots.RiseSource
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Evaluates multi-day rises after getting up (docs/algorithms/POSTURAL_RISE.md) on the phone using
 * the last two weeks of stored heart-rate minutes and watch stand probes.
 */
class PosturalAlertEvaluator(
    private val heart: HeartRepository,
    private val settings: suspend () -> MonitorSettings = { MonitorSettings() },
    private val age: suspend () -> Int? = { null },
    private val symptomTimes: suspend (fromMs: Long) -> List<Long> = { emptyList() },
    private val zone: ZoneId? = null,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val evalLock = Mutex()

    /** Checks for a newly actionable postural rise pattern as of [nowMs], storing and returning the alert if raised. */
    suspend fun evaluateIfDue(nowMs: Long): HealthAlert? = evalLock.withLock {
        val cfg = settings()
        if (!cfg.posturalRiseActive) return@withLock null
        val z = zone ?: ZoneId.systemDefault()
        val local = java.time.Instant.ofEpochMilli(nowMs).atZone(z)
        if (cfg.isQuiet(local.hour * 60 + local.minute)) return@withLock null
        val last = heart.lastPosturalAlertMs()
        if (last != null && nowMs - last < PosturalPatternRules.COOLDOWN_MS) return@withLock null
        val newestMinute = heart.newestMinuteMs() ?: return@withLock null
        val evalNow = minOf(nowMs, newestMinute + 60_000L)
        val from = evalNow - HeartRepository.PATTERN_LOOKBACK_MS
        val minutes = heart.minutesBetween(from, evalNow)
        val probes = heart.standProbesBetween(from, evalNow + 1)
        val minRise = OrthostaticEventDetector.minRiseFor(age())
        val events = withContext(Dispatchers.Default) {
            OrthostaticEventDetector(minRiseBpm = minRise).detect(minutes, evalNow, probes)
        }
        val rules = PosturalPatternRules()
        // Symptoms only describe a pattern, so the ECG records are read only once there is one.
        if (withContext(Dispatchers.Default) { rules.evaluate(events, evalNow, z) } == null) return@withLock null
        val tagged = heart.feltRiseSymptomsSince(from)
        val symptomMs = symptomTimes(from) + tagged
        val pattern = withContext(Dispatchers.Default) {
            rules.evaluate(events, evalNow, z, symptomMs)
        } ?: return@withLock null
        val alert = HealthAlert(
            newId(),
            AlertKind.HIGH_HEART_RATE,
            evalNow,
            pattern.medianPeakBpm,
            threshold = minRise,
            context = HrContext.ACTIVE,
            normal = pattern.medianRestBpm,
            trend = HeartTrend.POSTURAL_RISE,
            value = pattern.medianRiseBpm.toFloat(),
        )
        HLog.i(
            "Heartline/Alert",
            "postural rise: ${pattern.events} rises on ${pattern.days} days (${events.count { it.source == RiseSource.PROBE }} measured), " +
                "symptoms=${pattern.withSymptoms} ${alert.describe()}",
        )
        if (heart.storeAlert(alert)) alert else null
    }
}
