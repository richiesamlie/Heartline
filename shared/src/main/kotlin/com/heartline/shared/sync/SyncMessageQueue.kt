// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Serializes inbound control messages in arrival order on a background consumer coroutine. */
class SyncMessageQueue(scope: CoroutineScope, private val onError: (Throwable) -> Unit = {}) {
    private val channel = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (task in channel) {
                try {
                    task()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    onError(e)
                }
            }
        }
    }

    fun submit(task: suspend () -> Unit) {
        channel.trySend(task)
    }
}
