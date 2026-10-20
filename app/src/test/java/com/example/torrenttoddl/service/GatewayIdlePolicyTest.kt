package com.example.torrenttoddl.service

import org.junit.Assert.*
import org.junit.Test

class GatewayIdlePolicyTest {
    @Test fun backgroundIdleAndMetadataTimeouts() {
        assertFalse(GatewayIdlePolicy.shouldStop(29_999, false, false, 0, false))
        assertTrue(GatewayIdlePolicy.shouldStop(30_000, false, false, 0, false))
        assertFalse(GatewayIdlePolicy.shouldStop(60_000, true, false, 0, false))
        assertTrue(GatewayIdlePolicy.shouldStop(180_000, true, false, 0, false))
        assertFalse(GatewayIdlePolicy.shouldStop(600_000, false, true, 0, false))
        assertFalse(GatewayIdlePolicy.shouldStop(600_000, false, false, 1, false))
    }

    @Test fun visibleAppKeepsCachedTorrentAndCompletedDownloadsAvailable() {
        assertFalse(GatewayIdlePolicy.shouldStop(600_000, false, false, 0, true))
        assertFalse(GatewayIdlePolicy.shouldStop(600_000, true, false, 0, true))
        assertFalse(GatewayIdlePolicy.shouldStop(600_000, false, false, 0, true, true))
    }

    @Test fun backgroundCompletionRequiresAllTransfersToClose() {
        assertTrue(GatewayIdlePolicy.shouldStop(0, false, false, 0, false, true))
        assertFalse(GatewayIdlePolicy.shouldStop(0, false, true, 0, false, true))
        assertFalse(GatewayIdlePolicy.shouldStop(0, false, false, 1, false, true))
    }
}
