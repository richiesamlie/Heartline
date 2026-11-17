// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.sync

import com.heartline.logging.HLog
import com.heartline.phone.data.HeartRepository
import com.heartline.phone.heart.PosturalAlertEvaluator
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HeartLimits
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.describe
import com.heartline.shared.stress.StressLimits
import com.heartline.shared.sync.HeartDataSink
import com.heartline.shared.vitals.VitalsLimits

/**
 * Coordinates incoming heart batches and watch alerts from [PhoneSyncEngine]: persists data in
 * [HeartRepository], updates synced limits, evaluates phone-side postural patterns, and notifies on fresh alerts.
 */
class HeartIngestionCoordinator(
    private val heart: HeartRepository,
    private val evaluator: PosturalAlertEvaluator? = null,
    private val onLimits: suspend (HeartLimits) -> Unit = {},
    private val onVitalsLimits: suspend (VitalsLimits) -> Unit = {},
    private val onStressLimits: suspend (StressLimits) -> Unit = {},
    private val watchVersion: suspend () -> String? = { null },
    private val onAlert: suspend (HealthAlert) -> Unit = {},
) : HeartDataSink {
    override suspend fun saveBatch(batch: HrBatch) {
        val newest = heart.storeBatch(batch)
        batch.limits?.let { onLimits(it) }
        batch.vitals?.let { onVitalsLimits(it) }
        batch.stressLimits?.let { onStressLimits(it) }
        if (newest != null) {
            evaluator?.evaluateIfDue(newest)?.let { onAlert(it) }
        }
    }

    override suspend fun saveAlert(alert: HealthAlert) {
        val from = watchVersion()
        val fresh = heart.storeAlert(alert, watchVersion = from)
        HLog.i("Heartline/Alert", "received ${alert.describe()} watch=${from ?: "unknown"} ${if (fresh) "notified" else "duplicate, not notified"}")
        if (fresh) onAlert(alert)
    }
}
