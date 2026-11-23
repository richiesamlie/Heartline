// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.update

import com.heartline.logging.HLog
import com.heartline.shared.update.GitHubReleaseClient
import java.io.File

/** Reads the project's releases from the GitHub API and downloads release files. No personal data is sent. */
open class ReleaseSource(
    userAgent: String,
    allowLocalhostForTesting: Boolean = false,
) : GitHubReleaseClient(
    userAgent = userAgent,
    allowLocalhostForTesting = allowLocalhostForTesting,
    log = { HLog.i(TAG, it) },
) {
    companion object {
        private const val TAG = "Heartline/Update"
        const val RELEASES_API = GitHubReleaseClient.RELEASES_API

        /** The total size from a `Content-Range: bytes 100-199/200` header. */
        internal fun totalOf(contentRange: String?): Long? = GitHubReleaseClient.totalOf(contentRange)

        /** The SHA-256 of [file] (lowercase hex). */
        fun sha256(file: File): String = GitHubReleaseClient.sha256(file)
    }
}
