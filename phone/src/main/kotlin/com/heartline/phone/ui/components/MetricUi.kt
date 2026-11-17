// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.heartline.phone.format.label
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.model.EcgResult
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

/** Coloured dot + result name, as on the SHM result list. */
@Composable
fun ResultBadge(result: EcgResult, modifier: Modifier = Modifier, large: Boolean = false) {
    val color = HeartlineTheme.colors.severity(result.severity)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(if (large) 12.dp else 9.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            stringResource(result.label),
            style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
            color = HeartlineTheme.colors.onBackground,
        )
    }
}
