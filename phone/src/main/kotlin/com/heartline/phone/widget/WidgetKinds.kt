// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.heartline.shared.model.Metric

/** Which widget is being set up, what it can be set to, and how big its preview is. */
internal data class WidgetKind(val widget: HeartlineWidget, val previewSize: DpSize, val pick: MetricPick? = null) {
    enum class MetricPick { SHOW, MEASURE }

    companion object {
        private val measurable = listOf(Metric.ECG, Metric.BLOOD_PRESSURE, Metric.SPO2, Metric.STRESS, Metric.SKIN_TEMPERATURE, Metric.BODY_COMPOSITION, Metric.HEART_RATE)

        fun choices(pick: MetricPick): List<Metric> = if (pick == MetricPick.SHOW) Metric.entries.sortedBy { measurable.indexOf(it).let { i -> if (i < 0) 99 else i } } else measurable

        fun of(receiver: String?): WidgetKind? = when (receiver) {
            DashboardWidgetReceiver::class.java.name -> WidgetKind(DashboardWidget(), DpSize(300.dp, 150.dp))
            HeartRateWidgetReceiver::class.java.name -> WidgetKind(HeartRateWidget(), DpSize(160.dp, 160.dp))
            EcgWidgetReceiver::class.java.name -> WidgetKind(EcgWidget(), DpSize(160.dp, 160.dp))
            BpWidgetReceiver::class.java.name -> WidgetKind(BpWidget(), DpSize(160.dp, 160.dp))
            StressWidgetReceiver::class.java.name -> WidgetKind(StressWidget(), DpSize(160.dp, 160.dp))
            QuickMeasureWidgetReceiver::class.java.name -> WidgetKind(QuickMeasureWidget(), DpSize(300.dp, 64.dp))
            HeartDayWidgetReceiver::class.java.name -> WidgetKind(HeartDayWidget(), DpSize(300.dp, 160.dp))
            MetricTileSmallReceiver::class.java.name -> WidgetKind(MetricTileWidget(), DpSize(72.dp, 72.dp), MetricPick.SHOW)
            MetricTileSlimReceiver::class.java.name -> WidgetKind(MetricTileWidget(), DpSize(160.dp, 64.dp), MetricPick.SHOW)
            MetricTileReceiver::class.java.name -> WidgetKind(MetricTileWidget(), DpSize(160.dp, 160.dp), MetricPick.SHOW)
            MeasureButtonReceiver::class.java.name -> WidgetKind(MeasureButtonWidget(), DpSize(72.dp, 72.dp), MetricPick.MEASURE)
            else -> null
        }
    }
}
