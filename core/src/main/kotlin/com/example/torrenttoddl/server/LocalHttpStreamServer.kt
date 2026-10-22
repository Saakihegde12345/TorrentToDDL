package com.example.torrenttoddl.server

import com.example.torrenttoddl.engine.TorrentFileEntry
import com.example.torrenttoddl.engine.TorrentFileSource
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLConnection
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class LocalHttpStreamServer(
    port: Int = 8080,
    private val engine: TorrentFileSource? = null,
    private val sourceForTorrent: ((String) -> TorrentFileSource?)? = null,
    private val accessToken: String? = null,
    bindAddress: String? = null,
    private val controlHandler: ((IHTTPSession) -> Response)? = null,
    private val adminToken: String? = null
) : NanoHTTPD(bindAddress, port) {
    init {
        require(controlHandler == null || (!adminToken.isNullOrBlank() && adminToken != accessToken)) {
            "Remote administration requires a separate nonempty key"
        }
    }

    private val streams = AtomicInteger()
    val activeStreams: Int get() = streams.get()
    private val served = AtomicBoolean()
    val hasServedDownload: Boolean get() = served.get()
    private val opened = ConcurrentHashMap.newKeySet<InputStream>()

    private fun track(input: InputStream): InputStream {
        streams.incrementAndGet()
        served.set(true)
        return object : FilterInputStream(input) {
            private val released = AtomicBoolean()
            init { opened.add(this) }
            override fun close() {
                try { super.close() } finally {
                    if (released.compareAndSet(false, true)) {
                        streams.decrementAndGet()
                        opened.remove(this)
                    }
                }
            }
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val controlRequest = session.uri?.startsWith("/api/") == true && controlHandler != null
        val expectedToken = if (controlRequest) adminToken else accessToken
        if (expectedToken != null) {
            val supplied = headerValue(session, "authorization")?.removePrefix("Bearer ")
                ?: session.parameters["token"]?.firstOrNull() ?: ""
            if (!MessageDigest.isEqual(expectedToken.toByteArray(), supplied.toByteArray())) {
                return textResponse(Response.Status.UNAUTHORIZED, "A valid gateway access key is required")
            }
        }
        if (controlRequest) return controlHandler!!.invoke(session)
        if (session.method != Method.GET && session.method != Method.HEAD) {
            return textResponse(Response.Status.METHOD_NOT_ALLOWED, "Use GET or HEAD")
        }
        val uri = session.uri ?: return textResponse(
            Response.Status.NOT_FOUND,
            "Not Found"
        )

        val path = uri.substringBefore('?').split('/').filter { it.isNotEmpty() }
        val selectedEngine = if (path.firstOrNull() == "torrents" && path.size >= 5) {
            sourceForTorrent?.invoke(path[1])
        } else engine
        val segments = if (path.firstOrNull() == "torrents") path.drop(2) else path
        if (segments.size < 3 || segments[0] != "files") {
            return textResponse(
                Response.Status.BAD_REQUEST,
                "Usage: /files/{fileIndex}/{fileName}"
            )
        }

        val fileIndex = segments[1].toIntOrNull()
            ?: return textResponse(Response.Status.BAD_REQUEST, "Invalid file index")
        if (fileIndex < 0) {
            return textResponse(Response.Status.BAD_REQUEST, "Invalid file index")
        }

        val source = selectedEngine ?: return textResponse(Response.Status.NOT_FOUND, "Torrent not found")
        val engineStatus = source.status.value
        if (!engineStatus.isReadyForDdl) {
            val unavailableText = engineStatus.stateText
                .takeIf { it.isNotBlank() && it != "Idle" }
                ?: "Torrent metadata not ready yet"
            return textResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                unavailableText
            )
        }

        val fileEntry = engineStatus.files.getOrNull(fileIndex)
            ?: return textResponse(Response.Status.BAD_REQUEST, "Invalid file index")
        if (fileEntry.sizeBytes < 0L) {
            return textResponse(Response.Status.SERVICE_UNAVAILABLE, "Invalid torrent file size")
        }

        val etag = "\"${engineStatus.infoHash}-${fileEntry.index}\""
        val ifMatch = headerValue(session, "if-match")
        if (ifMatch != null && ifMatch.trim() != "*" && ifMatch.split(',').none { it.trim() == etag }) {
            return textResponse(Response.Status.PRECONDITION_FAILED, "Torrent changed; start a new download")
        }

        val rangeHeader = headerValue(session, "range")
        val requestedRange = if (rangeHeader == null) {
            null
        } else {
            when (val parsed = parseRange(rangeHeader, fileEntry.sizeBytes)) {
                is RangeResult.Invalid -> {
                    return textResponse(Response.Status.BAD_REQUEST, parsed.message)
                }
                is RangeResult.Unsatisfiable -> {
                    return rangeNotSatisfiable(fileEntry.sizeBytes, parsed.message)
                }
                is RangeResult.Satisfiable -> parsed.range
            }
        }

        val startByte = requestedRange?.start ?: 0L
        val endByte = requestedRange?.endInclusive
        val endByteOffset = endByte?.let { if (it == Long.MAX_VALUE) Long.MAX_VALUE else it + 1L }
        val streamPair = source.openFileStream(fileIndex, startByte, endByteOffset)
            ?: return textResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "Torrent stream is not available yet"
            )

        val (streamEntry, torrentInputStream) = streamPair
        val contentLength = if (requestedRange == null) {
            streamEntry.sizeBytes
        } else {
            requestedRange.endInclusive - requestedRange.start + 1L
        }
        val mimeType = URLConnection.guessContentTypeFromName(streamEntry.name)
            ?: "application/octet-stream"

        return newFixedLengthResponse(
            if (requestedRange == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT,
            mimeType,
            if (session.method == Method.HEAD) {
                torrentInputStream.close()
                java.io.ByteArrayInputStream(byteArrayOf())
            } else track(torrentInputStream),
            contentLength
        ).apply {
            addHeader("ETag", etag)
            addHeader("Accept-Ranges", "bytes")
            addHeader("Content-Length", contentLength.toString())
            if (requestedRange != null) {
                addHeader(
                    "Content-Range",
                    "bytes ${requestedRange.start}-${requestedRange.endInclusive}/${streamEntry.sizeBytes}"
                )
            }
            addHeader(
                "Content-Disposition",
                contentDisposition(streamEntry, attachment = requestedRange == null)
            )
        }
    }

    override fun stop() {
        opened.toList().forEach { runCatching { it.close() } }
        super.stop()
    }

    private fun parseRange(header: String, totalSize: Long): RangeResult {
        val value = header.trim()
        if (!value.regionMatches(0, "bytes=", 0, 6, ignoreCase = true)) {
            return RangeResult.Invalid("Invalid Range header")
        }

        val spec = value.substring(6).trim()
        if (spec.isEmpty() || spec.contains(',')) {
            return RangeResult.Invalid("Only one byte range is supported")
        }

        val parts = spec.split('-', limit = 2)
        if (parts.size != 2) return RangeResult.Invalid("Invalid byte range")

        val first = parts[0].trim()
        val second = parts[1].trim()
        if (first.isEmpty() && second.isEmpty()) {
            return RangeResult.Invalid("Invalid byte range")
        }

        if (first.isEmpty()) {
            val suffixLength = parseNonNegativeLong(second)
                ?: return RangeResult.Invalid("Invalid byte range")
            if (suffixLength == 0L || totalSize == 0L) {
                return RangeResult.Unsatisfiable("Requested range is not satisfiable")
            }
            val start = if (suffixLength >= totalSize) 0L else totalSize - suffixLength
            return RangeResult.Satisfiable(ByteRange(start, totalSize - 1L))
        }

        val start = parseNonNegativeLong(first)
            ?: return RangeResult.Invalid("Invalid byte range")
        if (start >= totalSize) {
            return RangeResult.Unsatisfiable("Requested range is not satisfiable")
        }

        if (second.isEmpty()) {
            return RangeResult.Satisfiable(ByteRange(start, totalSize - 1L))
        }

        val requestedEnd = parseNonNegativeLong(second)
            ?: return RangeResult.Invalid("Invalid byte range")
        if (requestedEnd < start) {
            return RangeResult.Unsatisfiable("Requested range is not satisfiable")
        }

        return RangeResult.Satisfiable(
            ByteRange(start, minOf(requestedEnd, totalSize - 1L))
        )
    }

    private fun parseNonNegativeLong(value: String): Long? {
        if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
        return value.toLongOrNull()
    }

    private fun rangeNotSatisfiable(totalSize: Long, message: String): Response =
        textResponse(Response.Status.RANGE_NOT_SATISFIABLE, message).apply {
            addHeader("Accept-Ranges", "bytes")
            addHeader("Content-Range", "bytes */$totalSize")
        }

    private fun textResponse(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, MIME_PLAINTEXT, body)

    private fun headerValue(session: IHTTPSession, name: String): String? =
        session.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun contentDisposition(fileEntry: TorrentFileEntry, attachment: Boolean): String {
        val displayName = fileEntry.name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace('"', '_')
            .replace('\r', '_')
            .replace('\n', '_')
            .ifBlank { "download" }
        val disposition = if (attachment) "attachment" else "inline"
        return "$disposition; filename=\"$displayName\""
    }

    private sealed class RangeResult {
        data class Satisfiable(val range: ByteRange) : RangeResult()
        data class Invalid(val message: String) : RangeResult()
        data class Unsatisfiable(val message: String) : RangeResult()
    }

    private data class ByteRange(val start: Long, val endInclusive: Long)

    companion object {
        fun getLocalLanIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                for (intf in interfaces) {
                    if (!intf.isUp || intf.isLoopback) continue
                    for (addr in intf.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress ?: "127.0.0.1"
                        }
                    }
                }
            } catch (_: Exception) {
            }
            return "127.0.0.1"
        }
    }
}
