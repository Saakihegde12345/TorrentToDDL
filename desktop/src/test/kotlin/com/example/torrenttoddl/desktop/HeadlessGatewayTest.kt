package com.example.torrenttoddl.desktop

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class HeadlessGatewayTest {
    @Test(timeout = 45000)
    fun serverRunsWithoutDesktopAndSeparatesAdminAndDownloadKeys(): Unit = runBlocking {
        val root = Files.createTempDirectory("headless-gateway-").toFile()
        val port = ServerSocket(0).use { it.localPort }
        val settings = DesktopSettings(port = port, cacheDirectory = File(root, "cache").path)
        writeProperties(File(root, "settings.properties"), settings.properties())
        val java = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val server = ProcessBuilder(java.path, "-Djava.awt.headless=true", "-cp", System.getProperty("torrenttoddl.worker.classpath"),
            "com.example.torrenttoddl.desktop.MainKt", "--headless", root.path)
            .redirectOutput(File(root, "output.log")).redirectError(File(root, "error.log")).start()
        fun request(key: String, post: Boolean = false): HttpURLConnection =
            (URL("http://127.0.0.1:$port/api/" + if (post) "magnets" else "status").openConnection() as HttpURLConnection).apply {
                connectTimeout = 1000; readTimeout = 3000
                setRequestProperty("Authorization", "Bearer $key")
                if (post) {
                    requestMethod = "POST"; doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    outputStream.use { it.write("magnet=invalid".toByteArray()) }
                }
            }
        try {
            withTimeout(15000) {
                while (true) {
                    check(server.isAlive) { File(root, "error.log").readText() }
                    val ready = runCatching { request(settings.adminToken).let { c -> try { c.responseCode == 200 } finally { c.disconnect() } } }.getOrDefault(false)
                    if (ready) break
                    delay(100)
                }
            }
            val admin = request(settings.adminToken)
            try { assertEquals(200, admin.responseCode); assertTrue(admin.inputStream.bufferedReader().use { it.readText() }.contains("\"torrents\":[]")) }
            finally { admin.disconnect() }
            val viewer = request(settings.accessToken)
            try { assertEquals("Shared download links must not authorize remote control", 401, viewer.responseCode) }
            finally { viewer.disconnect() }
            val invalid = request(settings.adminToken, post = true)
            try { assertEquals(400, invalid.responseCode) } finally { invalid.disconnect() }
        } finally {
            server.destroy()
            if (!server.waitFor(10, TimeUnit.SECONDS)) { server.destroyForcibly(); server.waitFor(5, TimeUnit.SECONDS) }
            root.deleteRecursively()
        }
        ServerSocket(port).use { }
    }
}
