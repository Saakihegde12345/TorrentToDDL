package com.example.torrenttoddl.service

/** Monotonic-clock idle policy; queued downloads and live HTTP streams own the gateway. */
internal object GatewayIdlePolicy {
    const val IDLE_MS = 30_000L
    const val STALL_MS = 180_000L

    fun shouldStop(elapsedIdleMs: Long, loading: Boolean, activeDownloads: Boolean, activeStreams: Int): Boolean =
        !activeDownloads && activeStreams == 0 && elapsedIdleMs >= if (loading) STALL_MS else IDLE_MS
}
