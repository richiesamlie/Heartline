// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.format

import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary

/** Shared value formatting for Wear history rows, launcher entries, and tiles. */
object WatchRecordFormat {
    fun lastValue(meta: RecordMeta): String? = when (val s = meta.summary) {
        is RecordSummary.Ecg -> s.averageBpm?.let { "$it bpm" }
        is RecordSummary.BloodPressure -> "${s.systolic}/${s.diastolic}"
        is RecordSummary.Spo2 -> "${s.percent}%"
        is RecordSummary.SkinTemperature -> "%.1f °C".format(s.skinCelsius)
        is RecordSummary.BodyComposition -> "%.1f%%".format(s.bodyFatPercent)
        is RecordSummary.Stress -> "${s.score}/100"
        is RecordSummary.HeartRate -> "${s.bpm} bpm"
    }
}
