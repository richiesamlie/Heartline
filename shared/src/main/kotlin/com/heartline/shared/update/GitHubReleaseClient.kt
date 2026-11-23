// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.update

import com.heartline.shared.AppInfo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Reads the project's releases from the GitHub API and downloads release files. No personal data is sent. */
open class GitHubReleaseClient(
    private val userAgent: String,
    private val allowLocalhostForTesting: Boolean = false,
    private val progressThrottleMs: Long = 0L,
    private val log: (String) -> Unit = {}
) {
    open suspend fun releases(): List<Release> = Releases.parse(text(RELEASES_API))

    open suspend fun text(url: String): String = withContext(Dispatchers.IO) {
        open(url).run {
            try {
                inputStream.bufferedReader().use { it.readText() }
            } finally {
                disconnect()
            }
        }
    }

    /**
     * Downloads [url] into [target], continuing a partial [target] from where it stopped (an HTTP
     * `Range` request), and returns the whole file's SHA-256 (lowercase hex). [onProgress] gets
     * the bytes on disk and the total (0 when unknown).
     */
    open suspend fun download(url: String, target: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): String =
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            val have = if (target.isFile) target.length() else 0L
            val connection = open(url, accept = "application/octet-stream", from = have)
            try {
                val code = connection.responseCode
                val append = code == HttpURLConnection.HTTP_PARTIAL
                val length = connection.contentLengthLong
                val total = when {
                    code == RANGE_NOT_SATISFIABLE -> have
                    append -> totalOf(connection.getHeaderField("Content-Range")) ?: if (length > 0) have + length else 0L
                    else -> length.coerceAtLeast(0L)
                }
                val mode = when {
                    append -> "resuming"
                    code == RANGE_NOT_SATISFIABLE -> "complete"
                    else -> "from the start"
                }
                log("download ${target.name}: had $have bytes, HTTP $code, $mode, total $total")
                if (code != RANGE_NOT_SATISFIABLE) {
                    connection.inputStream.use { input ->
                        FileOutputStream(target, append).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = if (append) have else 0L
                            var lastReportedAt = if (progressThrottleMs > 0L) Long.MIN_VALUE else 0L
                            onProgress(done, total)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                done += n
                                if (progressThrottleMs <= 0L) {
                                    onProgress(done, total)
                                } else {
                                    val now = System.nanoTime() / 1_000_000L
                                    val due = lastReportedAt == Long.MIN_VALUE ||
                                        now - lastReportedAt >= progressThrottleMs ||
                                        (total > 0 && done >= total)
                                    if (due) {
                                        lastReportedAt = now
                                        onProgress(done, total)
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            sha256(target)
        }

    private fun parseUrl(raw: String, base: URL? = null): URL = try {
        val uri = if (base == null) URI(raw) else base.toURI().resolve(raw)
        uri.toURL()
    } catch (e: URISyntaxException) {
        throw IOException("Invalid URL: $raw", e)
    } catch (e: IllegalArgumentException) {
        throw IOException("Invalid URL: $raw", e)
    }

    private fun open(url: String, accept: String = "application/vnd.github+json", from: Long = 0): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val parsed = parseUrl(current)
            if (!isAllowedUpdateUrl(parsed)) throw IOException("Refusing non-GitHub or non-HTTPS URL: $current")
            val connection = parsed.openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("User-Agent", userAgent)
            if (from > 0) connection.setRequestProperty("Range", "bytes=$from-")
            val code = connection.responseCode
            if (code in REDIRECT_CODES) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw IOException("Redirect without Location header for $current")
                current = parseUrl(location, parsed).toString()
                return@repeat
            }
            if (code !in 200..299 && !(from > 0 && code == RANGE_NOT_SATISFIABLE)) {
                connection.disconnect()
                throw IOException("HTTP $code for $current")
            }
            return connection
        }
        throw IOException("Too many redirects for $url")
    }

    fun isAllowedUpdateUrl(url: URL): Boolean {
        val host = url.host.lowercase(Locale.ROOT)
        if (allowLocalhostForTesting && (host == "127.0.0.1" || host == "localhost")) return true
        if (!url.protocol.equals("https", ignoreCase = true)) return false
        return host in ALLOWED_HOSTS
    }

    companion object {
        private const val RANGE_NOT_SATISFIABLE = 416
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val ALLOWED_HOSTS = setOf(
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "github-releases.githubusercontent.com"
        )

        /** The total size from a `Content-Range: bytes 100-199/200` header. */
        fun totalOf(contentRange: String?): Long? = contentRange?.substringAfterLast('/', "")?.trim()?.toLongOrNull()

        /** The SHA-256 of [file] (lowercase hex). */
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
        }

        fun safeAssetName(name: String): String {
            val clean = File(name).name
            if (clean.isEmpty() || clean == "." || clean == ".." || clean != name) {
                throw IOException("Invalid update asset name: $name")
            }
            return clean
        }

        const val RELEASES_API =
            "https://api.github.com/repos/${AppInfo.REPO_OWNER}/${AppInfo.REPO_NAME}/releases?per_page=30"
    }
}
