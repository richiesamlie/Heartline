// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.phone.data.ProfileRepository
import com.heartline.shared.profile.UserProfile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfileViewModel(private val repository: ProfileRepository) : ViewModel() {
    val profile: StateFlow<UserProfile?> = repository.profile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** A changed weight is stamped with now, so the watch won't ask for it again for a month. */
    fun save(profile: UserProfile, onSaved: () -> Unit) = viewModelScope.launch {
        val old = this@ProfileViewModel.profile.value ?: repository.profile.first()
        val stamped = if (old != null && old.weightKg == profile.weightKg) {
            profile.copy(weightUpdatedAtMs = old.weightUpdatedAtMs)
        } else {
            profile.copy(weightUpdatedAtMs = System.currentTimeMillis())
        }
        repository.save(stamped)
        onSaved()
    }
}
