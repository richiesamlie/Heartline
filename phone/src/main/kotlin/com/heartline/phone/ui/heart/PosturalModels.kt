// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.heart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.phone.data.HeartRepository
import com.heartline.phone.format.RecordFormatter
import com.heartline.phone.summary.PosturalReportSummary
import com.heartline.phone.summary.PosturalSummaries
import com.heartline.shared.model.Symptom
import com.heartline.shared.pots.OrthostaticEventDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** The report of rises after getting up, and each rise's detail. */
class PosturalViewModel(
    private val repository: HeartRepository,
    private val formatter: RecordFormatter,
    age: Flow<Int?> = flowOf(null),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: Locale = Locale.getDefault(),
    private val now: () -> Long = System::currentTimeMillis,
    private val reports: com.heartline.phone.report.PosturalReportBuilder? = null,
) : ViewModel() {
    private val from = now() - (maxOf(2 * PosturalSummaries.PATTERN_DAYS, PosturalSummaries.CHART_DAYS) + 1) * 86_400_000L

    val state: StateFlow<PosturalReportSummary> = combine(repository.posturalSince(from), repository.riseSymptoms, repository.alerts, age) { (minutes, probes), symptoms, alerts, years ->
        val nowMs = now()
        val minRise = OrthostaticEventDetector.minRiseFor(years)
        PosturalSummaries.report(
            PosturalSummaries.events(minutes, probes, nowMs, minRise),
            symptoms,
            PosturalSummaries.lastNotice(alerts),
            nowMs,
            minRise,
            LocalDate.now(zone),
            zone,
            locale,
        ) { t -> formatter.date(t) to formatter.time(t) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PosturalReportSummary())

    fun setSymptoms(startMs: Long, symptoms: List<Symptom>) = viewModelScope.launch { repository.setRiseSymptoms(startMs, symptoms) }

    fun exportReport(
        state: PosturalReportSummary,
        profile: com.heartline.shared.profile.UserProfile?,
        name: String?,
        fileName: String,
    ): java.io.File = checkNotNull(reports) { "PosturalReportBuilder missing" }.export(state, profile, name, fileName)
}
