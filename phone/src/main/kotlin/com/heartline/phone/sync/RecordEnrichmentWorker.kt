// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.heartline.logging.HLog
import com.heartline.shared.bp.BpSafety
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/** Runs durable post-save ML enrichment (BP refinement, ECG second opinion, weight update) for a synced record. */
class RecordEnrichmentWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {
    private val coordinator: RecordIngestionCoordinator by inject()

    override suspend fun doWork(): Result {
        val recordId = inputData.getString(KEY_RECORD_ID) ?: return Result.success()
        val notifiedSafety = inputData.getString(KEY_NOTIFIED_SAFETY)
            ?.let { name -> BpSafety.entries.firstOrNull { it.name == name } }
        return try {
            coordinator.enrich(recordId, alreadyNotifiedBpSafety = notifiedSafety)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            HLog.w(TAG, "record enrichment failed for $recordId", e)
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "Heartline/Sync"
        private const val WORK_PREFIX = "heartline-enrich-"
        internal const val KEY_RECORD_ID = "record_id"
        internal const val KEY_NOTIFIED_SAFETY = "notified_safety"
        private const val MAX_RETRIES = 3

        fun enqueue(context: Context, recordId: String, notifiedSafety: BpSafety? = null) {
            val data = Data.Builder()
                .putString(KEY_RECORD_ID, recordId)
                .apply { notifiedSafety?.let { putString(KEY_NOTIFIED_SAFETY, it.name) } }
                .build()
            val request = OneTimeWorkRequestBuilder<RecordEnrichmentWorker>()
                .setInputData(data)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("$WORK_PREFIX$recordId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
