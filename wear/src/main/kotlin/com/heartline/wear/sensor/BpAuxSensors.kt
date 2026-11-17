// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sensor

/** Skin temperature from SKIN_TEMPERATURE_ON_DEMAND, °C. */
data class SkinTempReading(val objectC: Double, val ambientC: Double?)

/**
 * The sensors a blood-pressure session reads before its main recording (algorithm 6): skin
 * temperature (vasoconstriction) and skin conductance (sympathetic arousal, Watch8+). Only one
 * on-demand tracker can run at a time, so they are read one after the other. Each returns null
 * when the watch doesn't have it.
 */
interface BpAuxSensors {
    suspend fun skinTemp(): SkinTempReading?

    suspend fun skinConductance(seconds: Int): Double?

    companion object {
        val NONE = object : BpAuxSensors {
            override suspend fun skinTemp(): SkinTempReading? = null

            override suspend fun skinConductance(seconds: Int): Double? = null
        }
    }
}
