package com.example.torrenttoddl.engine

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.BeforeClass
import org.junit.Test
import org.libtorrent4j.SessionManager

class TorrentStreamEngineTest {
    @Test
    fun cachePercentageUsesByteCountsAndHandlesUnknownTotals() {
        // A file-check task can reach 100% with no payload downloaded.
        assertEquals(0f, cachedPercent(0, 100, false), 0f)
        assertEquals(25f, cachedPercent(25, 100, false), 0f)
        assertEquals(0f, cachedPercent(0, 0, false), 0f)
        assertEquals(100f, cachedPercent(0, 0, true), 0f)
        assertEquals(100f, cachedPercent(120, 100, true), 0f)
    }

    @Test(timeout = 60000)
    fun torrentHandleSurvivesAlertRecyclingAndResetStopsNetworking(): Unit = runBlocking {
        val root = Files.createTempDirectory("torrent-engine-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = SessionManager()
        val engine = TorrentStreamEngine(root, scope, manager)
        try {
            assertFalse("Opening the engine must not start networking", manager.isRunning)
            val payload = "hello torrent".toByteArray()
            File(root, "payload.bin").writeBytes(payload)
            val metadata = torrentMetadata(payload)
            engine.loadTorrentBytes(metadata)
            withTimeout(10000L) { engine.status.first { it.isReadyForDdl } }
            withTimeout(10000L) { engine.status.first { it.cachedBytes >= payload.size } }
            val full = engine.openFileStream(0, 0L)?.second ?: error("Cached file unavailable")
            org.junit.Assert.assertArrayEquals(payload, full.use { it.readBytes() })
            val range = engine.openFileStream(0, 2L, 7L)?.second ?: error("Cached range unavailable")
            org.junit.Assert.assertArrayEquals(payload.copyOfRange(2, 7), range.use { it.readBytes() })

            // The alert's borrowed handle is freed/reused by later alert batches.
            // Exercise the retained handle after several batches and GC cycles.
            repeat(8) {
                manager.swig().post_session_stats()
                System.gc()
                delay(600L)
                assertEquals("payload.bin", engine.status.value.files.single().name)
                val stream = engine.openFileStream(0, 0L, 0L)?.second
                    ?: error("Retained handle became unavailable after alert recycling")
                assertEquals(-1, stream.use { it.read() })
            }
            engine.resetSession()
            assertFalse("Reset must stop DHT, peer discovery and torrent threads", manager.isRunning)
            assertEquals(EngineStatus(), engine.status.value)

            // A new load must lazily restart networking after reset.
            engine.loadTorrentBytes(metadata)
            withTimeout(10000L) { engine.status.first { it.isReadyForDdl } }
        } finally {
            engine.stopSession()
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private fun torrentMetadata(payload: ByteArray): ByteArray {
        val prefix = "d4:infod6:lengthi${payload.size}e4:name11:payload.bin12:piece lengthi16384e6:pieces20:"
        return prefix.toByteArray() + MessageDigest.getInstance("SHA-1").digest(payload) + "ee".toByteArray()
    }

    @Test(timeout = 30000)
    fun desktopSeedingAndBandwidthOptionsReachTheNativeSession(): Unit = runBlocking {
        val root = Files.createTempDirectory("torrent-settings-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = SessionManager()
        val engine = TorrentStreamEngine(root, scope, manager, EngineOptions(
            seedAfterDownload = true, downloadLimitKiB = 128, uploadLimitKiB = 64, connections = 50
        ))
        try {
            val payload = "configured torrent".toByteArray()
            File(root, "payload.bin").writeBytes(payload)
            engine.loadTorrentBytes(torrentMetadata(payload))
            withTimeout(10000) { engine.status.first { it.stateText == "Seeding" } }
            assertFalse("Seeding must remain active when enabled", manager.isPaused)
            assertEquals(128 * 1024, manager.downloadRateLimit())
            assertEquals(64 * 1024, manager.uploadRateLimit())
            engine.pauseTransfers(true)
            org.junit.Assert.assertTrue(manager.isPaused)
            engine.pauseTransfers(false)
            assertFalse(manager.isPaused)
        } finally { engine.stopSession(); scope.cancel(); root.deleteRecursively() }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadDesktopNativeLibrary() {
            com.example.torrenttoddl.desktop.DesktopNativeLibrary.load()
        }
    }
}
