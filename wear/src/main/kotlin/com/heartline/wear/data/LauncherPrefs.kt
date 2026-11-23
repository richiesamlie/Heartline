// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.data

import android.content.Context
import com.heartline.shared.model.Metric
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which metrics the user pinned to the top of the launcher (watch only). */
class LauncherPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val pinned: StateFlow<List<Metric>> = state.asStateFlow()

    private fun load(): List<Metric> =
        prefs.getString(KEY, null).orEmpty().split(',').mapNotNull { n -> Metric.entries.firstOrNull { it.name == n } }

    @Synchronized
    fun toggle(metric: Metric) {
        val next = if (metric in state.value) state.value - metric else state.value + metric
        prefs.edit().putString(KEY, next.joinToString(",") { it.name }).apply()
        state.value = next
    }

    /** True the first time [kind] is claimed on [day] (a celebration plays once a day). */
    @Synchronized
    fun claim(kind: String, day: Long): Boolean {
        val key = "celebrated_$kind"
        if (prefs.getLong(key, -1) == day) return false
        prefs.edit().putLong(key, day).apply()
        return true
    }

    private companion object {
        const val KEY = "pinned"
    }
}
