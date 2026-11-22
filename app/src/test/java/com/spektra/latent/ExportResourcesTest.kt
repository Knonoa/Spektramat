package com.spektra.latent

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportResourcesTest {
    @Test fun scalesWithDeviceCapacityAndBackgroundState() {
        assertEquals(2, ExportResources.workers(4, 3000, true, false, true, false, 0, false))
        assertEquals(4, ExportResources.workers(8, 5462, false, false, true, false, 0, true))
        assertEquals(1, ExportResources.workers(8, 5462, false, false, false, false, 0, true))
        assertEquals(8, ExportResources.workers(12, 11000, false, false, true, false, 0, false))
        assertEquals(2, ExportResources.workers(12, 11000, false, false, false, false, 0, false))
        assertEquals(1, ExportResources.workers(12, 11000, false, false, false, false, 0, true))
    }

    @Test fun resourcePressureOverridesHardwareCapacity() {
        assertEquals(2, ExportResources.workers(12, 11000, false, false, true, true, 0, false))
        assertEquals(2, ExportResources.workers(12, 11000, false, false, true, false, 2, false))
        assertEquals(1, ExportResources.workers(12, 11000, false, false, true, false, 3, false))
        assertEquals(1, ExportResources.workers(12, 11000, false, true, true, false, 0, false))
    }
}
