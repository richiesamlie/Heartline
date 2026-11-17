// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import com.heartline.shared.model.Metric

val Metric.icon: ImageVector
    get() = when (this) {
        Metric.ECG -> Icons.Rounded.MonitorHeart
        Metric.BLOOD_PRESSURE -> Icons.Rounded.Speed
        Metric.HEART_RATE -> Icons.Rounded.Favorite
        Metric.SPO2 -> Icons.Rounded.WaterDrop
        Metric.SKIN_TEMPERATURE -> Icons.Rounded.Thermostat
        Metric.BODY_COMPOSITION -> Icons.Rounded.Accessibility
        Metric.STRESS -> Icons.Rounded.SelfImprovement
    }
