// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.summary

import com.heartline.phone.R
import com.heartline.phone.data.BackgroundLatest
import com.heartline.phone.data.HealthRecord
import com.heartline.phone.data.Spo2AttemptRecord
import com.heartline.phone.format.RecordFormatter
import com.heartline.shared.hr.AlertSensitivity
import com.heartline.shared.hr.Histogram
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.profile.StressIndex
import com.heartline.shared.profile.StressLevel
import com.heartline.shared.profile.TemperatureBaseline
import com.heartline.shared.stress.StressLimits
import com.heartline.shared.stress.StressSample
import com.heartline.shared.vitals.Spo2Outcome
import com.heartline.shared.vitals.Spo2Sample
import com.heartline.shared.vitals.TempSample
import com.heartline.shared.vitals.VitalsBaseline
import com.heartline.shared.vitals.VitalsHistory
import com.heartline.shared.vitals.VitalsLimits
import com.heartline.shared.vitals.VitalsMonitor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/** Latest value of a non-ECG metric for its Home tile. */
data class TileValue(val value: String, val unit: String?, val caption: String, val detail: String? = null)

/** A formatted value for one reading of SpO2 / skin temperature / body composition / stress. */
data class MetricReading(
    val id: String,
    val date: String,
    val time: String,
    val value: String,
    val unit: String?,
    /** Plot value for the trend bars. */
    val plot: Float,
    val details: List<Pair<Int, String>> = emptyList(),
    val atMs: Long = 0,
    /** A day of readings the watch took by itself, summarised in one row. */
    val fromWatch: Boolean = false,
    /** That day's readings, newest first (time to value). */
    val entries: List<Pair<String, String>> = emptyList(),
)

/**
 * Readings the watch takes by itself (blood oxygen hourly, skin temperature in sleep and by day):
 * the usual values, last night, the last 28 nights (oldest first) and today's latest readings.
 */
data class BackgroundVitalsSummary(
    /** SpO2: usual awake and asleep (%); temperature: usual night (°C, null until 2 nights). */
    val usualDay: Float? = null,
    val usualNight: Float? = null,
    /** SpO2: last night's median and lowest; temperature: last night's change from usual (°C). */
    val lastNight: Float? = null,
    val lastNightLow: Float? = null,
    val learning: Boolean = true,
    val nights: List<Float?> = emptyList(),
    val recent: List<Pair<String, String>> = emptyList(),
)

/**
 * Background stress from the watch's rhythm windows: today's windows (7:00–23:00 in 15-minute
 * slots, null when none), the day's average and time in high stress, sleep HRV against the usual,
 * the last 7 days' averages (oldest first) and the week's insight.
 */
data class BackgroundStressSummary(
    val today: List<Int?> = emptyList(),
    val todayAverage: Int? = null,
    val highMinutes: Int = 0,
    val latest: Pair<String, Int>? = null,
    val usualNightRmssd: Int? = null,
    val lastNightRmssd: Int? = null,
    val week: List<Int?> = emptyList(),
    /** The week's day names, oldest first ("Tue" … "Mon"). */
    val weekDays: List<String> = emptyList(),
    val weekAboveUsual: Int? = null,
    val learning: Boolean = true,
)

/**
 * Today's background blood oxygen: readings awake and asleep, the last try with how it ended
 * ([lastPercent] when measured) and when the next try is planned.
 */
data class Spo2TodaySummary(
    val awake: Int,
    val asleep: Int,
    val lastTime: String? = null,
    val lastOutcome: Spo2Outcome? = null,
    val lastPercent: Int? = null,
    val next: String? = null,
)

/** Stored stress readings to [BackgroundStressSummary] (pure, unit tested). */
object BackgroundStress {
    const val FIRST_SLOT_HOUR = 7
    const val SLOTS = 64

    fun ui(
        samples: List<StressSample>,
        limits: StressLimits?,
        today: LocalDate,
        formatter: RecordFormatter,
        zone: ZoneId = ZoneId.systemDefault(),
    ): BackgroundStressSummary? {
        val scored = samples.filter { it.score != null }
        if (scored.isEmpty() && limits == null) return null
        fun dateOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone)
        val todays = scored.filter { dateOf(it.tsMs).toLocalDate() == today }
        val slots = arrayOfNulls<Int>(SLOTS)
        todays.forEach { s ->
            val t = dateOf(s.tsMs)
            val slot = (t.hour - FIRST_SLOT_HOUR) * 4 + t.minute / 15
            if (slot in 0 until SLOTS) slots[slot] = maxOf(slots[slot] ?: 0, s.score!!)
        }
        val week = (6 downTo 0).map { back ->
            scored.filter { dateOf(it.tsMs).toLocalDate() == today.minusDays(back.toLong()) }
                .takeIf { it.isNotEmpty() }?.map { it.score!! }?.average()?.roundToInt()
        }
        return BackgroundStressSummary(
            today = slots.toList(),
            todayAverage = todays.takeIf { it.isNotEmpty() }?.map { it.score!! }?.average()?.roundToInt(),
            highMinutes = todays.count { StressIndex.level(it.score!!) == StressLevel.HIGH } * 15,
            latest = scored.lastOrNull()?.let { formatter.time(it.tsMs) to it.score!! },
            usualNightRmssd = limits?.usualNightRmssd?.roundToInt(),
            lastNightRmssd = limits?.lastNightRmssd?.roundToInt(),
            week = week,
            weekDays = (6 downTo 0).map { formatter.weekday(today.minusDays(it.toLong())) },
            weekAboveUsual = limits?.weekAboveUsual,
            learning = (limits?.confidence ?: 0.0) < 0.8,
        )
    }
}

object Spo2Today {
    /** Null when the watch neither measured nor tried today (or is too old to send its tries). */
    fun ui(
        attempts: List<Spo2AttemptRecord>,
        samples: List<Spo2Sample>,
        dayStartMs: Long,
        everyMinutes: Int,
        formatter: RecordFormatter,
    ): Spo2TodaySummary? {
        val tries = attempts.filter { it.tsMs >= dayStartMs }
        val readings = samples.filter { it.tsMs >= dayStartMs }
        if (tries.isEmpty() && readings.isEmpty()) return null
        val last = tries.maxByOrNull { it.tsMs }
        // A put-off try says when it comes back; otherwise the next one is due an interval later.
        val next = last?.let { it.retryAtMs ?: (it.tsMs + everyMinutes * 60_000L) }
        return Spo2TodaySummary(
            awake = readings.count { it.context != HrContext.SLEEP },
            asleep = readings.count { it.context == HrContext.SLEEP },
            lastTime = last?.let { formatter.time(it.tsMs) },
            lastOutcome = last?.outcome,
            lastPercent = last?.percent,
            next = next?.let { formatter.time(it) },
        )
    }
}

/** Background readings to [BackgroundVitalsSummary] (pure, unit tested). */
object BackgroundVitals {
    private val zone get() = ZoneId.systemDefault()

    fun spo2(
        samples: List<Spo2Sample>,
        limits: VitalsLimits?,
        today: Long,
        formatter: RecordFormatter,
        zoneId: ZoneId = zone,
    ): BackgroundVitalsSummary? {
        if (samples.isEmpty()) return null
        val history = vitalsHistory(samples, emptyList(), zoneId)
        val lastNight = history.days.firstOrNull { it.day == today }?.let { Histogram(it.spo2Night) }?.takeIf { it.count > 0 }
        val computed = VitalsBaseline.limits(history, today, limits?.sensitivity ?: AlertSensitivity.STANDARD, null)
        val l = limits ?: computed
        return BackgroundVitalsSummary(
            usualDay = l.spo2DayNormal.toFloat(),
            usualNight = l.spo2NightNormal.toFloat(),
            lastNight = lastNight?.median()?.toFloat(),
            lastNightLow = lastNight?.percentile(0.0)?.toFloat(),
            learning = l.spo2Confidence < 0.8,
            nights = (0 until 28).map { i -> VitalsBaseline.nightSpo2(history, today - 27 + i)?.toFloat() },
            recent = samples.takeLast(6).reversed().map { formatter.time(it.tsMs) to "${it.percent} %" },
        )
    }

    fun temperature(
        samples: List<TempSample>,
        limits: VitalsLimits?,
        today: Long,
        formatter: RecordFormatter,
        zoneId: ZoneId = zone,
    ): BackgroundVitalsSummary? {
        if (samples.isEmpty()) return null
        val history = vitalsHistory(emptyList(), samples, zoneId)
        val base = VitalsBaseline.tempBaseline(history, today)
        return BackgroundVitalsSummary(
            usualNight = (limits?.tempBaseline ?: base?.first?.toFloat()),
            lastNight = limits?.lastNightDeviation ?: VitalsBaseline.nightTemp(history, today)?.let { t -> base?.let { (t - it.first).toFloat() } },
            learning = (limits?.tempNights ?: base?.third ?: 0) < VitalsBaseline.TEMP_SHOW_NIGHTS,
            nights = (0 until 28).map { i -> VitalsBaseline.nightTemp(history, today - 27 + i)?.toFloat() },
            recent = samples.takeLast(6).reversed().map { formatter.time(it.tsMs) to String.format(Locale.US, "%.1f °C", it.skinC) },
        )
    }

    /** The same daily history the watch keeps, rebuilt from the phone's samples. */
    fun vitalsHistory(spo2: List<Spo2Sample>, temps: List<TempSample>, zoneId: ZoneId): VitalsHistory {
        val monitor = VitalsMonitor(zoneId) { "" }
        val off = MonitorSettings().copy(spo2Monitoring = false)
        var h = VitalsHistory()
        spo2.forEach { h = monitor.onSpo2(h, it, emptyList(), off).first }
        temps.forEach { h = monitor.onTemp(h, it) }
        return h
    }
}

/** Pure mapping from stored records to display values, shared with Home tiles. */
object MetricFormat {
    private fun displayTemp(c: Float, fahrenheit: Boolean): Float = if (fahrenheit) c * 9f / 5f + 32f else c

    private fun tempUnit(fahrenheit: Boolean): String = if (fahrenheit) "°F" else "°C"

    fun reading(
        record: HealthRecord,
        previous: List<HealthRecord>,
        formatter: RecordFormatter,
        fahrenheit: Boolean = false,
    ): MetricReading? {
        val date = formatter.date(record.startedAtMs)
        val time = formatter.time(record.startedAtMs)
        return when (val s = record.summary) {
            is RecordSummary.Spo2 -> MetricReading(
                record.id,
                date,
                time,
                "${s.percent}",
                "%",
                s.percent.toFloat(),
                listOfNotNull(s.heartRate?.let { R.string.detail_heart_rate to "$it bpm" }),
            )
            is RecordSummary.SkinTemperature -> {
                val history = previous.mapNotNull { (it.summary as? RecordSummary.SkinTemperature)?.skinCelsius }
                val deviation = TemperatureBaseline.deviation(s.skinCelsius, history)?.let { if (fahrenheit) it * 9f / 5f else it }
                val skin = displayTemp(s.skinCelsius, fahrenheit)
                val ambient = s.ambientCelsius?.let { displayTemp(it, fahrenheit) }
                val unit = tempUnit(fahrenheit)
                MetricReading(
                    record.id,
                    date,
                    time,
                    deviation?.let { String.format(Locale.US, "%+.1f", it) } ?: String.format(Locale.US, "%.1f", skin),
                    unit,
                    skin,
                    listOfNotNull(
                        R.string.detail_skin to String.format(Locale.US, "%.1f %s", skin, unit),
                        ambient?.let { R.string.detail_ambient to String.format(Locale.US, "%.1f %s", it, unit) },
                    ),
                )
            }
            is RecordSummary.BodyComposition -> MetricReading(
                record.id,
                date,
                time,
                String.format(Locale.US, "%.1f", s.bodyFatPercent),
                "%",
                s.bodyFatPercent,
                listOfNotNull(
                    s.skeletalMuscleKg?.let { R.string.detail_muscle to String.format(Locale.US, "%.1f kg", it) },
                    s.bodyWaterKg?.let { R.string.detail_water to String.format(Locale.US, "%.1f kg", it) },
                    s.bmrKcal?.let { R.string.detail_bmr to "$it kcal" },
                ),
            )
            is RecordSummary.Stress -> MetricReading(
                record.id,
                date,
                time,
                "${s.score}",
                null,
                s.score.toFloat(),
                listOfNotNull(
                    R.string.detail_level to StressIndex.level(s.score).name.lowercase().replaceFirstChar { it.uppercase() },
                    s.rmssdMs?.let { R.string.detail_hrv to "${it.toInt()} ms" },
                    s.skinConductanceMicroSiemens?.let { R.string.detail_eda to String.format(Locale.US, "%.1f µS", it) },
                ),
            )
            else -> null
        }
    }

    fun readings(records: List<HealthRecord>, formatter: RecordFormatter, fahrenheit: Boolean = false) =
        records.mapIndexedNotNull { i, r -> reading(r, records.drop(i + 1), formatter, fahrenheit)?.copy(atMs = r.startedAtMs) }
}

/**
 * The watch's background readings where measurements are shown: the newest one on Home and the
 * widgets, and one row per day in a metric's history (they used to appear only in the "Measured
 * by your watch" card, so everything else still showed the last manual measurement).
 */
object BackgroundReadings {
    private fun temp(c: Float) = String.format(Locale.US, "%.1f", c)

    private fun displayTemp(c: Float, fahrenheit: Boolean) = if (fahrenheit) c * 9f / 5f + 32f else c

    /** The newest background reading of [metric], when it is newer than [manualAtMs]. */
    fun tile(
        metric: Metric,
        latest: BackgroundLatest,
        manualAtMs: Long,
        formatter: RecordFormatter,
        fahrenheit: Boolean = false,
    ): TileValue? {
        val (at, value, unit) = when (metric) {
            Metric.SPO2 -> latest.spo2?.let { Triple(it.tsMs, "${it.percent}", "%") }
            Metric.SKIN_TEMPERATURE -> latest.temp?.let {
                Triple(it.tsMs, temp(displayTemp(it.skinC, fahrenheit)), if (fahrenheit) "°F" else "°C")
            }
            Metric.STRESS -> latest.stress?.score?.let { Triple(latest.stress.tsMs, "$it", null) }
            else -> null
        } ?: return null
        if (at <= manualAtMs) return null
        return TileValue(value, unit, "${formatter.date(at)} ${formatter.time(at)}", formatter.backgroundLabel)
    }

    fun spo2Days(samples: List<Spo2Sample>, formatter: RecordFormatter, zone: ZoneId = ZoneId.systemDefault()) =
        days(Metric.SPO2, samples, { it.tsMs }, { it.percent.toFloat() }, "%", zone, formatter, low = true) { "${it.toInt()}" }

    /** A night's readings, when there are any, give the day's value (the day's own vary with the air). */
    fun tempDays(
        samples: List<TempSample>,
        formatter: RecordFormatter,
        zone: ZoneId = ZoneId.systemDefault(),
        fahrenheit: Boolean = false,
    ) = days(
        Metric.SKIN_TEMPERATURE,
        samples,
        { it.tsMs },
        { displayTemp(it.skinC, fahrenheit) },
        if (fahrenheit) "°F" else "°C",
        zone,
        formatter,
        prefer = { it.context == HrContext.SLEEP },
    ) { temp(it) }

    fun stressDays(samples: List<StressSample>, formatter: RecordFormatter, zone: ZoneId = ZoneId.systemDefault()) =
        days(Metric.STRESS, samples.filter { it.score != null }, { it.tsMs }, { it.score!!.toFloat() }, null, zone, formatter) { "${it.toInt()}" }

    /** Readings further apart than this start a new row (a night and an afternoon nap are two). */
    private const val SESSION_GAP_MS = 3 * 3_600_000L

    /**
     * One row per stretch of readings (a night, a nap, an afternoon), not per calendar day: a day
     * row mixed last night's readings with the evening's and sorted them above a measurement
     * taken in between, so the night looked as if it came after it.
     */
    private fun <T> days(
        metric: Metric,
        samples: List<T>,
        ts: (T) -> Long,
        value: (T) -> Float,
        unit: String?,
        @Suppress("UNUSED_PARAMETER") zone: ZoneId,
        formatter: RecordFormatter,
        low: Boolean = false,
        prefer: (T) -> Boolean = { true },
        format: (Float) -> String,
    ): List<MetricReading> {
        val groups = mutableListOf<MutableList<T>>()
        samples.sortedBy(ts).forEach { s ->
            val last = groups.lastOrNull()
            if (last == null || ts(s) - ts(last.last()) > SESSION_GAP_MS) groups += mutableListOf(s) else last += s
        }
        return groups.map { sorted ->
            val counted = sorted.filter(prefer).ifEmpty { sorted }.map(value).sorted()
            val median = counted[counted.size / 2]
            val first = ts(sorted.first())
            val last = ts(sorted.last())
            MetricReading(
                id = "watch-${metric.name}-$first",
                date = formatter.date(last),
                time = if (first == last) formatter.time(last) else "${formatter.time(first)}–${formatter.time(last)}",
                value = format(median),
                unit = unit,
                plot = median,
                details = listOfNotNull(
                    if (low) R.string.detail_lowest to "${format(sorted.minOf(value))}${if (unit == "%") " %" else ""}" else null,
                    R.string.detail_readings to "${sorted.size}",
                ),
                atMs = last,
                fromWatch = true,
                entries = sorted.reversed().map { formatter.time(ts(it)) to listOfNotNull(format(value(it)), unit).joinToString(" ") },
            )
        }
    }

    /** Manual measurements and the watch's stretches together, newest first. */
    fun merge(manual: List<MetricReading>, watch: List<MetricReading>) = (manual + watch).sortedByDescending { it.atMs }
}
