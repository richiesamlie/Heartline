// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sensor.sdk

import com.heartline.logging.HLog
import com.heartline.shared.model.RecordSummary
import com.heartline.wear.sensor.BpAuxSensors
import com.heartline.wear.sensor.QuickEvent
import com.heartline.wear.sensor.SkinTempReading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

class SdkBpAuxSensors(private val gateway: SdkSensorGateway) : BpAuxSensors {
    override suspend fun skinTemp(): SkinTempReading? = try {
        withTimeoutOrNull(12_000) {
            SdkSkinTempSource(gateway).measure(null).filterIsInstance<QuickEvent.Result>().firstOrNull()
        }?.summary?.let { it as? RecordSummary.SkinTemperature }?.let { SkinTempReading(it.skinCelsius.toDouble(), it.ambientCelsius?.toDouble()) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        HLog.w(SdkSensorGateway.TAG, "BP skin temp failed", e)
        null
    }

    override suspend fun skinConductance(seconds: Int): Double? = gateway.readSkinConductance(seconds)?.toDouble()
}
