// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.format

import android.content.res.Resources
import androidx.annotation.StringRes
import com.heartline.phone.R
import com.heartline.shared.bp.BpCategory
import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HeartTrend
import com.heartline.shared.hr.VitalAlert
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.Metric
import com.heartline.shared.model.Symptom

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

val EcgResult.explanation: Int
    @StringRes get() = when (this) {
        EcgResult.SINUS_RHYTHM -> R.string.ecg_explain_sinus
        EcgResult.AFIB_SIGNS -> R.string.ecg_explain_afib
        EcgResult.HIGH_HEART_RATE -> R.string.ecg_explain_high_hr
        EcgResult.LOW_HEART_RATE -> R.string.ecg_explain_low_hr
        EcgResult.INCONCLUSIVE -> R.string.ecg_explain_inconclusive
        EcgResult.POOR_RECORDING -> R.string.ecg_explain_poor
        EcgResult.FREQUENT_EXTRA_BEATS -> R.string.ecg_explain_frequent_extra_beats
    }

val Symptom.label: Int
    @StringRes get() = when (this) {
        Symptom.PALPITATIONS -> R.string.symptom_palpitations
        Symptom.DIZZINESS -> R.string.symptom_dizziness
        Symptom.FATIGUE -> R.string.symptom_fatigue
        Symptom.SHORTNESS_OF_BREATH -> R.string.symptom_breath
        Symptom.CHEST_PRESSURE -> R.string.symptom_chest
        Symptom.FAINTING -> R.string.symptom_fainting
        Symptom.OTHER -> R.string.symptom_other
    }

val BpCategory.label: Int
    @StringRes get() = when (this) {
        BpCategory.NORMAL -> R.string.bp_normal
        BpCategory.ELEVATED -> R.string.bp_elevated
        BpCategory.HIGH_STAGE_1 -> R.string.bp_stage1
        BpCategory.HIGH_STAGE_2 -> R.string.bp_stage2
        BpCategory.CRISIS -> R.string.bp_crisis
    }

/** Readable sensor names for the stored channel list ("PWA_GREEN,BCG_PTT" → "PPG · motion"). */
internal fun bpChannelNames(res: Resources, channels: String): String = channels.split(',').map {
    when (it.trim()) {
        "PWA_GREEN" -> res.getString(R.string.bp_channel_ppg)
        "PWA_IR" -> res.getString(R.string.bp_channel_ir)
        "BCG_PTT" -> res.getString(R.string.bp_channel_motion)
        "PAT", "ECG_PTT" -> res.getString(R.string.bp_channel_ecg)
        "HYDRO_MAP" -> res.getString(R.string.bp_channel_arm_raise)
        else -> it
    }
}.distinct().joinToString(" · ")

@StringRes
fun alertTitle(alert: HealthAlert): Int = when (alert.vital) {
    VitalAlert.SPO2_LOW -> R.string.alert_spo2_low_title
    VitalAlert.SPO2_NIGHTS -> R.string.alert_spo2_nights_title
    VitalAlert.TEMPERATURE -> R.string.alert_temp_title
    VitalAlert.COMBINED -> R.string.alert_combined_title
    VitalAlert.STRESS -> R.string.alert_stress_title
    null -> when (alert.trend) {
        HeartTrend.ELEVATED_RESTING -> R.string.alert_trend_title
        HeartTrend.HIGH_NORMAL -> R.string.alert_high_normal_title
        HeartTrend.POSTURAL_RISE -> R.string.alert_postural_title
        null -> alertTitle(alert.kind)
    }
}

@StringRes
fun alertTitle(kind: AlertKind): Int = when (kind) {
    AlertKind.IRREGULAR_RHYTHM -> R.string.alert_irn_title
    AlertKind.HIGH_HEART_RATE -> R.string.alert_high_title
    AlertKind.LOW_HEART_RATE -> R.string.alert_low_title
}
