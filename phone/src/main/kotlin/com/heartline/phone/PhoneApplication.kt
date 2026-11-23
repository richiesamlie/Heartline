// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import com.heartline.logging.HLog
import android.app.Application
import com.heartline.phone.data.BpRepository
import com.heartline.phone.link.PhoneStatusPublisher
import com.heartline.phone.notify.Reminders
import com.heartline.phone.update.UpdateWorker
import com.heartline.phone.update.UpdateRepository
import com.heartline.phone.update.Updater
import com.heartline.phone.diag.DiagnosticsRepository
import com.heartline.phone.data.ProfileRepository
import com.heartline.shared.diag.Redactor
import com.heartline.shared.ecg.RhythmModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import com.heartline.phone.widget.WidgetUpdater
import com.heartline.phone.data.DemoData
import com.heartline.phone.data.HeartRepository
import com.heartline.phone.data.RecordRepository
import com.heartline.phone.data.SettingsRepository
import com.heartline.phone.di.APP_SCOPE
import com.heartline.phone.di.phoneModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class PhoneApplication : Application() {
    /** Removes the sample records, alert, heart-rate history and BP calibration older debug builds seeded. */
    private suspend fun purgeDemoData() {
        val records = get<RecordRepository>().deleteDemo()
        val bp = get<BpRepository>()
        val calibrations = bp.deleteDemo()
        get<HeartRepository>().deleteDemoData()
        HLog.i("Heartline/Data", "purged demo data: records=$records calibrations=$calibrations")
        if (calibrations > 0) bp.resendCalibration(orNull = true)
    }

    override fun onCreate() {
        super.onCreate()
        HLog.init(this, HLog.PHONE_BUDGET_BYTES)
        RhythmModel.onLoadFailure = { HLog.w("Heartline/ECG", "ECG model ${it.path} unavailable: ${it.reason}", it.error) }
        RhythmModel.loadFailures().forEach { HLog.w("Heartline/ECG", "ECG model ${it.path} unavailable: ${it.reason}", it.error) }
        startKoin {
            androidContext(this@PhoneApplication)
            modules(phoneModule)
        }
        HLog.i("Heartline/App", "phone app ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}) on ${android.os.Build.MODEL}, API ${android.os.Build.VERSION.SDK_INT}")
        val scope = get<CoroutineScope>(APP_SCOPE)
        scope.launch {
            val settings = get<SettingsRepository>()
            if (settings.claimDemoPurge()) purgeDemoData()
            // Only builds made with -Pheartline.demoData=true start with sample records.
            if (BuildConfig.DEMO_DATA && settings.claimDemoSeed()) {
                DemoData.seedIfEmpty(get<RecordRepository>(), get<HeartRepository>())
            }
        }
        get<PhoneStatusPublisher>().start(scope)
        get<WidgetUpdater>().start(scope)
        scope.launch { Reminders.sync(this@PhoneApplication, get<SettingsRepository>().current()) }
        UpdateWorker.schedule(this, enabled = BuildConfig.UPDATER)
        scope.launch {
            get<UpdateRepository>().recordInstalled()
            // Deletes the downloaded update once it's installed.
            get<Updater>().cleanUp()
        }
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - 86_400_000L
            listOf("reports", "exports").forEach { dirName ->
                java.io.File(cacheDir, dirName).listFiles()
                    ?.filter { it.isFile && it.lastModified() < cutoff }
                    ?.forEach { it.delete() }
            }
        }
        // Diagnostic logs: on for beta users, stable users are asked; names never reach the file.
        get<DiagnosticsRepository>().start(scope)
        get<ProfileRepository>().profile.onEach { HLog.setRedactor(Redactor.of(it)) }.launchIn(scope)
    }
}
