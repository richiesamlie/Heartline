// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.phone.data.BpRepository
import com.heartline.phone.data.HeartRepository
import com.heartline.phone.data.RecordRepository
import com.heartline.phone.data.SettingsRepository
import com.heartline.phone.ui.RecordFormatter
import com.heartline.phone.ui.ecg.EcgRecordUi
import com.heartline.phone.ui.metric.BackgroundReadings
import com.heartline.phone.ui.metric.MetricFormat
import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Latest value of a non-ECG metric for its Home tile. */
data class TileValue(val value: String, val unit: String?, val caption: String, val detail: String? = null)

data class HomeState(
    val watchName: String? = null,
    val latestEcg: EcgRecordUi? = null,
    val tiles: Map<Metric, TileValue> = emptyMap(),
    /** Greeting on top of Home: the user's name (when set) and the part of the day. */
    val name: String? = null,
    val dayPart: com.heartline.shared.profile.DayPart = com.heartline.shared.profile.DayPart.MORNING,
    val birthday: Boolean = false,
)

class HomeViewModel(
    repository: RecordRepository,
    heart: HeartRepository,
    settings: SettingsRepository,
    bp: BpRepository,
    formatter: RecordFormatter,
    profiles: com.heartline.phone.data.ProfileRepository? = null,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val profile = profiles?.profile ?: kotlinx.coroutines.flow.flowOf(null)

    private val others = combine(
        combine(
            repository.observe(RecordKind.SPO2),
            repository.observe(RecordKind.SKIN_TEMPERATURE),
            repository.observe(RecordKind.BODY_COMPOSITION),
            repository.observe(RecordKind.STRESS),
            heart.backgroundLatest,
        ) { spo2, temp, body, stress, background ->
            listOf(Metric.SPO2 to spo2, Metric.SKIN_TEMPERATURE to temp, Metric.BODY_COMPOSITION to body, Metric.STRESS to stress) to background
        },
        settings.monitor.map { it.temperatureFahrenheit },
    ) { (lists, background), fahrenheit ->
        buildMap {
            lists.forEach { (metric, list) ->
                MetricFormat.readings(list.take(8), formatter, fahrenheit).firstOrNull()?.let { r ->
                    put(metric, TileValue(r.value, r.unit, "${r.date} ${r.time}", r.details.firstOrNull()?.second))
                }
                // The watch's background reading, when newer than the last measurement.
                BackgroundReadings.tile(metric, background, list.firstOrNull()?.entity?.startedAtMs ?: 0, formatter, fahrenheit)?.let {
                    put(metric, it)
                }
            }
        }
    }

    private val latestEcg = repository.observe(RecordKind.ECG)
        .map { it.firstOrNull() }
        .distinctUntilChangedBy { r -> r?.let { Triple(it.id, it.entity.wavePath, it.summary) } }
        .map { r -> r?.let { formatter.ecg(it, repository.displayWave(it)) } }

    val state: StateFlow<HomeState> = combine(
        latestEcg,
        heart.latestMinute,
        settings.monitor,
        combine(bp.readings, bp.calibration) { r, c -> r to c },
        others,
    ) { ecg, minute, _, (bpReadings, calibration), otherTiles ->
        val tiles = buildMap {
            putAll(otherTiles)
            bpReadings.firstOrNull()?.let { r ->
                val s = r.summary as RecordSummary.BloodPressure
                val detail = if (calibration?.isValid(now()) == true) {
                    formatter.calibrationDaysLeft(calibration.daysLeft(now()))
                } else {
                    formatter.calibrationNeeded
                }
                put(
                    Metric.BLOOD_PRESSURE,
                    TileValue("${s.systolic}/${s.diastolic}", "mmHg", "${formatter.date(r.entity.startedAtMs)} ${formatter.time(r.entity.startedAtMs)}", detail),
                )
            }
            minute?.let {
                put(Metric.HEART_RATE, TileValue("${it.avgBpm}", "bpm", "${formatter.date(it.minuteStartMs)} ${formatter.time(it.minuteStartMs)}"))
            }
        }
        HomeState(
            latestEcg = ecg,
            tiles = tiles,
        )
    }.combine(profile) { home, p ->
        val local = java.time.Instant.ofEpochMilli(now()).atZone(java.time.ZoneId.systemDefault())
        home.copy(
            name = p?.displayName?.takeIf { it.isNotBlank() },
            dayPart = com.heartline.shared.profile.DayPart.of(local.hour),
            birthday = p?.isBirthday(local.toLocalDate()) == true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())
}
