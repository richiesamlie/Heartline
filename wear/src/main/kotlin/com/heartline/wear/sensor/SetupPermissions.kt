// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sensor

import android.Manifest
import android.os.Build
import com.heartline.shared.model.Metric
import com.heartline.shared.sensor.PermissionPolicy

/** Asked for once, during setup, so no feature has to interrupt the user for permissions later. */
object SetupPermissions {
    /** Sensor access: every feature needs it, so the gate waits for it. */
    val required: Array<String>
        get() = Metric.entries.flatMap { PermissionPolicy.permissionsFor(it, Build.VERSION.SDK_INT) }.distinct().toTypedArray()

    /** Also asked for, but optional (alerts, activity context). */
    val requested: Array<String>
        get() = (
            required.toList() +
                Manifest.permission.ACTIVITY_RECOGNITION +
                (if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
            ).distinct().toTypedArray()
}
