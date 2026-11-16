// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.diag

/** Replaces the user's names and birth date in log text, so exported logs don't identify them. */
class Redactor(names: Collection<String> = emptyList(), private val birthDate: String? = null) {
    // Longest first, so "Sara Smith" is replaced before "Sara"; short ASCII names (< 3) would hit ordinary words,
    // while non-ASCII names (e.g. 2-char CJK names) don't collide with English words and lack \b boundaries.
    private val names = names.map { it.trim() }
        .filter { it.length >= 3 || (it.length >= 2 && it.any { ch -> ch.code > 127 }) }
        .distinct()
        .sortedByDescending { it.length }
    private val patterns = this.names.map { name ->
        if (name.any { ch -> ch.code > 127 }) {
            Regex(Regex.escape(name))
        } else {
            Regex("\\b" + Regex.escape(name) + "\\b", RegexOption.IGNORE_CASE)
        }
    }

    fun redact(text: String): String {
        var out = text
        for (p in patterns) out = p.replace(out, NAME)
        if (!birthDate.isNullOrBlank()) out = out.replace(birthDate, BIRTH_DATE)
        return out
    }

    companion object {
        const val NAME = "<name>"
        const val BIRTH_DATE = "<birthdate>"
        val NONE = Redactor()

        /** Hides every name and the birth date in [profile]. */
        fun of(profile: com.heartline.shared.profile.UserProfile?): Redactor = if (profile == null) {
            NONE
        } else {
            Redactor(
                listOf(profile.firstName, profile.lastName, profile.preferredName, "${profile.firstName} ${profile.lastName}"),
                profile.birthDate
            )
        }
    }
}
