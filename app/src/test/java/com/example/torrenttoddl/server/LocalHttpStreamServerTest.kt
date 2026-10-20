package com.example.torrenttoddl.server

import com.example.torrenttoddl.engine.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.Test

class LocalHttpStreamServerTest {
    @Test fun downloadCanResumeAndCannotMixBytesFromAnotherTorrent() {
        val payload = "0123456789".toByteArray()
        val entry = TorrentFileEntry(0, "test.bin", "test.bin", payload.size.toLong(), 0)
        val engineStatus = MutableStateFlow(EngineStatus(files = listOf(entry), isReadyForDdl = true, infoHash = "abc123"))
        val source = object : TorrentFileSource {
            override val status: StateFlow<EngineStatus> = engineStatus
            override fun openFileStream(fileIndex: Int, startByteOffset: Long, endByteOffset: Long?): Pair<TorrentFileEntry, InputStream> =
                entry to ByteArrayInputStream(payload.copyOfRange(startByteOffset.toInt(), (endByteOffset ?: payload.size.toLong()).toInt()))
        }
        val server = LocalHttpStreamServer(0, source)
        server.start(1000, true)
        fun request(range: String? = null, match: String? = null): HttpURLConnection =
            (URL("http://127.0.0.1:${server.listeningPort}/files/0/test.bin").openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                range?.let { setRequestProperty("Range", it) }
                match?.let { setRequestProperty("If-Match", it) }
            }
        try {
            val first = request()
            val etag = first.getHeaderField("ETag")
            assertNotNull("Android requires an ETag to resume", etag)
            assertEquals(200, first.responseCode)
            assertArrayEquals(payload, first.inputStream.use { it.readBytes() })
            first.disconnect()
            val resumed = request("bytes=4-", etag)
            assertEquals(206, resumed.responseCode)
            assertEquals("bytes 4-9/10", resumed.getHeaderField("Content-Range"))
            assertEquals(etag, resumed.getHeaderField("ETag"))
            assertEquals("456789", resumed.inputStream.use { String(it.readBytes()) })
            resumed.disconnect()
            engineStatus.value = engineStatus.value.copy(infoHash = "def456")
            val stale = request("bytes=4-", etag)
            assertEquals(412, stale.responseCode)
            stale.errorStream?.close()
            stale.disconnect()
            val invalid = request("bytes=10-")
            assertEquals(416, invalid.responseCode)
            invalid.errorStream?.close()
            invalid.disconnect()
        } finally { server.stop() }
        assertEquals("Finished responses must release gateway ownership", 0, server.activeStreams)
    }
}
