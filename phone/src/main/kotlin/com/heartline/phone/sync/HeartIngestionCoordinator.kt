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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
    private val posturalScope: CoroutineScope? = null,
    private val onAlert: suspend (HealthAlert) -> Unit = {},
) : HeartDataSink {
    private val posturalRunning = AtomicBoolean(false)
    private val pendingPosturalMs = AtomicLong(Long.MIN_VALUE)

    override suspend fun saveBatch(batch: HrBatch) {
        val newest = heart.storeBatch(batch)
        batch.limits?.let { onLimits(it) }
        batch.vitals?.let { onVitalsLimits(it) }
        batch.stressLimits?.let { onStressLimits(it) }
        if (newest != null) {
            val eval = evaluator
            val scope = posturalScope
            if (eval != null && scope == null) {
                eval.evaluateIfDue(newest)?.let { onAlert(it) }
            } else if (eval != null && scope != null) {
                schedulePostural(scope, eval, newest)
            }
        }
    }

    private fun schedulePostural(scope: CoroutineScope, eval: PosturalAlertEvaluator, newest: Long) {
        pendingPosturalMs.accumulateAndGet(newest) { cur, n -> if (cur == Long.MIN_VALUE) n else maxOf(cur, n) }
        if (posturalRunning.compareAndSet(false, true)) launchPosturalDrain(scope, eval)
    }

    private fun launchPosturalDrain(scope: CoroutineScope, eval: PosturalAlertEvaluator) {
        scope.launch {
            var cancelled = false
            try {
                while (true) {
                    val target = pendingPosturalMs.getAndSet(Long.MIN_VALUE)
                    if (target == Long.MIN_VALUE) break
                    try {
                        eval.evaluateIfDue(target)?.let { onAlert(it) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        HLog.w("Heartline/Alert", "postural evaluation failed", t)
                    }
                }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } finally {
                posturalRunning.set(false)
                if (!cancelled && pendingPosturalMs.get() != Long.MIN_VALUE && posturalRunning.compareAndSet(false, true)) {
                    launchPosturalDrain(scope, eval)
                }
            }
        }
    }

    override suspend fun saveAlert(alert: HealthAlert) {
        val from = watchVersion()
        val fresh = heart.storeAlert(alert, watchVersion = from)
        HLog.i("Heartline/Alert", "received ${alert.describe()} watch=${from ?: "unknown"} ${if (fresh) "notified" else "duplicate, not notified"}")
        if (fresh) onAlert(alert)
    }
}
