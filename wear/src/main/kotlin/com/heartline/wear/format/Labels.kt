// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.format

import androidx.annotation.StringRes
import com.heartline.shared.bp.BpCategory
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.Metric
import com.heartline.wear.R

val Metric.label: Int
    @StringRes get() = when (this) {
        Metric.ECG -> R.string.metric_ecg
        Metric.BLOOD_PRESSURE -> R.string.metric_bp
        Metric.HEART_RATE -> R.string.metric_hr
        Metric.SPO2 -> R.string.metric_spo2
        Metric.SKIN_TEMPERATURE -> R.string.metric_skin_temp
        Metric.BODY_COMPOSITION -> R.string.metric_body
        Metric.STRESS -> R.string.metric_stress
    }

val EcgResult.label: Int
    @StringRes get() = when (this) {
        EcgResult.SINUS_RHYTHM -> R.string.ecg_result_sinus
        EcgResult.AFIB_SIGNS -> R.string.ecg_result_afib
        EcgResult.HIGH_HEART_RATE -> R.string.ecg_result_high_hr
        EcgResult.LOW_HEART_RATE -> R.string.ecg_result_low_hr
        EcgResult.INCONCLUSIVE -> R.string.ecg_result_inconclusive
        EcgResult.POOR_RECORDING -> R.string.ecg_result_poor
        EcgResult.FREQUENT_EXTRA_BEATS -> R.string.ecg_result_frequent_extra_beats
    }

val BpCategory.label: Int
    @StringRes get() = when (this) {
        BpCategory.NORMAL -> R.string.bp_normal
        BpCategory.ELEVATED -> R.string.bp_elevated
        BpCategory.HIGH_STAGE_1 -> R.string.bp_stage1
        BpCategory.HIGH_STAGE_2 -> R.string.bp_stage2
        BpCategory.CRISIS -> R.string.bp_crisis
    }
