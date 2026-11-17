// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.share

import android.content.res.Resources
import com.heartline.phone.R
import com.heartline.phone.report.EcgDetailRows
import com.heartline.phone.ui.bp.bpChannelNames
import com.heartline.phone.ui.bp.label
import com.heartline.phone.ui.components.explanation
import com.heartline.phone.ui.components.label
import com.heartline.phone.ui.bp.BpHomeUi
import com.heartline.phone.ui.ecg.EcgRecordUi
import com.heartline.phone.ui.heart.HeartRateUi
import com.heartline.shared.bp.BpSafety
import com.heartline.shared.pots.PosturalStatus
import kotlin.math.roundToInt

/**
 * Plain-text summaries sent to an AI assistant: the user's prompt first, then the measured values
 * in a compact, unambiguous form, and a reminder that this is wellness data, not a diagnosis.
 */
object ResultSummary {
    fun prompt(res: Resources, custom: String?) = custom?.takeIf { it.isNotBlank() } ?: res.getString(R.string.ai_default_prompt)

    fun ecg(res: Resources, record: EcgRecordUi, person: String?, prompt: String): String = buildString {
        appendLine(prompt)
        appendLine()
        appendLine(res.getString(R.string.ai_ecg_header))
        person?.let { appendLine(res.getString(R.string.ai_person, it)) }
        appendLine("${res.getString(R.string.ai_result)}: ${res.getString(record.result.label)}")
        appendLine("${res.getString(R.string.ai_recorded)}: ${record.date} ${record.time}")
        record.averageBpm?.let { appendLine("${res.getString(R.string.ecg_avg_hr)}: ${res.getString(R.string.ecg_bpm_value, it)}") }
        record.metrics?.let { m ->
            EcgDetailRows.rows(res, m).forEach { (k, v) -> appendLine("$k: $v") }
            m.secondOpinionAfProbability?.let { prob ->
                appendLine(res.getString(R.string.ai_ecg_af_prob, (prob * 100.0).roundToInt()))
            }
        }
        if (record.symptoms.isNotEmpty()) appendLine("${res.getString(R.string.ecg_symptoms)}: ${record.symptoms.joinToString { res.getString(it.label) }}")
        appendLine("${res.getString(R.string.ai_about_result)}: ${res.getString(record.result.explanation)}")
        appendLine()
        append(res.getString(R.string.ai_disclaimer))
    }

    fun bp(res: Resources, state: BpHomeUi, person: String?, prompt: String): String = buildString {
        appendLine(prompt)
        appendLine()
        appendLine(res.getString(R.string.ai_bp_header))
        person?.let { appendLine(res.getString(R.string.ai_person, it)) }
        state.readings.take(14).forEach { r ->
            val unit = r.uncertainty?.let { res.getString(R.string.bp_unit_uncertainty, it) } ?: res.getString(R.string.unit_mmhg)
            val tags = listOfNotNull(
                res.getString(if (r.wideRange) R.string.bp_tag_wide else r.category.label),
                r.pulse?.let { res.getString(R.string.ai_bp_pulse, it) },
                res.getString(R.string.bp_tag_beyond).takeIf { r.beyondCalibration && !r.confirmed },
                res.getString(R.string.bp_tag_confirmed).takeIf { r.confirmed },
                if (r.refined) res.getString(R.string.ai_bp_refined_from, r.watchSystolic ?: 0, r.watchDiastolic ?: 0) else null,
                when (r.bodyState) {
                    "TRANSIENT" -> res.getString(R.string.bp_tag_transient)
                    "COMPENSATORY" -> res.getString(R.string.bp_tag_compensatory)
                    "IRREGULAR" -> res.getString(R.string.bp_tag_irregular)
                    else -> null
                },
                when (r.safety) {
                    BpSafety.VERY_HIGH -> res.getString(R.string.bp_safety_high)
                    BpSafety.LOW -> res.getString(R.string.bp_safety_low)
                    BpSafety.NONE -> null
                },
                r.channels?.takeIf { it.isNotBlank() }?.let { res.getString(R.string.bp_channels, bpChannelNames(res, it)) },
            ).joinToString(", ")
            appendLine("${r.date} ${r.time}: ${r.systolic}/${r.diastolic} $unit ($tags)")
        }
        state.average7?.let { appendLine("${res.getString(R.string.bp_avg7)}: ${it.first}/${it.second} ${res.getString(R.string.unit_mmhg)}") }
        state.average30?.let { appendLine("${res.getString(R.string.bp_avg30)}: ${it.first}/${it.second} ${res.getString(R.string.unit_mmhg)}") }
        state.accuracy?.let { a ->
            appendLine(res.getString(R.string.ai_bp_accuracy, a.count, a.meanDiffSys, a.sdSys, a.meanDiffDia, a.sdDia))
        }
        appendLine(res.getString(R.string.ai_bp_method))
        appendLine()
        append(res.getString(R.string.ai_disclaimer))
    }

    fun heartRate(res: Resources, state: HeartRateUi, person: String?, prompt: String): String = buildString {
        appendLine(prompt)
        appendLine()
        appendLine(res.getString(R.string.ai_hr_header))
        person?.let { appendLine(res.getString(R.string.ai_person, it)) }
        state.latestBpm?.let { appendLine("${res.getString(R.string.ai_latest)}: ${res.getString(R.string.ecg_bpm_value, it)} (${state.latestTime.orEmpty()})") }
        state.restingBpm?.let { appendLine("${res.getString(R.string.hr_resting)}: ${res.getString(R.string.ecg_bpm_value, it)}") }
        if (state.minBpm != null && state.maxBpm != null) {
            appendLine("${res.getString(R.string.ai_today_range)}: ${res.getString(R.string.value_bpm_range, state.minBpm, state.maxBpm)}")
        }
        state.week.forEachIndexed { i, b ->
            val label = state.weekDates.getOrElse(b.index) { res.getString(R.string.ai_day_fallback, i + 1) }
            appendLine("$label: ${res.getString(R.string.value_bpm_range, b.min, b.max)}")
        }
        state.hrvTodayMs?.let { appendLine(res.getString(R.string.ai_hrv_rmssd, it)) }
        appendLine()
        append(res.getString(R.string.ai_disclaimer))
    }

    /** The report of rises after getting up, for AI apps: the summary and each rise, no conclusions. */
    fun postural(res: Resources, state: com.heartline.phone.ui.heart.PosturalReportUi, person: String?, prompt: String): String = buildString {
        appendLine(prompt)
        appendLine()
        appendLine(res.getString(R.string.postural_report_title_pdf))
        person?.let { appendLine(res.getString(R.string.ai_person, it)) }
        val statusText = when (state.status) {
            PosturalStatus.EASING -> res.getString(R.string.postural_status_easing)
            PosturalStatus.PATTERN -> res.getString(R.string.postural_status_pattern)
            PosturalStatus.NONE -> res.getString(R.string.ai_postural_status_none)
        }
        appendLine("${res.getString(R.string.ai_postural_status)}: $statusText")
        appendLine(res.getString(R.string.ai_postural_threshold, state.minRiseBpm))
        appendLine(res.getString(R.string.postural_report_guidance))
        appendLine("${res.getString(R.string.postural_days_label)}: ${res.getString(R.string.postural_days_value, state.days, com.heartline.phone.ui.heart.PosturalSummaries.PATTERN_DAYS)}")
        state.medianRiseBpm?.let {
            appendLine("${res.getString(R.string.postural_report_rise)}: +${res.getString(R.string.ecg_bpm_value, it)} (${state.medianRestBpm} → ${res.getString(R.string.ecg_bpm_value, state.medianPeakBpm ?: 0)})")
        }
        state.rises.forEach { r ->
            val source = res.getString(if (r.measured) R.string.ai_postural_source_measured else R.string.ai_postural_source_passive)
            val felt = r.symptoms.joinToString { res.getString(it.label) }
            val extra = listOfNotNull(source, felt.takeIf { it.isNotEmpty() }).joinToString(" · ")
            appendLine("${r.date} ${r.time}: ${r.restBpm} → ${res.getString(R.string.ecg_bpm_value, r.peakBpm)} (+${r.riseBpm}) · $extra")
        }
        appendLine()
        append(res.getString(R.string.ai_disclaimer))
    }
}
