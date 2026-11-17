// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.summary

import com.heartline.phone.data.AlertRecord
import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.HeartBaseline
import com.heartline.shared.hr.HeartHistory
import com.heartline.shared.hr.Histogram
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.HrMinute
import com.heartline.shared.irn.IrregularRhythmDetector
import com.heartline.shared.model.EcgMetrics
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.Symptom
import java.time.ZoneId
import kotlin.math.roundToInt

/** Formatted ECG summary shared between PDF/image reports and plain-text AI sharing. */
data class EcgSummary(
    val id: String,
    val date: String,
    val time: String,
    val result: EcgResult,
    val averageBpm: Int?,
    val symptoms: List<Symptom>,
    val durationSec: Int,
    val sampleRateHz: Int,
    val samples: FloatArray? = null,
    val metrics: EcgMetrics? = null,
)

/** Pure heart-rate summaries so they can be shared and unit tested without UI dependencies. */
object HeartSummaries {
    /** Resting heart rate: a low percentile of minutes awake and still (sleep and exercise left out). */
    fun resting(minutes: List<HrMinute>): Int? {
        val rest = minutes.filter { it.activity == HrContext.REST }.map { it.avgBpm }.sorted()
        return if (rest.size < 5) null else rest[rest.size / 10]
    }

    /** HRV (RMSSD) median of still minutes, awake or asleep. */
    fun hrv(minutes: List<HrMinute>): Int? {
        val values = minutes.filter { it.activity == HrContext.REST || it.activity == HrContext.SLEEP }.mapNotNull { it.rmssdMs }.sorted()
        return if (values.size < 5) null else values[values.size / 2].toInt()
    }

    fun sleep(minutes: List<HrMinute>): Pair<Int, Int>? {
        val sleep = minutes.filter { it.activity == HrContext.SLEEP }
        if (sleep.size < 5) return null
        return sleep.map { it.avgBpm }.average().roundToInt() to sleep.minOf { it.minBpm }
    }

    /**
     * For a rhythm notification: whether the first usable ECG within two hours after it looked
     * regular (true), showed something else (false), or there was none (null).
     */
    fun ecgFollowUp(alert: AlertRecord, ecgs: List<Pair<Long, EcgResult>>): Boolean? {
        if (alert.kind != AlertKind.IRREGULAR_RHYTHM) return null
        val window = alert.atMs..alert.atMs + IrregularRhythmDetector.ECG_FOLLOW_UP_MS
        val result = ecgs.filter { it.first in window && it.second != EcgResult.POOR_RECORDING && it.second != EcgResult.INCONCLUSIVE }
            .minByOrNull { it.first }?.second ?: return null
        return result == EcgResult.SINUS_RHYTHM
    }

    /** The phone's minutes as daily histograms (the watch's baseline input), for older watches that send no limits. */
    fun history(minutes: List<HrMinute>, zone: ZoneId): HeartHistory = minutes.fold(HeartHistory()) { h, m ->
        h.record(m, zone)
    }

    /** Median heart rate in sleep of the night ending on [day], if there are enough readings. */
    fun night(history: HeartHistory, day: Long): Int? = history.days.firstOrNull { it.day == day }
        ?.let { Histogram(it.sleep) }?.takeIf { it.count >= HeartBaseline.MIN_NIGHT_READINGS }?.median()?.roundToInt()

    /** Minutes in each zone given its lower [bounds]; minutes below the first are not counted. */
    fun zones(minutes: List<HrMinute>, bounds: List<Int>): List<Int> {
        return bounds.indices.map { i ->
            val lo = bounds[i]
            val hi = bounds.getOrNull(i + 1) ?: Int.MAX_VALUE
            minutes.count { it.avgBpm in lo until hi }
        }
    }
}
