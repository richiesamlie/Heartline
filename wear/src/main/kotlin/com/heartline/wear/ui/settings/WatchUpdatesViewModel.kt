// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.shared.update.AppVersion
import com.heartline.shared.update.Release
import com.heartline.wear.link.AppForeground
import com.heartline.wear.update.WatchUpdateStore
import com.heartline.wear.update.WatchUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI state for the watch Updates screen. */
data class WatchUpdatesUi(
    val installed: String,
    val enabled: Boolean,
    val track: AppVersion.Channel = AppVersion.Channel.STABLE,
    val checking: Boolean = false,
    val available: Release? = null,
    val upToDate: Boolean = false,
    val error: String? = null,
    val readyVersion: String? = null,
    val downloading: Pair<Long, Long>? = null
) {
    val showsChannel: Boolean
        get() = AppVersion.parse(installed)?.channel.let { it != null && it != AppVersion.Channel.STABLE }

    val ready: String?
        get() = readyVersion?.takeIf { available == null || it == available.version.toString() }
}

/** Checks, downloads and triggers installation of watch updates directly from GitHub. */
class WatchUpdatesViewModel(
    private val updater: WatchUpdater,
    private val store: WatchUpdateStore,
    installed: String,
    private val appScope: CoroutineScope
) : ViewModel() {
    private val state = MutableStateFlow(WatchUpdatesUi(installed, updater.enabled, store.current().track))
    val ui: StateFlow<WatchUpdatesUi> = combine(state, store.prefs) { s, p ->
        s.copy(track = p.track, readyVersion = p.ready?.version)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value)

    private val intents = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val startActivity: SharedFlow<Intent> = intents.asSharedFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var waitingForPermission = false

    fun check() {
        if (checkJob?.isActive == true || !updater.enabled) return
        checkJob = viewModelScope.launch {
            state.update { it.copy(checking = true, error = null) }
            state.update { current ->
                when (val result = updater.check()) {
                    is WatchUpdater.Check.Available -> current.copy(checking = false, available = result.release, upToDate = false)
                    WatchUpdater.Check.UpToDate -> current.copy(checking = false, available = null, upToDate = true)
                    is WatchUpdater.Check.Failed -> current.copy(checking = false, error = result.message)
                }
            }
        }
    }

    fun cycleTrack() {
        val channels = AppVersion.Channel.entries
        val next = channels[(ui.value.track.ordinal + 1) % channels.size]
        store.setTrack(next)
        state.update { it.copy(available = null, upToDate = false, error = null) }
        check()
    }

    fun install() {
        val current = ui.value
        if (!updater.enabled || downloadJob?.isActive == true) return
        if (!askedToAllow()) return
        if (current.ready != null) {
            installReady()
            return
        }
        val release = current.available ?: return
        state.update { it.copy(error = null, downloading = 0L to (release.watchApk?.size ?: 0L)) }
        downloadJob = appScope.launch {
            try {
                val file = updater.download(release) { done, total ->
                    state.update { it.copy(downloading = done to total) }
                }
                state.update { it.copy(downloading = null) }
                if (AppForeground.resumed) updater.install(file)
            } catch (e: CancellationException) {
                state.update { it.copy(downloading = null) }
                throw e
            } catch (e: Throwable) {
                state.update { it.copy(downloading = null, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun installReady() {
        viewModelScope.launch {
            val file = updater.readyFile()
            if (file == null) {
                val release = ui.value.available
                if (release != null) install() else check()
                return@launch
            }
            runCatching { updater.install(file) }
                .onFailure { e -> state.update { it.copy(error = e.message ?: e.javaClass.simpleName) } }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        state.update { it.copy(downloading = null) }
    }

    private fun askedToAllow(): Boolean {
        if (updater.canInstall()) return true
        waitingForPermission = true
        intents.tryEmit(updater.allowInstallIntent())
        return false
    }

    fun onResume() {
        if (waitingForPermission && updater.canInstall()) {
            waitingForPermission = false
            install()
        }
    }
}
