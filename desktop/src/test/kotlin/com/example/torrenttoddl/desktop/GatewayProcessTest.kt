package com.example.torrenttoddl.desktop

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class GatewayProcessTest {
    @Test fun desktopInstanceLockPreventsDuplicateControllersAndReleasesOnExit() {
        val root = Files.createTempDirectory("desktop-instance-").toFile()
        try {
            val first = acquireDesktopInstance(root) ?: error("Cannot acquire initial lock")
            first.use { assertNull(acquireDesktopInstance(root)) }
            val second = acquireDesktopInstance(root) ?: error("Lock survived controller exit")
            second.close()
        } finally { root.deleteRecursively() }
    }
    @Test fun settingsAndSnapshotsRoundTripWithoutLosingUnicodeOrFileIdentity() {
        val root = Files.createTempDirectory("desktop-settings-").toFile()
        try {
            val settings = DesktopSettings(downloadDirectory = File(root, "My Downloads").path, port = 8092, seedAfterDownload = false)
            val file = File(root, "settings.properties")
            writeProperties(file, settings.properties())
            assertEquals(settings, DesktopSettings.from(readProperties(file)))
            val view = DesktopView(running = true, torrents = listOf(DesktopTorrent("id", "magnet:test",
                com.example.torrenttoddl.engine.EngineStatus(torrentName = "日本語 & spaces", infoHash = "abc"))), backendPid = 123)
            assertEquals(view, decodeView(view.encode()))
            assertThrows(IllegalArgumentException::class.java) { settings.copy(port = 80).validate() }
            assertThrows(IllegalArgumentException::class.java) { settings.copy(accessToken = "short").validate() }
            assertThrows(IllegalArgumentException::class.java) { safeExportPath(root, "../outside") }
        } finally { root.deleteRecursively() }
    }

    @Test(timeout = 90000)
    fun multipleTorrentsExportsAuthRestartAndStopReleaseTheActualWorker(): Unit = runBlocking {
        val root = Files.createTempDirectory("desktop-worker-").toFile()
        val port = ServerSocket(0).use { it.localPort }
        val settings = DesktopSettings(port = port, downloadDirectory = File(root, "downloads").path,
            cacheDirectory = File(root, "cache").path, concurrentDownloads = 2, shareOnLan = false)
        writeProperties(File(root, "settings.properties"), settings.properties())
        val ids = List(2) { UUID.randomUUID().toString() }
        val payloads = listOf("first torrent bytes", "second torrent bytes").map { it.toByteArray() }
        writeProperties(File(root, "queue.properties"), Properties().apply {
            setProperty("ids", ids.joinToString(","))
            ids.forEachIndexed { i, id ->
                val metadata = File(root, "metadata/$id.torrent")
                metadata.parentFile.mkdirs()
                val payload = payloads[i]
                metadata.writeBytes(("d4:infod6:lengthi${payload.size}e4:name11:payload.bin12:piece lengthi16384e6:pieces20:").toByteArray() +
                    MessageDigest.getInstance("SHA-1").digest(payload) + "ee".toByteArray())
                File(settings.cacheDirectory, "$id/payload.bin").apply { parentFile.mkdirs(); writeBytes(payload) }
                setProperty("$id.source", metadata.path)
            }
        })
        val gateway = GatewayProcess(root)
        fun request(path: String, token: String? = settings.accessToken, range: String? = null): HttpURLConnection =
            (URL("http://127.0.0.1:$port/$path" + if (token != null) "?token=$token" else "").openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000; readTimeout = 5000
                range?.let { setRequestProperty("Range", it) }
            }
        try {
            gateway.start().join()
            val ready = withTimeout(20000) { gateway.view.first { it.torrents.size == 2 && it.torrents.all { t -> t.engine.progressPercent == 100f } } }
            val pid = ready.backendPid
            assertNotEquals(ProcessHandle.current().pid(), pid)
            ids.forEachIndexed { i, id ->
                val response = request("torrents/$id/files/0/payload.bin")
                try { assertEquals(200, response.responseCode); assertArrayEquals(payloads[i], response.inputStream.use { it.readBytes() }) }
                finally { response.disconnect() }
                gateway.save(id, 0, File(settings.downloadDirectory, "$i.bin")).join()
            }
            withTimeout(15000) { gateway.view.first { it.exports.size == 2 && it.exports.all { e -> e.finished } } }
            payloads.forEachIndexed { i, bytes -> assertArrayEquals(bytes, File(settings.downloadDirectory, "$i.bin").readBytes()) }
            val unauthorized = request("torrents/${ids[0]}/files/0/payload.bin", token = null)
            try { assertEquals(401, unauthorized.responseCode) } finally { unauthorized.disconnect() }
            val wrong = request("torrents/${ids[0]}/files/0/payload.bin", token = "wrong")
            try { assertEquals(401, wrong.responseCode) } finally { wrong.disconnect() }
            val resumed = request("torrents/${ids[1]}/files/0/payload.bin", range = "bytes=2-7")
            try { assertEquals(206, resumed.responseCode); assertArrayEquals(payloads[1].copyOfRange(2, 8), resumed.inputStream.use { it.readBytes() }) }
            finally { resumed.disconnect() }
            gateway.pause(ids[0], true).join()
            withTimeout(5000) { gateway.view.first { it.torrents.any { t -> t.id == ids[0] && t.paused } } }
            gateway.stop().join()
            assertFalse(gateway.view.value.running)
            assertEquals(0L, gateway.view.value.backendPid)
            assertTrue(ProcessHandle.of(pid).map { !it.isAlive }.orElse(true))
            ServerSocket(port).use { }
            gateway.start().join()
            withTimeout(15000) { gateway.view.first { it.torrents.size == 2 } }
            assertTrue(gateway.view.value.torrents.first { it.id == ids[0] }.paused)
            assertTrue(gateway.view.value.backendPid != pid)
            gateway.stop().join()
            assertNull("The frontend must not load JNI", System.getProperty("libtorrent4j.jni.path"))
        } finally { gateway.shutdown(); root.deleteRecursively() }
    }
}
