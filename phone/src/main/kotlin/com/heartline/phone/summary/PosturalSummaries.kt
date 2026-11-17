// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.summary

import com.heartline.phone.data.AlertRecord
import com.heartline.shared.hr.HeartTrend
import com.heartline.shared.hr.HrMinute
import com.heartline.shared.hr.RangeBucket
import com.heartline.shared.model.Symptom
import com.heartline.shared.pots.OrthostaticEvent
import com.heartline.shared.pots.OrthostaticEventDetector
import com.heartline.shared.pots.PosturalPatternRules
import com.heartline.shared.pots.PosturalStatus
import com.heartline.shared.pots.RiseSource
import com.heartline.shared.pots.StandProbe
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The heart-rate screen's link to the report: shown only once a pattern was found. */
data class PosturalBanner(val status: PosturalStatus, val days: Int = 0, val restBpm: Int? = null, val peakBpm: Int? = null)

/** One rise after getting up, as listed in the report. [measured]: the watch took a stand probe for it. */
data class RiseSummary(
    val startMs: Long,
    val date: String,
    val time: String,
    val restBpm: Int,
    val peakBpm: Int,
    val measured: Boolean,
    val symptoms: List<Symptom> = emptyList(),
) {
    val riseBpm: Int get() = peakBpm - restBpm
}

/** A point of a rise's curve: seconds from getting up (negative before) and the heart rate. */
data class CurvePoint(val seconds: Int, val bpm: Int)

/** The report of rises after getting up (docs/algorithms/POSTURAL_RISE.md, section 4). */
data class PosturalReportSummary(
    val status: PosturalStatus = PosturalStatus.NONE,
    /** The rise that counts (30 bpm, 40 for teenagers). */
    val minRiseBpm: Int = OrthostaticEventDetector.ADULT_RISE_BPM,
    /** Days with a rise, and rises, in the last 14 days; days with a rise in the 14 before. */
    val days: Int = 0,
    val riseCount: Int = 0,
    val previousDays: Int = 0,
    val medianRestBpm: Int? = null,
    val medianPeakBpm: Int? = null,
    val medianRiseBpm: Int? = null,
    /** Per day of the last [PosturalSummaries.CHART_DAYS] (index = day, oldest first): the lowest resting rate to the highest held one. */
    val chart: List<RangeBucket> = emptyList(),
    val chartDates: List<String> = emptyList(),
    val chartLabels: List<String> = emptyList(),
    /** The chart's first day (the report's period). */
    val chartStart: LocalDate? = null,
    /** Newest first. */
    val rises: List<RiseSummary> = emptyList(),
    /** Each rise's curve, by its start. */
    val curves: Map<Long, List<CurvePoint>> = emptyMap(),
)

/** Pure builders, so they can be unit tested. */
object PosturalSummaries {
    const val PATTERN_DAYS = 14
    const val CHART_DAYS = 30
    private const val DAY = 86_400_000L

    /** Symptoms the report offers for a rise (how getting up can feel when the rate rises a lot). */
    val OFFERED = listOf(Symptom.DIZZINESS, Symptom.PALPITATIONS, Symptom.FATIGUE, Symptom.SHORTNESS_OF_BREATH, Symptom.FAINTING)

    fun events(minutes: List<HrMinute>, probes: List<StandProbe>, nowMs: Long, minRise: Int) =
        OrthostaticEventDetector(minRiseBpm = minRise).detect(minutes, nowMs, probes)

    /** The time of the newest notice of rises after getting up, if any. */
    fun lastNotice(alerts: List<AlertRecord>): Long? = alerts.filter { it.trend == HeartTrend.POSTURAL_RISE }.maxOfOrNull { it.atMs }

    fun banner(events: List<OrthostaticEvent>, lastNoticeMs: Long?, nowMs: Long, zone: ZoneId): PosturalBanner? {
        val pattern = PosturalPatternRules(windowDays = PATTERN_DAYS).evaluate(events, nowMs, zone)
        val status = PosturalStatus.of(pattern, lastNoticeMs, nowMs)
        if (status == PosturalStatus.NONE) return null
        return PosturalBanner(status, pattern?.days ?: 0, pattern?.medianRestBpm, pattern?.medianPeakBpm)
    }

    fun report(
        events: List<OrthostaticEvent>,
        symptoms: Map<Long, List<Symptom>>,
        lastNoticeMs: Long?,
        nowMs: Long,
        minRise: Int,
        today: LocalDate,
        zone: ZoneId,
        locale: Locale,
        label: (Long) -> Pair<String, String>,
    ): PosturalReportSummary {
        val pattern = PosturalPatternRules(windowDays = PATTERN_DAYS).evaluate(events, nowMs, zone)
        fun day(e: OrthostaticEvent) = Instant.ofEpochMilli(e.startMs).atZone(zone).toLocalDate()
        val recent = events.filter { nowMs - it.startMs in 0 until PATTERN_DAYS * DAY }
        val previous = events.filter { nowMs - it.startMs in PATTERN_DAYS * DAY until 2 * PATTERN_DAYS * DAY }
        val chartFrom = today.minusDays(CHART_DAYS - 1L)
        val chart = events.filter { !day(it).isBefore(chartFrom) && !day(it).isAfter(today) }
            .groupBy { (day(it).toEpochDay() - chartFrom.toEpochDay()).toInt() }
            .map { (i, list) -> RangeBucket(i, list.minOf { it.restBpm }, list.maxOf { it.peakBpm }, list.maxOf { it.peakBpm }) }
            .sortedBy { it.index }
        val dates = (0 until CHART_DAYS).map { chartFrom.plusDays(it.toLong()) }
        return PosturalReportSummary(
            status = PosturalStatus.of(pattern, lastNoticeMs, nowMs),
            minRiseBpm = minRise,
            days = recent.map(::day).toSet().size,
            riseCount = recent.size,
            previousDays = previous.map(::day).toSet().size,
            medianRestBpm = recent.takeIf { it.isNotEmpty() }?.let { median(it.map { e -> e.restBpm }) },
            medianPeakBpm = recent.takeIf { it.isNotEmpty() }?.let { median(it.map { e -> e.peakBpm }) },
            medianRiseBpm = recent.takeIf { it.isNotEmpty() }?.let { median(it.map { e -> e.riseBpm }) },
            chart = chart,
            chartDates = dates.map { it.format(DateTimeFormatter.ofPattern("EEE d MMM", locale)) },
            chartStart = chartFrom,
            chartLabels = dates.filterIndexed { i, _ -> i % 7 == 0 }.map { it.format(DateTimeFormatter.ofPattern("d MMM", locale)) },
            rises = events.filter { !day(it).isBefore(chartFrom) }.sortedByDescending { it.startMs }.map { e ->
                val (date, time) = label(e.startMs)
                RiseSummary(e.startMs, date, time, e.restBpm, e.peakBpm, e.source == RiseSource.PROBE, symptoms[e.startMs].orEmpty())
            },
            curves = events.associate { e ->
                e.startMs to (e.before + e.after).sortedBy { it.tsMs }.map { CurvePoint(((it.tsMs - e.startMs) / 1_000).toInt(), it.bpm) }
            },
        )
    }

    private fun median(values: List<Int>) = values.sorted()[values.size / 2]
}
