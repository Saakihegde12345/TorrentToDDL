package com.example.torrenttoddl.server

import com.example.torrenttoddl.engine.TorrentStreamEngine
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLConnection

class LocalHttpStreamServer(
    private val port: Int = 8080,
    private val engine: TorrentStreamEngine
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")

        val segments = uri.trim('/').split("/")
        if (segments.size < 2 || segments[0] != "files") {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                MIME_PLAINTEXT,
                "Usage: /files/{fileIndex}/{fileName}"
            )
        }

        val fileIndex = segments[1].toIntOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid file index")

        val rangeHeader = session.headers["range"]
        var startByte = 0L
        var endByte = -1L

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val rangeSpec = rangeHeader.removePrefix("bytes=").split("-")
            startByte = rangeSpec.getOrNull(0)?.toLongOrNull() ?: 0L
            endByte = rangeSpec.getOrNull(1)?.toLongOrNull() ?: -1L
        }

        val streamPair = engine.openFileStream(fileIndex, startByte)
            ?: return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                MIME_PLAINTEXT,
                "Torrent metadata not ready yet"
            )

        val (fileEntry, torrentInputStream) = streamPair
        val totalSize = fileEntry.sizeBytes
        if (endByte < 0 || endByte >= totalSize) {
            endByte = totalSize - 1
        }
        val contentLength = (endByte - startByte) + 1
        val mimeType = URLConnection.guessContentTypeFromName(fileEntry.name) ?: "application/octet-stream"

        return if (rangeHeader != null) {
            newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                mimeType,
                torrentInputStream,
                contentLength
            ).apply {
                addHeader("Accept-Ranges", "bytes")
                addHeader("Content-Range", "bytes $startByte-$endByte/$totalSize")
                addHeader("Content-Length", contentLength.toString())
                addHeader("Content-Disposition", "inline; filename=\"${fileEntry.name}\"")
            }
        } else {
            newFixedLengthResponse(
                Response.Status.OK,
                mimeType,
                torrentInputStream,
                totalSize
            ).apply {
                addHeader("Accept-Ranges", "bytes")
                addHeader("Content-Length", totalSize.toString())
                addHeader("Content-Disposition", "attachment; filename=\"${fileEntry.name}\"")
            }
        }
    }

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
