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
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.libtorrent4j.SessionManager

class TorrentStreamEngineTest {
    @Test(timeout = 30000)
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

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadLinuxNativeLibrary() {
            assumeTrue(System.getProperty("os.name").startsWith("Linux"))
            assumeTrue(System.getProperty("os.arch") in setOf("amd64", "x86_64"))
            // Android and Linux jars expose the same resource path. Select the
            // Linux jar explicitly instead of loading an Android ELF into a JVM.
            val resourceUrl = java.util.Collections.list(
                TorrentStreamEngineTest::class.java.classLoader!!.getResources("lib/x86_64/libtorrent4j.so")
            ).firstOrNull { it.toExternalForm().contains("libtorrent4j-linux-") }
                ?: error("Linux JNI test runtime is missing")
            val library = Files.createTempFile("libtorrent4j-test", ".so").toFile()
            library.deleteOnExit()
            resourceUrl.openStream().use { input -> library.outputStream().use { input.copyTo(it) } }
            System.setProperty("libtorrent4j.jni.path", library.absolutePath)
        }
    }
}
