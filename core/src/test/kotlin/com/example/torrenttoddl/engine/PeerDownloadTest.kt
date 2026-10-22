package com.example.torrenttoddl.engine

import com.example.torrenttoddl.desktop.DesktopNativeLibrary
import com.example.torrenttoddl.server.LocalHttpStreamServer
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.settings_pack

/** Receives real pieces over BitTorrent, then streams them through the gateway. */
class PeerDownloadTest {
    @Test(timeout = 60000)
    fun uncachedTorrentDownloadsFromPeerAndSupportsHttpResume(): Unit = runBlocking {
        val root = Files.createTempDirectory("torrent-peer-test").toFile()
        val seedDirectory = root.resolve("seed").apply { mkdirs() }
        val cacheDirectory = root.resolve("cache").apply { mkdirs() }
        val payload = ByteArray(300_001) { (it % 251).toByte() }
        val pieces = payload.asList().chunked(16384).flatMap {
            MessageDigest.getInstance("SHA-1").digest(it.toByteArray()).asList()
        }.toByteArray()
        val metadata = ("d4:infod6:lengthi${payload.size}e4:name11:payload.bin" +
            "12:piece lengthi16384e6:pieces${pieces.size}:").toByteArray() +
            pieces + "ee".toByteArray()
        seedDirectory.resolve("payload.bin").writeBytes(payload)
        val info = TorrentInfo.bdecode(metadata)
        val seed = SessionManager()
        val receiver = SessionManager()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val engine = TorrentStreamEngine(cacheDirectory, scope, receiver)
        val server = LocalHttpStreamServer(0, engine)
        try {
            val settings = SettingsPack().listenInterfaces("127.0.0.1:0")
            settings.setEnableDht(false)
            settings.setEnableLsd(false)
            settings.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false)
            settings.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false)
            seed.start(SessionParams(settings))
            seed.download(info, seedDirectory)
            val seedPort = withTimeout(15000) {
                while (seed.find(info.infoHash())?.status()?.isSeeding != true ||
                    seed.swig().listen_port() == 0) delay(50)
                // SessionManager's display list omits loopback addresses.
                seed.swig().listen_port()
            }

            engine.loadTorrentBytes(metadata)
            withTimeout(10000) { engine.status.first { it.isReadyForDdl } }
            assertFalse("The receiver must start without cached payload", cacheDirectory.resolve("payload.bin").let {
                it.exists() && it.readBytes().contentEquals(payload)
            })
            receiver.find(info.infoHash())!!.swig().connect_peer(TcpEndpoint("127.0.0.1", seedPort).swig())
            server.start(3000, true)

            fun request(range: String? = null, match: String? = null): HttpURLConnection =
                (URL("http://127.0.0.1:${server.listeningPort}/files/0/payload.bin")
                    .openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 30000
                    range?.let { setRequestProperty("Range", it) }
                    match?.let { setRequestProperty("If-Match", it) }
                }

            val full = request()
            val etag: String
            try {
                assertEquals(200, full.responseCode)
                etag = full.getHeaderField("ETag")
                assertArrayEquals(payload, full.inputStream.use { it.readBytes() })
            } finally { full.disconnect() }

            val resumed = request("bytes=16381-65539", etag)
            try {
                assertEquals(206, resumed.responseCode)
                assertArrayEquals(payload.copyOfRange(16381, 65540), resumed.inputStream.use { it.readBytes() })
            } finally { resumed.disconnect() }
            assertArrayEquals(payload, cacheDirectory.resolve("payload.bin").readBytes())
        } finally {
            server.stop()
            engine.stopSession()
            seed.stop()
            scope.cancel()
            root.deleteRecursively()
        }
    }

    companion object {
        @JvmStatic @BeforeClass fun loadNative() = DesktopNativeLibrary.load()
    }
}
