// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.metric

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.phone.data.HeartRepository
import com.heartline.phone.data.RecordRepository
import com.heartline.phone.format.RecordFormatter
import com.heartline.phone.summary.BackgroundReadings
import com.heartline.phone.summary.BackgroundStress
import com.heartline.phone.summary.BackgroundStressSummary
import com.heartline.phone.summary.BackgroundVitals
import com.heartline.phone.summary.BackgroundVitalsSummary
import com.heartline.phone.summary.MetricFormat
import com.heartline.phone.summary.MetricReading
import com.heartline.phone.summary.Spo2Today
import com.heartline.phone.summary.Spo2TodaySummary
import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordKind
import com.heartline.shared.stress.StressLimits
import com.heartline.shared.vitals.VitalsLimits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId

data class MetricDetailUi(
    val metric: Metric,
    val readings: List<MetricReading> = emptyList(),
    val background: BackgroundVitalsSummary? = null,
    val stress: BackgroundStressSummary? = null,
    /** Blood oxygen only: today's background readings and tries. */
    val spo2Today: Spo2TodaySummary? = null,
) {
    /** The latest measurement of your own (the watch's days are summaries, shown in the history). */
    val latest get() = readings.firstOrNull { !it.fromWatch }

    /** The newest reading of any kind, for "Last measured". */
    val newest get() = readings.firstOrNull()

    val manual get() = readings.filter { !it.fromWatch }
}

class MetricDetailViewModel(
    private val metric: Metric,
    repository: RecordRepository,
    formatter: RecordFormatter,
    /** Background readings from the watch (blood oxygen and skin temperature only). */
    heart: HeartRepository? = null,
    vitalsLimits: Flow<VitalsLimits?> = flowOf(null),
    stressLimits: Flow<StressLimits?> = flowOf(null),
    /** Minutes between background SpO2 tries (the settings' interval), for the next try's time. */
    spo2Every: Flow<Int> = flowOf(60),
    today: LocalDate = LocalDate.now(),
    temperatureFahrenheit: Flow<Boolean> = flowOf(false),
) : ViewModel() {
    private val kind = RecordKind.entries.first { it.metric == metric }
    private val since = today.minusDays(29).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val background: Flow<BackgroundVitalsSummary?> = when {
        heart == null -> flowOf(null)
        metric == Metric.SPO2 -> combine(heart.spo2Since(since), vitalsLimits) { s, l -> BackgroundVitals.spo2(s, l, today.toEpochDay(), formatter) }
        metric == Metric.SKIN_TEMPERATURE -> combine(heart.tempsSince(since), vitalsLimits, temperatureFahrenheit) { s, l, f ->
            BackgroundVitals.temperature(s, l, today.toEpochDay(), formatter, fahrenheit = f)
        }
        else -> flowOf(null)
    }

    private val stress: Flow<BackgroundStressSummary?> = if (heart != null && metric == Metric.STRESS) {
        val weekStart = today.minusDays(6).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        combine(heart.stressSince(weekStart), stressLimits) { s, l -> BackgroundStress.ui(s, l, today, formatter) }
    } else {
        flowOf(null)
    }

    private val spo2Today: Flow<Spo2TodaySummary?> = if (heart != null && metric == Metric.SPO2) {
        val dayStart = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        combine(heart.spo2AttemptsSince(dayStart), heart.spo2Since(dayStart), spo2Every) { a, s, e -> Spo2Today.ui(a, s, dayStart, e, formatter) }
    } else {
        flowOf(null)
    }

    /** The watch's background readings as one history row per day. */
    private val watchDays: Flow<List<MetricReading>> = when {
        heart == null -> flowOf(emptyList())
        metric == Metric.SPO2 -> heart.spo2Since(since).map { BackgroundReadings.spo2Days(it, formatter) }
        metric == Metric.SKIN_TEMPERATURE -> combine(heart.tempsSince(since), temperatureFahrenheit) { s, f ->
            BackgroundReadings.tempDays(s, formatter, fahrenheit = f)
        }
        metric == Metric.STRESS -> heart.stressSince(since).map { BackgroundReadings.stressDays(it, formatter) }
        else -> flowOf(emptyList())
    }

    val state: StateFlow<MetricDetailUi> = combine(
        combine(repository.observe(kind), temperatureFahrenheit) { records, f -> MetricFormat.readings(records, formatter, f) },
        background,
        stress,
        watchDays,
        spo2Today,
    ) { readings, bg, st, days, todayUi ->
        MetricDetailUi(metric, BackgroundReadings.merge(readings, days), bg, st, todayUi)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MetricDetailUi(metric))
}
