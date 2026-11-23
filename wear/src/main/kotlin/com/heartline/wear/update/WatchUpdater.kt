// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.heartline.logging.HLog
import com.heartline.shared.update.AppVersion
import com.heartline.shared.update.GitHubAsset
import com.heartline.shared.update.GitHubReleaseClient
import com.heartline.shared.update.Release
import com.heartline.shared.update.Releases
import com.heartline.wear.R
import com.heartline.wear.link.AppForeground
import com.heartline.wear.monitor.WatchNotifier
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Reads the project's releases from the GitHub API and downloads watch release files. */
open class WatchReleaseSource(
    userAgent: String,
    allowLocalhostForTesting: Boolean = false
) : GitHubReleaseClient(
    userAgent = userAgent,
    allowLocalhostForTesting = allowLocalhostForTesting,
    progressThrottleMs = 200L,
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

/** Watch update preferences and verified downloaded APK state. */
class WatchUpdateStore(context: Context, installedVersion: String) {
    data class Ready(val version: String, val file: String, val sha256: String)

    data class Prefs(val track: AppVersion.Channel, val ready: Ready? = null)

    private val shared = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val installedChannel = AppVersion.parse(installedVersion)?.channel ?: AppVersion.Channel.STABLE
    private val mutable = MutableStateFlow(read())
    val prefs: StateFlow<Prefs> = mutable.asStateFlow()

    private fun channel(name: String?): AppVersion.Channel? =
        AppVersion.Channel.entries.firstOrNull { it.name == name }

    private fun read(): Prefs {
        val highest = maxOf(channel(shared.getString(KEY_HIGHEST, null)) ?: AppVersion.Channel.STABLE, installedChannel)
        val track = channel(shared.getString(KEY_TRACK, null)) ?: highest
        val ready = shared.getString(KEY_READY_VERSION, null)?.let { version ->
            val file = shared.getString(KEY_READY_FILE, null) ?: return@let null
            val sha = shared.getString(KEY_READY_SHA, null) ?: return@let null
            Ready(version, file, sha)
        }
        return Prefs(track = track, ready = ready)
    }

    fun current(): Prefs = mutable.value

    @Synchronized
    fun setTrack(track: AppVersion.Channel) {
        shared.edit().putString(KEY_TRACK, track.name).apply()
        mutable.value = read()
    }

    /** Records the installed channel so dev/beta sideloads adjust the sticky default track up. */
    @Synchronized
    fun recordInstalled() {
        val highest = channel(shared.getString(KEY_HIGHEST, null)) ?: AppVersion.Channel.STABLE
        if (installedChannel > highest) {
            shared.edit().putString(KEY_HIGHEST, installedChannel.name).remove(KEY_TRACK).apply()
        } else if (shared.getString(KEY_HIGHEST, null) == null) {
            shared.edit().putString(KEY_HIGHEST, highest.name).apply()
        }
        mutable.value = read()
    }

    @Synchronized
    fun setReady(ready: Ready) {
        shared.edit()
            .putString(KEY_READY_VERSION, ready.version)
            .putString(KEY_READY_FILE, ready.file)
            .putString(KEY_READY_SHA, ready.sha256)
            .apply()
        mutable.value = read()
    }

    @Synchronized
    fun clearReady() {
        shared.edit()
            .remove(KEY_READY_VERSION)
            .remove(KEY_READY_FILE)
            .remove(KEY_READY_SHA)
            .apply()
        mutable.value = read()
    }

    private companion object {
        const val KEY_TRACK = "track"
        const val KEY_HIGHEST = "highest_installed_channel"
        const val KEY_READY_VERSION = "ready_version"
        const val KEY_READY_FILE = "ready_file"
        const val KEY_READY_SHA = "ready_sha256"
    }
}

/** Finds, downloads, verifies and installs watch updates from GitHub releases. */
class WatchUpdater(
    private val context: Context,
    private val source: WatchReleaseSource,
    private val store: WatchUpdateStore,
    private val installedVersion: String,
    val enabled: Boolean
) {
    sealed interface Check {
        data class Available(val release: Release) : Check

        data object UpToDate : Check

        data class Failed(val message: String) : Check
    }

    suspend fun check(): Check {
        if (!enabled) return Check.UpToDate
        return runCatching {
            val prefs = store.current()
            val releases = source.releases()
            val update = Releases.update(releases, installedVersion, prefs.track, forWatch = true)
            HLog.i(TAG, "checked ${releases.size} releases; installed=$installedVersion track=${prefs.track} update=${update?.version}")
            if (update != null) Check.Available(update) else Check.UpToDate
        }.getOrElse {
            if (it is CancellationException) throw it
            HLog.w(TAG, "update check failed", it)
            Check.Failed(it.message ?: it.javaClass.simpleName)
        }
    }

    /** Downloads the watch APK of [release] and verifies it against `SHA256SUMS`. */
    suspend fun download(release: Release, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        val apk = release.watchApk ?: throw IOException("This release has no watch APK")
        val sums = release.checksums ?: throw IOException("This release has no checksums, so it can't be verified")
        return download(release.version.toString(), apk, sums, onProgress)
    }

    /** Downloads, verifies and saves [apk] for [version] in `files/updates`. */
    suspend fun download(
        version: String,
        apk: GitHubAsset,
        sums: GitHubAsset,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File {
        readyFile(version)?.let { file ->
            HLog.i(TAG, "$version already downloaded and verified: no download")
            return file
        }
        val name = GitHubReleaseClient.safeAssetName(apk.name)
        val expected = Releases.parseChecksums(source.text(sums.url))[name] ?: throw IOException("No checksum for $name")
        val part = withContext(Dispatchers.IO) {
            val dir = dir()
            val p = File(dir, "$name.part")
            dir.listFiles()?.filter { it != p }?.forEach { it.deleteRecursively() }
            store.clearReady()
            p
        }
        val actual = source.download(apk.url, part, onProgress)
        return withContext(Dispatchers.IO) {
            if (!actual.equals(expected, ignoreCase = true)) {
                part.delete()
                HLog.w(TAG, "$name: checksum mismatch, deleted")
                throw ChecksumMismatch()
            }
            val file = File(dir(), name)
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            store.setReady(WatchUpdateStore.Ready(version, file.name, expected.lowercase()))
            HLog.i(TAG, "downloaded and verified $name: ready to install")
            file
        }
    }

    /** Returns the verified APK file when present and matching its recorded SHA-256, or null otherwise. */
    suspend fun readyFile(version: String? = null): File? = withContext(Dispatchers.IO) {
        val ready = store.current().ready ?: return@withContext null
        if (version != null && ready.version != version) return@withContext null
        val name = runCatching { GitHubReleaseClient.safeAssetName(ready.file) }.getOrNull()
        val file = name?.let { File(dir(), it) }
        if (file != null && file.isFile && WatchReleaseSource.sha256(file).equals(ready.sha256, ignoreCase = true)) {
            return@withContext file
        }
        HLog.w(TAG, "ready update ${ready.version} is missing or damaged: downloading again")
        file?.delete()
        store.clearReady()
        null
    }

    /** Deletes downloaded files once the ready update (or a newer one) is installed. */
    suspend fun cleanUp() = withContext(Dispatchers.IO) {
        val ready = store.current().ready ?: return@withContext
        val installed = AppVersion.parse(installedVersion)
        val readyVersion = AppVersion.parse(ready.version)
        if (installed == null || readyVersion == null || installed < readyVersion) return@withContext
        HLog.i(TAG, "update ${ready.version} installed: deleting its download")
        store.clearReady()
        dir().deleteRecursively()
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun allowInstallIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Verifies package name and signing certificates, then commits the package installer session. */
    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        verifyApkMatchesApp(apk)
        HLog.i(TAG, "installing ${apk.name}")
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        try {
            session.openWrite("heartline.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, WatchInstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        } catch (t: Throwable) {
            runCatching { session.abandon() }
            throw t
        } finally {
            session.close()
        }
    }

    private fun verifyApkMatchesApp(apk: File) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw InvalidPackage("Unreadable update package")
        if (archive.packageName != context.packageName) {
            throw InvalidPackage("Package name mismatch: ${archive.packageName}")
        }
        val installed = pm.getPackageInfo(context.packageName, flags)
        val archiveSigs = signingBytes(archive)
        val installedSigs = signingBytes(installed)
        if (archiveSigs.isEmpty() || archiveSigs != installedSigs) {
            throw InvalidPackage("Signing certificate mismatch for update package")
        }
    }

    private fun signingBytes(info: PackageInfo): Set<String> =
        info.signingInfo?.apkContentsSigners.orEmpty().map { it.toCharsString() }.toSet()

    private fun dir() = File(context.filesDir, "updates").apply { mkdirs() }

    class ChecksumMismatch : IOException("Checksum mismatch: the download is damaged or not an official release")

    class InvalidPackage(message: String) : IOException(message)

    private companion object {
        const val TAG = "Heartline/Update"
    }
}

/** Handles the system installer callback on the watch. */
class WatchInstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        handle(context, status, message, confirmOf(intent), AppForeground.resumed)
    }

    companion object {
        private const val TAG = "Heartline/Update"
        private const val NOTIF_ID = 1004

        private fun confirmOf(intent: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

        internal fun handle(context: Context, status: Int, message: String, confirm: Intent?, foreground: Boolean) {
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    if (confirm == null) return
                    if (foreground) {
                        HLog.i(TAG, "install status=pending: asking on screen")
                        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else {
                        HLog.i(TAG, "install status=pending: in the background, posting notification")
                        notifyConfirm(context, confirm)
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> HLog.i(TAG, "install status=success")
                PackageInstaller.STATUS_FAILURE_ABORTED -> HLog.i(TAG, "install status=aborted $message")
                else -> HLog.w(TAG, "install status=$status $message")
            }
        }

        private fun notifyConfirm(context: Context, confirm: Intent) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    WatchNotifier.CHANNEL_ALERTS,
                    context.getString(R.string.channel_alerts),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
            val open = PendingIntent.getActivity(
                context,
                NOTIF_ID,
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            manager.notify(
                NOTIF_ID,
                NotificationCompat.Builder(context, WatchNotifier.CHANNEL_ALERTS)
                    .setSmallIcon(R.drawable.ic_heart)
                    .setContentTitle(context.getString(R.string.update_notif_confirm_title))
                    .setContentText(context.getString(R.string.update_notif_confirm_text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build()
            )
        }
    }
}
