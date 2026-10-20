package com.example.torrenttoddl.service

/** Visible UI, queued downloads and live HTTP streams keep the gateway alive. */
internal object GatewayIdlePolicy {
    const val IDLE_MS = 30_000L
    const val STALL_MS = 180_000L

    fun shouldStop(
        elapsedIdleMs: Long,
        loading: Boolean,
        activeDownloads: Boolean,
        activeStreams: Int,
        uiVisible: Boolean,
        transfersFinished: Boolean = false
    ): Boolean =
        !uiVisible && !activeDownloads && activeStreams == 0 &&
            (transfersFinished || elapsedIdleMs >= if (loading) STALL_MS else IDLE_MS)
}
