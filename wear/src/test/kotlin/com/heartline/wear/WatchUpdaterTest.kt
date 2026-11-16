// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.shared.update.AppVersion
import com.heartline.shared.update.GitHubAsset
import com.heartline.shared.update.Release
import com.heartline.wear.update.WatchReleaseSource
import com.heartline.wear.update.WatchUpdateStore
import com.heartline.wear.update.WatchUpdater
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies watch self-update checking, downloading, checksum validation, cleanup and URL policy. */
@RunWith(AndroidJUnit4::class)
class WatchUpdaterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val apkBytes = Random(5).nextBytes(50_000)
    private val apk = GitHubAsset("Heartline-watch-1.3.0.apk", apkBytes.size.toLong(), "https://example.invalid/watch.apk")
    private val sums = GitHubAsset("SHA256SUMS", url = "https://example.invalid/sums")
    private val dir get() = File(context.filesDir, "updates")

    private inner class FakeSource : WatchReleaseSource("test") {
        var downloads = 0
        var resumedFrom = -1L
        var sumsSha = sha(apkBytes)
        var releaseList: List<Release> = listOf(
            Release(
                version = AppVersion.parse("1.3.0")!!,
                tag = "v1.3.0",
                notes = "Watch release",
                pageUrl = "https://github.com/example/heartline/releases/tag/v1.3.0",
                publishedAt = "2026-10-12T10:00:00Z",
                phoneApk = null,
                watchApk = apk,
                checksums = sums
            )
        )

        override suspend fun releases(): List<Release> = releaseList

        override suspend fun text(url: String) = "$sumsSha  ${apk.name}\n"

        override suspend fun download(url: String, target: File, onProgress: (Long, Long) -> Unit): String {
            downloads++
            resumedFrom = if (target.isFile) target.length() else 0
            target.parentFile?.mkdirs()
            target.appendBytes(apkBytes.copyOfRange(resumedFrom.toInt().coerceAtMost(apkBytes.size), apkBytes.size))
            onProgress(target.length(), apkBytes.size.toLong())
            return sha(target.readBytes())
        }
    }

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val store = WatchUpdateStore(context, "1.2.0")
    private val source = FakeSource()

    private fun updater(installed: String = "1.2.0", enabled: Boolean = true) =
        WatchUpdater(context, source, WatchUpdateStore(context, installed), installed, enabled = enabled)

    @Before
    fun reset() {
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().clear().commit()
        dir.deleteRecursively()
    }

    @Test
    fun checkFindsWatchUpdateFromReleases() = runBlocking {
        val result = updater().check()
        assertTrue(result is WatchUpdater.Check.Available)
        assertEquals("1.3.0", (result as WatchUpdater.Check.Available).release.version.toString())
    }

    @Test
    fun aVerifiedDownloadIsKeptAsReady() = runBlocking {
        val file = updater().download("1.3.0", apk, sums)
        assertEquals(File(dir, apk.name), file)
        assertArrayEquals(apkBytes, file.readBytes())
        assertFalse(File(dir, "${apk.name}.part").exists())
        assertEquals(WatchUpdateStore.Ready("1.3.0", apk.name, sha(apkBytes)), WatchUpdateStore(context, "1.2.0").current().ready)
    }

    @Test
    fun theReadyUpdateIsReusedWithoutDownloading() = runBlocking {
        updater().download("1.3.0", apk, sums)
        val again = updater().download("1.3.0", apk, sums)
        assertEquals(1, source.downloads)
        assertArrayEquals(apkBytes, again.readBytes())
        assertNotNull(updater().readyFile())
    }

    @Test
    fun aDamagedReadyFileIsDownloadedAgain() = runBlocking {
        val file = updater().download("1.3.0", apk, sums)
        file.writeBytes(ByteArray(10))
        assertNull(updater().readyFile())
        assertNull(store.current().ready)
        updater().download("1.3.0", apk, sums)
        assertEquals(2, source.downloads)
        assertArrayEquals(apkBytes, File(dir, apk.name).readBytes())
    }

    @Test
    fun aPartialDownloadContinuesWhereItStopped() = runBlocking {
        dir.mkdirs()
        File(dir, "${apk.name}.part").writeBytes(apkBytes.copyOf(20_000))
        updater().download("1.3.0", apk, sums)
        assertEquals(20_000L, source.resumedFrom)
        assertArrayEquals(apkBytes, File(dir, apk.name).readBytes())
    }

    @Test
    fun aChecksumMismatchKeepsNothing() = runBlocking {
        source.sumsSha = sha(ByteArray(1))
        try {
            updater().download("1.3.0", apk, sums)
            fail("expected a checksum mismatch")
        } catch (_: WatchUpdater.ChecksumMismatch) {
        }
        assertNull(WatchUpdateStore(context, "1.2.0").current().ready)
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun theDownloadIsDeletedOnceInstalled() = runBlocking {
        updater().download("1.3.0", apk, sums)
        updater("1.2.0").cleanUp()
        assertNotNull(WatchUpdateStore(context, "1.2.0").current().ready)
        assertTrue(File(dir, apk.name).exists())
        updater("1.3.0").cleanUp()
        assertNull(WatchUpdateStore(context, "1.3.0").current().ready)
        assertFalse(dir.exists())
    }

    @Test
    fun pathTraversalAssetNameIsRejected() = runBlocking {
        val evil = GitHubAsset("../evil.apk", 10L, "https://example.invalid/evil")
        try {
            updater().download("1.3.0", evil, sums)
            fail("expected invalid asset name to fail")
        } catch (_: IOException) {
        }
        assertFalse(File(context.filesDir, "evil.apk.part").exists())
    }

    @Test
    fun urlAllowlistRejectsCleartextAndUntrustedHosts() {
        val realSource = WatchReleaseSource("HeartlineTest/1.0")
        assertThrows(IOException::class.java) {
            runBlocking { realSource.text("http://api.github.com/repos/example/repo/releases") }
        }
        assertThrows(IOException::class.java) {
            runBlocking { realSource.download("https://evil.example.com/app.apk", File.createTempFile("watch", ".apk")) }
        }
        assertFalse(realSource.isAllowedUpdateUrl(URL("https://raw.githubusercontent.com/org/repo/main/app.apk")))
        assertTrue(realSource.isAllowedUpdateUrl(URL("https://release-assets.githubusercontent.com/github-production-release-asset/1")))
    }
}
