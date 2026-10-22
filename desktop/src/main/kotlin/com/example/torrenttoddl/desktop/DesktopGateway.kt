package com.example.torrenttoddl.desktop

import com.example.torrenttoddl.engine.*
import com.example.torrenttoddl.server.LocalHttpStreamServer
import java.io.File
import java.io.InputStream
import java.net.URLEncoder
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Runs only in the disposable backend process. The desktop never loads JNI. */
class DesktopGateway(private val root: File, private val settings: DesktopSettings, private val remoteControl: Boolean = false) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Mutex()
    private val mutableView = MutableStateFlow(DesktopView())
    val view: StateFlow<DesktopView> = mutableView.asStateFlow()
    // Each active torrent has an isolated session/cache; the download queue is
    // bounded to eight slots. A shared multi-handle session is the upgrade path.
    private val engines = ConcurrentHashMap<String, TorrentStreamEngine>()
    private val collectors = mutableMapOf<String, Job>()
    private val exports = mutableMapOf<String, Job>()
    private val streams = ConcurrentHashMap<String, InputStream>()
    private var server: LocalHttpStreamServer? = null
    private var monitor: Job? = null
    private val queueFile = File(root, "queue.properties")

    init {
        val p = readProperties(queueFile)
        val ids = p.getProperty("ids", "").split(',').filter { it.matches(Regex("[a-f0-9-]{36}")) }
        mutableView.value = DesktopView(torrents = ids.map { id ->
            DesktopTorrent(id, p.getProperty("$id.source", ""),
                EngineStatus(stateText = "Stopped", torrentName = p.getProperty("$id.name", "")),
                p.getProperty("$id.paused", "false").toBoolean())
        })
    }

    fun start() = command {
        if (server != null) return@command
        settings.validate()
        DesktopNativeLibrary.load()
        val listener = LocalHttpStreamServer(
            settings.port, sourceForTorrent = { engines[it] }, accessToken = settings.accessToken,
            bindAddress = if (settings.shareOnLan) "0.0.0.0" else "127.0.0.1",
            controlHandler = if (remoteControl) ({ request -> controlResponse(request) }) else null,
            adminToken = settings.adminToken
        )
        listener.start(5000, true)
        server = listener
        val host = if (settings.shareOnLan) LocalHttpStreamServer.getLocalLanIpAddress() else "127.0.0.1"
        mutableView.update { it.copy(running = true, lanAddress = "http://$host:${listener.listeningPort}",
            backendPid = ProcessHandle.current().pid(), notice = "Gateway online") }
        startQueued()
        monitor = scope.launch {
            while (isActive) {
                delay(1000)
                commands.withLock {
                    mutableView.update { it.copy(activeStreams = server?.activeStreams ?: 0) }
                    startQueued()
                }
            }
        }
    }

    fun openMagnet(value: String) = command {
        val magnet = value.trim()
        require(magnet.startsWith("magnet:", true) && magnet.length <= 65536) { "Paste a valid magnet link." }
        require(view.value.torrents.none { it.source == magnet }) { "This magnet is already in the queue." }
        add(DesktopTorrent(UUID.randomUUID().toString(), magnet, EngineStatus(stateText = "Queued")))
    }

    fun openTorrent(file: File) = command {
        require(file.length() in 1..16L * 1024 * 1024) { "Torrent metadata must be between 1 byte and 16 MiB." }
        val bytes = file.inputStream().use { it.readNBytes(16 * 1024 * 1024 + 1) }
        require(bytes.size in 1..16 * 1024 * 1024) { "Torrent metadata exceeds 16 MiB." }
        // Decode before accepting a queue entry, rather than persisting invalid input.
        val info = org.libtorrent4j.TorrentInfo.bdecode(bytes)
        require(view.value.torrents.none { it.engine.infoHash == info.infoHash().toHex() }) { "This torrent is already in the queue." }
        val id = UUID.randomUUID().toString()
        val saved = File(root, "metadata/$id.torrent")
        saved.parentFile.mkdirs()
        saved.writeBytes(bytes)
        add(DesktopTorrent(id, saved.absolutePath, EngineStatus(stateText = "Queued", torrentName = info.name())))
    }

    private fun add(torrent: DesktopTorrent) {
        require(view.value.torrents.size < 100) { "The queue supports up to 100 torrents." }
        mutableView.update { it.copy(torrents = it.torrents + torrent) }
        persist()
        startQueued()
    }

    private fun startQueued() {
        if (!view.value.running) return
        var downloading = view.value.torrents.count {
            engines.containsKey(it.id) && !it.paused && it.engine.progressPercent < 100f
        }
        for (torrent in view.value.torrents) {
            if (downloading >= settings.concurrentDownloads) break
            if (torrent.paused || engines.containsKey(torrent.id)) continue
            val cache = File(settings.cacheDirectory, torrent.id)
            check(cache.isDirectory || cache.mkdirs()) { "Cannot create torrent cache: $cache" }
            val created = TorrentStreamEngine(cache, scope, options = EngineOptions(
                settings.seedAfterDownload, settings.downloadLimitKiB, settings.uploadLimitKiB, settings.connectionsPerTorrent
            ))
            engines[torrent.id] = created
            collectors[torrent.id] = scope.launch {
                created.status.collect { status ->
                    mutableView.update { v -> v.copy(torrents = v.torrents.map { t ->
                        if (t.id == torrent.id) t.copy(engine = status) else t
                    }) }
                }
            }
            if (torrent.source.startsWith("magnet:", true)) created.resolveMagnetOrTorrent(torrent.source)
            else created.loadTorrentBytes(File(torrent.source).readBytes())
            downloading++
        }
    }

    fun pause(id: String, paused: Boolean) = command {
        check(view.value.torrents.any { it.id == id }) { "Torrent no longer exists." }
        if (!paused) {
            val otherDownloads = view.value.torrents.count {
                it.id != id && !it.paused && engines.containsKey(it.id) && it.engine.progressPercent < 100f
            }
            require(otherDownloads < settings.concurrentDownloads) { "All download slots are in use. Pause another torrent first." }
        }
        engines[id]?.pauseTransfers(paused)
        mutableView.update { it.copy(torrents = it.torrents.map { t -> if (t.id == id) t.copy(paused = paused) else t }) }
        persist()
        startQueued()
    }

    fun remove(id: String) = command {
        require(view.value.exports.none { it.torrentId == id && !it.finished }) { "Cancel this torrent's saves before removing it." }
        require((server?.activeStreams ?: 0) == 0) { "Wait for shared downloads to finish before removing a torrent." }
        collectors.remove(id)?.cancelAndJoin()
        engines.remove(id)?.stopSession()
        mutableView.update { it.copy(torrents = it.torrents.filterNot { t -> t.id == id }) }
        persist()
        // Removing a queue entry preserves both cached data and saved files.
        startQueued()
    }

    fun save(id: String, index: Int, destination: File? = null) = command {
        val t = view.value.torrents.firstOrNull { it.id == id } ?: error("Torrent no longer exists.")
        val file = t.engine.files.firstOrNull { it.index == index } ?: error("Wait for file metadata.")
        enqueueSave(t, file, destination ?: File(settings.downloadDirectory, safeName(file.name)))
    }

    fun saveAll(id: String) = command {
        val torrent = view.value.torrents.firstOrNull { it.id == id } ?: error("Torrent no longer exists.")
        require(torrent.engine.isReadyForDdl) { "Wait for file metadata." }
        val folder = File(settings.downloadDirectory, safeName(torrent.engine.torrentName))
        val destinations = torrent.engine.files.map { file ->
            file to safeExportPath(folder, file.relativePath)
        }
        require(destinations.none { it.second.exists() }) { "An output file already exists. Choose another download folder or save files individually." }
        destinations.forEach { (file, destination) -> enqueueSave(torrent, file, destination) }
    }

    private fun enqueueSave(torrent: DesktopTorrent, file: TorrentFileEntry, destination: File) {
        val target = destination.canonicalFile
        require(!target.exists()) { "File already exists: ${target.name}" }
        require(view.value.exports.none { !it.finished && it.destination == target.path }) { "This destination is already queued." }
        val id = UUID.randomUUID().toString()
        val record = DesktopExport(id, torrent.id, file.name, target.path, total = file.sizeBytes)
        mutableView.update { it.copy(exports = (it.exports + record).takeLast(1000)) }
        // Exports share one disk writer; all torrents can continue receiving pieces.
        val previous = exports.values.lastOrNull()
        exports[id] = scope.launch {
            try {
                previous?.join()
                ensureActive()
                val source = engines[torrent.id]?.openFileStream(file.index, 0)?.second ?: error("Torrent is not available.")
                streams[id] = source
                target.parentFile.mkdirs()
                var sampledAt = System.nanoTime()
                var sampledBytes = 0L
                updateExport(id) { it.copy(state = "Saving") }
                runInterruptible {
                    source.use { input -> exportFile(input, file.sizeBytes, target.toPath()) { bytes ->
                        val at = System.nanoTime()
                        val elapsed = at - sampledAt
                        if (elapsed >= 250_000_000 || bytes == file.sizeBytes) {
                            val rate = if (elapsed > 0) ((bytes - sampledBytes) * 1e9 / elapsed).toLong() else 0
                            updateExport(id) { it.copy(bytes = bytes, rate = rate) }
                            sampledAt = at; sampledBytes = bytes
                        }
                    } }
                }
                updateExport(id) { it.copy(bytes = file.sizeBytes, state = "Saved", finished = true, rate = 0) }
            } catch (cancelled: CancellationException) {
                updateExport(id) { it.copy(state = "Cancelled", finished = true, rate = 0) }
                throw cancelled
            } catch (error: Exception) {
                updateExport(id) { it.copy(state = error.message ?: "Save failed", finished = true, failed = true, rate = 0) }
            } finally { streams.remove(id)?.let { runCatching { it.close() } } }
        }
    }

    fun cancelSave(id: String) = command {
        exports[id]?.cancel()
        streams.remove(id)?.let { runCatching { it.close() } }
        exports[id]?.join()
    }

    private fun updateExport(id: String, transform: (DesktopExport) -> DesktopExport) {
        mutableView.update { v -> v.copy(exports = v.exports.map { if (it.id == id) transform(it) else it }) }
    }

    private fun persist() {
        writeProperties(queueFile, Properties().apply {
            setProperty("ids", view.value.torrents.joinToString(",") { it.id })
            view.value.torrents.forEach {
                setProperty("${it.id}.source", it.source); setProperty("${it.id}.name", it.engine.torrentName)
                setProperty("${it.id}.paused", "${it.paused}")
            }
        })
    }

    private fun command(block: suspend () -> Unit): Job = scope.launch {
        commands.withLock {
            mutableView.update { it.copy(busy = true) }
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableView.update { it.copy(notice = error.message ?: error.javaClass.simpleName) } }
            finally { mutableView.update { it.copy(busy = false) } }
        }
    }

    suspend fun shutdown() {
        commands.withLock {
            val finalNotice = if (view.value.running) "Backend stopped" else view.value.notice
            monitor?.cancel()
            exports.values.forEach { it.cancel() }
            streams.values.forEach { runCatching { it.close() } }
            exports.values.toList().joinAll()
            server?.stop(); server = null
            collectors.values.forEach { it.cancel() }
            engines.values.forEach { it.stopSession() }
            engines.clear()
            persist()
            mutableView.update { it.copy(running = false, backendPid = 0, lanAddress = "", activeStreams = 0, notice = finalNotice) }
        }
        scope.cancel()
    }
}

internal fun safeName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')
    .replace(Regex("[<>:\"/\\\\|?*\\p{Cntrl}]"), "_").trim().trimEnd('.').take(180)
    .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "download"

internal fun safeExportPath(root: File, relative: String): File {
    val base = root.canonicalFile
    val result = File(base, relative.replace('\\', '/')).canonicalFile
    require(result.toPath().startsWith(base.toPath()) && result != base) { "Torrent path escapes the download folder." }
    return result
}

fun torrentLink(address: String, torrentId: String, file: TorrentFileEntry, token: String): String =
    "$address/torrents/$torrentId/files/${file.index}/${URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")}?token=$token"
