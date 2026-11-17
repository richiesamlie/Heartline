// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.update

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.heartline.phone.R
import com.heartline.phone.ui.components.MarkdownBlocks
import com.heartline.phone.update.UpdateRepository
import com.heartline.shared.text.Changelog
import com.heartline.shared.text.ChangelogEntry
import com.heartline.shared.text.Markdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

/** Loads the changelog notes once when the app version changes after an update. */
class WhatsNewViewModel(
    private val updates: UpdateRepository,
    private val versionName: String,
    private val readChangelog: suspend () -> String,
) : ViewModel() {
    private val mutable = MutableStateFlow<ChangelogEntry?>(null)
    val entry: StateFlow<ChangelogEntry?> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            if (updates.claimVersionChange() != null) {
                val notes = withContext(Dispatchers.IO) { readChangelog() }
                mutable.value = Changelog.entryFor(notes, versionName)?.takeIf { it.notes.isNotBlank() }
            }
        }
    }

    fun dismiss() {
        mutable.value = null
    }
}

@Composable
fun WhatsNewAfterUpdate(versionName: String, onAllNotes: () -> Unit, vm: WhatsNewViewModel = koinViewModel()) {
    val entry by vm.entry.collectAsStateWithLifecycle()
    entry?.let {
        WhatsNewDialog(
            versionName,
            it,
            onDismiss = vm::dismiss,
            onAllNotes = {
                vm.dismiss()
                onAllNotes()
            },
        )
    }
}

@Composable
fun WhatsNewDialog(versionName: String, entry: ChangelogEntry, onDismiss: () -> Unit = {}, onAllNotes: () -> Unit = {}) {
    val uri = LocalUriHandler.current
    val blocks = remember(entry) { Markdown.parse(entry.notes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.whats_new_after_update, versionName)) },
        text = {
            MarkdownBlocks(
                blocks,
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                onLink = { runCatching { uri.openUri(Markdown.resolve(it, "")) } },
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
        dismissButton = { TextButton(onClick = onAllNotes) { Text(stringResource(R.string.whats_new_all)) } },
    )
}
