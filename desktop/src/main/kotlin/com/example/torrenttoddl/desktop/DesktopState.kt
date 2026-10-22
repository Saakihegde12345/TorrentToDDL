package com.example.torrenttoddl.desktop

import com.example.torrenttoddl.engine.EngineStatus
import com.example.torrenttoddl.engine.TorrentFileEntry
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Properties

data class DesktopTorrent(val id: String, val source: String, val engine: EngineStatus = EngineStatus(), val paused: Boolean = false)
data class DesktopExport(
    val id: String, val torrentId: String, val name: String, val destination: String,
    val bytes: Long = 0, val total: Long = 0, val rate: Long = 0, val state: String = "Queued",
    val finished: Boolean = false, val failed: Boolean = false
)
data class DesktopView(
    val running: Boolean = false, val busy: Boolean = false, val torrents: List<DesktopTorrent> = emptyList(),
    val exports: List<DesktopExport> = emptyList(), val lanAddress: String = "", val backendPid: Long = 0,
    val activeStreams: Int = 0, val notice: String = "Backend stopped"
)

internal fun encodeText(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
internal fun decodeText(value: String): String = String(Base64.getDecoder().decode(value), Charsets.UTF_8)
internal fun encodeProperties(value: Properties): String = ByteArrayOutputStream().use {
    value.store(it, null); Base64.getEncoder().encodeToString(it.toByteArray())
}
internal fun decodeProperties(value: String) = Properties().apply { Base64.getDecoder().decode(value).inputStream().use { load(it) } }

internal fun DesktopView.encode(): String = encodeProperties(Properties().apply {
    fun put(key: String, value: Any) { setProperty(key, value.toString()) }
    put("running", running); put("busy", busy); put("lanAddress", lanAddress); put("pid", backendPid)
    put("streams", activeStreams); put("notice", notice); put("torrentCount", torrents.size); put("exportCount", exports.size)
    torrents.forEachIndexed { i, t ->
        val p = "t$i."
        put(p + "id", t.id); put(p + "source", t.source); put(p + "paused", t.paused)
        val e = t.engine
        put(p + "state", e.stateText); put(p + "name", e.torrentName); put(p + "hash", e.infoHash)
        put(p + "cached", e.cachedBytes); put(p + "total", e.totalBytes); put(p + "speed", e.downloadSpeedKbps)
        put(p + "peers", e.peers); put(p + "ready", e.isReadyForDdl); put(p + "progress", e.progressPercent)
        put(p + "files", e.files.size)
        e.files.forEachIndexed { j, f ->
            val q = p + "f$j."
            put(q + "index", f.index); put(q + "name", f.name); put(q + "path", f.relativePath)
            put(q + "size", f.sizeBytes); put(q + "offset", f.offsetInTorrent)
        }
    }
    exports.forEachIndexed { i, e ->
        val p = "e$i."
        put(p + "id", e.id); put(p + "torrent", e.torrentId); put(p + "name", e.name); put(p + "path", e.destination)
        put(p + "bytes", e.bytes); put(p + "total", e.total); put(p + "rate", e.rate); put(p + "state", e.state)
        put(p + "finished", e.finished); put(p + "failed", e.failed)
    }
})

internal fun decodeView(value: String): DesktopView {
    val p = decodeProperties(value)
    fun text(key: String) = p.getProperty(key, "")
    fun long(key: String) = text(key).toLong()
    fun int(key: String) = text(key).toInt()
    fun bool(key: String) = text(key).toBoolean()
    return DesktopView(bool("running"), bool("busy"), List(int("torrentCount")) { i ->
        val t = "t$i."
        DesktopTorrent(text(t + "id"), text(t + "source"), EngineStatus(
            stateText = text(t + "state"), torrentName = text(t + "name"), progressPercent = text(t + "progress").toFloat(),
            downloadSpeedKbps = int(t + "speed"), peers = int(t + "peers"),
            files = List(int(t + "files")) { j ->
                val f = t + "f$j."
                TorrentFileEntry(int(f + "index"), text(f + "name"), text(f + "path"), long(f + "size"), long(f + "offset"))
            }, isReadyForDdl = bool(t + "ready"), infoHash = text(t + "hash"), cachedBytes = long(t + "cached"), totalBytes = long(t + "total")
        ), bool(t + "paused"))
    }, List(int("exportCount")) { i ->
        val e = "e$i."
        DesktopExport(text(e + "id"), text(e + "torrent"), text(e + "name"), text(e + "path"),
            long(e + "bytes"), long(e + "total"), long(e + "rate"), text(e + "state"), bool(e + "finished"), bool(e + "failed"))
    }, text("lanAddress"), long("pid"), int("streams"), text("notice"))
}
