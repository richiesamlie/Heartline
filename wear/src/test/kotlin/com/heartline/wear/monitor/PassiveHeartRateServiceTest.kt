// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PassiveHeartRateServiceTest {
    @Test
    fun sanitizePassiveBpmEnforcesPhysiologicalRange() {
        assertNull(PassiveHeartRateService.sanitizePassiveBpm(24.0))
        assertNull(PassiveHeartRateService.sanitizePassiveBpm(231.0))
        assertEquals(25, PassiveHeartRateService.sanitizePassiveBpm(25.0))
        assertEquals(72, PassiveHeartRateService.sanitizePassiveBpm(72.4))
        assertEquals(230, PassiveHeartRateService.sanitizePassiveBpm(230.0))
    }
}
