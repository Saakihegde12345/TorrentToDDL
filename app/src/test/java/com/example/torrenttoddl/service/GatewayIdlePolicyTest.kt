package com.example.torrenttoddl.service

import org.junit.Assert.*
import org.junit.Test

class GatewayIdlePolicyTest {
    @Test fun idleGatewayStopsButActiveTransfersAndMetadataHaveTimeToFinish() {
        assertFalse(GatewayIdlePolicy.shouldStop(29_999, false, false, 0))
        assertTrue(GatewayIdlePolicy.shouldStop(30_000, false, false, 0))
        assertFalse(GatewayIdlePolicy.shouldStop(60_000, true, false, 0))
        assertTrue(GatewayIdlePolicy.shouldStop(180_000, true, false, 0))
        assertFalse(GatewayIdlePolicy.shouldStop(600_000, false, true, 0))
        assertFalse(GatewayIdlePolicy.shouldStop(600_000, false, false, 1))
    }
}
