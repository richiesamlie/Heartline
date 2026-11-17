// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.phone.link.OpenResult
import com.heartline.phone.link.PhoneStatusPublisher
import com.heartline.phone.link.WatchLinkUi
import com.heartline.phone.link.WatchOpener
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** "Measure on watch" buttons: opens the screen on the watch and reports how it went. */
class OpenOnWatchViewModel(private val opener: WatchOpener) : ViewModel() {
    private val results = MutableSharedFlow<OpenResult>(extraBufferCapacity = 4)
    val events: SharedFlow<OpenResult> = results.asSharedFlow()

    fun open(route: String) = viewModelScope.launch { results.emit(opener.open(route)) }
}

/** The watch connection card (Home, onboarding). Re-probed whenever the screen asks. */
class WatchLinkViewModel(private val opener: WatchOpener, status: PhoneStatusPublisher) : ViewModel() {
    private val probe = MutableStateFlow<WatchLinkUi?>(null)
    val link: StateFlow<WatchLinkUi?> = probe.asStateFlow()
    val setupComplete: StateFlow<Boolean> = status.status.map { it.setupComplete }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun refresh() = viewModelScope.launch { probe.value = opener.probe() }

    fun openWatchApp() = viewModelScope.launch { opener.open("") }
}
