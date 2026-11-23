// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.heartline.logging.HLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext

/** Re-applies background monitoring after a reboot or app update (passive registration, IRN windows, StandTrigger). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val store = runCatching { GlobalContext.get().get<WatchSettingsStore>() }
                    .getOrElse { WatchSettingsStore(app) }
                val settings = store.settings.value
                withTimeoutOrNull(SYNC_TIMEOUT_MS) {
                    runCatching { BackgroundMonitoring.sync(app, settings) }
                        .onFailure { HLog.w(TAG, "monitoring sync failed on $action", it) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "Heartline/Monitor"
        const val SYNC_TIMEOUT_MS = 8_000L
    }
}
