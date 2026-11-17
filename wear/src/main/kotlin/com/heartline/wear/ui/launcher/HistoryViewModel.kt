// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui.launcher

import android.text.format.DateUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.shared.model.RecordSummary
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.format.WatchRecordFormat
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class HistoryViewModel(store: WatchRecordStore, private val now: () -> Long = System::currentTimeMillis) : ViewModel() {
    val items: StateFlow<List<HistoryItem>> = store.historyEntries.map { rows ->
        rows.map { row ->
            val meta = row.meta
            HistoryItem(
                id = meta.id,
                metric = meta.kind.metric,
                result = (meta.summary as? RecordSummary.Ecg)?.result,
                value = WatchRecordFormat.lastValue(meta),
                time = DateUtils.getRelativeTimeSpanString(meta.startedAtMs, now(), DateUtils.MINUTE_IN_MILLIS).toString(),
                synced = row.delivered,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
