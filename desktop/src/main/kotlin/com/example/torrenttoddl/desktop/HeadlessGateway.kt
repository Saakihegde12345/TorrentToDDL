package com.example.torrenttoddl.desktop

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.runBlocking

/** Deployable gateway: download keys cannot administer torrents. */
internal fun headlessGateway(root: File) {
    root.mkdirs()
    val file = File(root, "settings.properties")
    val p = readProperties(file)
    System.getenv("TTD_DOWNLOAD_KEY")?.let { p.setProperty("accessToken", it) }
    System.getenv("TTD_ADMIN_KEY")?.let { p.setProperty("adminToken", it) }
    if (!p.containsKey("cacheDirectory")) p.setProperty("cacheDirectory", File(root, "cache").absolutePath)
    if (!p.containsKey("downloadDirectory")) p.setProperty("downloadDirectory", File(root, "downloads").absolutePath)
    val settings = DesktopSettings.from(p).copy(shareOnLan = true)
    writeProperties(file, settings.properties())
    val gateway = DesktopGateway(root, settings, remoteControl = true)
    runBlocking { gateway.start().join() }
    check(gateway.view.value.running) { gateway.view.value.notice }
    Runtime.getRuntime().addShutdownHook(Thread { runBlocking { gateway.shutdown() } })
    println("TorrentToDDL server listening on ${settings.port}. Keys are stored in settings.properties.")
    CountDownLatch(1).await()
}

internal fun DesktopGateway.controlResponse(request: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
    fun response(code: NanoHTTPD.Response.Status, text: String) =
        NanoHTTPD.newFixedLengthResponse(code, "application/json; charset=utf-8", text).apply {
            addHeader("Cache-Control", "no-store"); addHeader("Referrer-Policy", "no-referrer")
        }
    if (request.method == NanoHTTPD.Method.GET && request.uri == "/api/status") {
        val value = view.value
        val torrents = value.torrents.joinToString(",") { t ->
            val files = t.engine.files.joinToString(",") { f ->
                "{\"index\":${f.index},\"name\":${jsonString(f.name)},\"size\":${f.sizeBytes}}"
            }
            "{\"id\":${jsonString(t.id)},\"name\":${jsonString(t.engine.torrentName)},\"status\":${jsonString(t.engine.stateText)}," +
                "\"cachedBytes\":${t.engine.cachedBytes},\"totalBytes\":${t.engine.totalBytes},\"files\":[$files]}"
        }
        return response(NanoHTTPD.Response.Status.OK, "{\"running\":${value.running},\"torrents\":[$torrents]}")
    }
    if (request.method != NanoHTTPD.Method.POST) return response(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "{\"error\":\"Use GET /api/status or POST commands\"}")
    val length = request.headers["content-length"]?.toLongOrNull()
    if (length == null || length !in 0..65536) return response(NanoHTTPD.Response.Status.BAD_REQUEST, "{\"error\":\"Body exceeds 64 KiB or Content-Length is missing\"}")
    return try {
        request.parseBody(mutableMapOf())
        fun field(name: String): String = request.parameters[name]?.singleOrNull() ?: error("Missing $name")
        when (request.uri) {
            "/api/magnets" -> {
                val magnet = field("magnet")
                require(magnet.startsWith("magnet:", true) && magnet.length <= 65536) { "Invalid magnet link" }
                runBlocking { openMagnet(magnet).join() }
            }
            "/api/pause" -> runBlocking { pause(field("id"), field("paused").toBooleanStrict()).join() }
            "/api/remove" -> runBlocking { remove(field("id")).join() }
            "/api/save" -> runBlocking { save(field("id"), field("file").toInt()).join() }
            else -> return response(NanoHTTPD.Response.Status.NOT_FOUND, "{\"error\":\"Unknown endpoint\"}")
        }
        response(NanoHTTPD.Response.Status.ACCEPTED, "{\"accepted\":true}")
    } catch (error: Exception) {
        response(NanoHTTPD.Response.Status.BAD_REQUEST, "{\"error\":${jsonString(error.message ?: "Invalid request")}}")
    }
}

internal fun jsonString(text: String): String = buildString {
    append('"')
    text.forEach { c -> when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
    } }
    append('"')
}
