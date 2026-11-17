// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.ecg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.phone.data.RecordRepository
import com.heartline.phone.ui.RecordFormatter
import com.heartline.shared.model.EcgMetrics
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.Symptom
import com.heartline.shared.sync.PhoneSyncEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val WHILE_SUBSCRIBED = SharingStarted.WhileSubscribed(5_000)

/** An ECG record ready for display; [samples] is loaded only where a waveform is shown. */
data class EcgRecordUi(
    val id: String,
    val month: String,
    val date: String,
    val time: String,
    val result: EcgResult,
    val averageBpm: Int?,
    val symptoms: List<Symptom>,
    val durationSec: Int,
    val sampleRateHz: Int,
    val samples: FloatArray? = null,
    val metrics: EcgMetrics? = null,
)

data class EcgListState(val records: List<EcgRecordUi> = emptyList(), val loading: Boolean = true) {
    val latest get() = records.firstOrNull()
}

/** ECG list for the ECG home and history screens; the latest record also carries its waveform. */
@OptIn(ExperimentalCoroutinesApi::class)
class EcgListViewModel(private val repository: RecordRepository, private val formatter: RecordFormatter) : ViewModel() {
    val state: StateFlow<EcgListState> = repository.observe(RecordKind.ECG)
        .flatMapLatest { records ->
            flow {
                val latestWave = records.firstOrNull()?.let { repository.displayWave(it) }
                emit(
                    EcgListState(
                        records.mapIndexed { i, r -> formatter.ecg(r, if (i == 0) latestWave else null) },
                        loading = false,
                    ),
                )
            }
        }
        .stateIn(viewModelScope, WHILE_SUBSCRIBED, EcgListState())
}

@OptIn(ExperimentalCoroutinesApi::class)
class EcgDetailViewModel(
    private val id: String,
    private val repository: RecordRepository,
    private val sync: PhoneSyncEngine,
    private val formatter: RecordFormatter,
) : ViewModel() {
    val state: StateFlow<EcgRecordUi?> = repository.observe(id)
        .flatMapLatest { record -> flow { emit(record?.let { formatter.ecg(it, repository.displayWave(it)) }) } }
        .stateIn(viewModelScope, WHILE_SUBSCRIBED, null)

    fun updateSymptoms(symptoms: List<Symptom>) = viewModelScope.launch { repository.updateEcgSymptoms(id, symptoms, null) }

    /** Deletes here and asks the watch to drop its copy too. */
    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        sync.requestDelete(id)
        onDone()
    }
}
