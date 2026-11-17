// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.quick

import android.content.Context
import com.heartline.shared.model.Metric
import com.heartline.shared.profile.UserProfile
import com.heartline.shared.sync.Protocol
import com.heartline.wear.sensor.QuickSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString

/** The user profile (for body composition), received from the phone. */
class WatchProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(
        prefs.getString(KEY, null)?.let { runCatching { Protocol.json.decodeFromString<UserProfile>(it) }.getOrNull() },
    )
    val profile: StateFlow<UserProfile?> = state.asStateFlow()

    fun update(profile: UserProfile) {
        prefs.edit().putString(KEY, Protocol.json.encodeToString(profile)).apply()
        state.value = profile
    }

    private companion object {
        const val KEY = "profile"
    }
}

/** The on-demand wellness sources available on this build, by metric. */
class QuickSources(sources: List<QuickSource>) {
    private val byMetric = sources.associateBy { it.metric }

    operator fun get(metric: Metric): QuickSource? = byMetric[metric]

    val metrics: Set<Metric> get() = byMetric.keys
}
