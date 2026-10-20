package com.example.torrenttoddl.desktop

import com.example.torrenttoddl.engine.EngineStatus
import com.example.torrenttoddl.engine.TorrentFileEntry
import com.example.torrenttoddl.engine.TorrentStreamEngine
import com.example.torrenttoddl.server.LocalHttpStreamServer
import com.example.torrenttoddl.service.GatewayIdlePolicy
import java.io.File
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DesktopView(
    val running: Boolean = false,
    val busy: Boolean = false,
    val engine: EngineStatus = EngineStatus(),
    val saving: Boolean = false,
    val saveName: String = "",
    val savedBytes: Long = 0,
    val saveTotal: Long = 0,
    val saveRate: Long = 0,
    val saveMessage: String = "",
    val lanAddress: String = "",
    val notice: String = "Gateway is off. Your device is in control."
)

/** Lifecycle commands are serialized; native teardown never blocks the UI thread. */
class DesktopGateway(private val cacheRoot: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Mutex()
    private val mutableView = MutableStateFlow(DesktopView())
    val view: StateFlow<DesktopView> = mutableView.asStateFlow()
    private val errors = Channel<String>(Channel.UNLIMITED)
    val messages = errors.receiveAsFlow()
    @Volatile private var engine: TorrentStreamEngine? = null
    private var server: LocalHttpStreamServer? = null
    private var telemetry: Job? = null
    private var saveJob: Job? = null
    private val saveStream = AtomicReference<InputStream?>()
    private val lastActivity = AtomicLong(now())
    @Volatile private var visible = true
    private var closed = false
    @Volatile private var exportFinished = false

    init {
        scope.launch {
            while (isActive) {
                delay(1000)
                commands.withLock {
                    val snapshot = view.value
                    val status = snapshot.engine
                    val loading = !status.isReadyForDdl &&
                        (status.stateText.startsWith("Fetching") ||
                            status.stateText.startsWith("Loading"))
                    if (snapshot.running && GatewayIdlePolicy.shouldStop(
                            elapsedIdleMs = (now() - lastActivity.get()) / 1_000_000,
                            loading = loading,
                            activeDownloads = snapshot.saving || status.downloadSpeedKbps > 0,
                            activeStreams = server?.activeStreams ?: 0,
                            uiVisible = visible,
                            transfersFinished = exportFinished
                        )) {
                        stopInternal("Gateway stopped while minimized and idle.")
                    }
                }
            }
        }
    }

    fun start() = command { startInternal() }
    fun stop() = command { stopInternal("Gateway stopped.") }

    fun setVisible(value: Boolean) {
        if (visible != value) lastActivity.set(now())
        visible = value
        engine?.setUiVisible(value)
    }

    fun openMagnet(value: String) = command {
        require(value.trim().startsWith("magnet:", true)) {
            "Paste a valid magnet link."
        }
        requireCanChangeTorrent()
        startInternal()
        exportFinished = false
        lastActivity.set(now())
        engine!!.resolveMagnetOrTorrent(value.trim())
    }

    fun openTorrent(file: File) = command {
        requireCanChangeTorrent()
        require(file.length() in 1..MAX_METADATA_BYTES) {
            ".torrent files must be between 1 byte and 16 MiB."
        }
        val bytes = file.inputStream().use {
            it.readNBytes(MAX_METADATA_BYTES.toInt() + 1)
        }
        require(bytes.size in 1..MAX_METADATA_BYTES.toInt()) {
            "Invalid or oversized .torrent file."
        }
        startInternal()
        exportFinished = false
        lastActivity.set(now())
        engine!!.loadTorrentBytes(bytes)
    }

    fun save(entry: TorrentFileEntry, destination: File, expectedHash: String) = command {
        check(view.value.running && view.value.engine.isReadyForDdl) {
            "Load a torrent first."
        }
        check(saveJob?.isActive != true) {
            "A file is already being saved. Cancel it first."
        }
        require(view.value.engine.infoHash == expectedHash) {
            "The torrent changed. Select the file again."
        }
        val current = view.value.engine.files.firstOrNull { it.index == entry.index }
        require(current == entry) {
            "The selected torrent changed. Select the file again."
        }
        val input = engine!!.openFileStream(entry.index, 0L)?.second
            ?: error("Torrent file stream is unavailable.")
        saveStream.set(input)
        exportFinished = false
        mutableView.update { it.copy(
            saving = true, saveName = entry.name, savedBytes = 0,
            saveTotal = entry.sizeBytes, saveRate = 0,
            saveMessage = "Waiting for torrent pieces…"
        ) }
        saveJob = scope.launch {
            var previousAt = now()
            var previousBytes = 0L
            try {
                runInterruptible {
                    input.use { source ->
                        exportFile(source, entry.sizeBytes, destination.toPath()) { bytes ->
                            val at = now()
                            val delta = at - previousAt
                            if (delta >= 250_000_000 || bytes == entry.sizeBytes) {
                                val rate = if (delta > 0) {
                                    ((bytes - previousBytes) * 1_000_000_000.0 / delta).toLong()
                                } else 0
                                mutableView.update { it.copy(
                                    savedBytes = bytes,
                                    saveRate = rate,
                                    saveMessage = "Saving to ${destination.name}"
                                ) }
                                previousAt = at
                                previousBytes = bytes
                            }
                        }
                    }
                }
                exportFinished = true
                mutableView.update {
                    it.copy(saveMessage = "Saved to ${destination.absolutePath}")
                }
                errors.trySend("Saved ${entry.name}")
            } catch (cancelled: CancellationException) {
                mutableView.update {
                    it.copy(saveMessage = "Save cancelled. Partial file removed.")
                }
                throw cancelled
            } catch (error: Exception) {
                mutableView.update {
                    it.copy(saveMessage = "Save failed. Partial file removed.")
                }
                report(error)
            } finally {
                saveStream.compareAndSet(input, null)
                mutableView.update { it.copy(saving = false, saveRate = 0) }
                lastActivity.set(now())
            }
        }
    }

    fun cancelSave() = command { cancelSaveInternal() }

    fun lanLink(entry: TorrentFileEntry, expectedHash: String): String {
        check(view.value.engine.infoHash == expectedHash &&
            entry in view.value.engine.files) {
            "The torrent changed. Select the file again."
        }
        check(view.value.running) { "Start the gateway first." }
        val name = URLEncoder.encode(entry.name, "UTF-8").replace("+", "%20")
        return "${view.value.lanAddress}/files/${entry.index}/$name"
    }

    fun showError(error: Throwable) { report(error) }

    suspend fun shutdown() {
        try {
            commands.withLock {
                closed = true
                stopInternal("Closing…")
            }
        } finally {
            scope.cancel()
            errors.close()
        }
    }

    private fun command(block: suspend () -> Unit) {
        scope.launch {
            commands.withLock {
                if (closed) return@withLock
                mutableView.update { it.copy(busy = true) }
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: LinkageError) {
                    report(failure)
                } catch (failure: Exception) {
                    report(failure)
                } finally {
                    mutableView.update { it.copy(busy = false) }
                }
            }
        }
    }

    private fun startInternal() {
        if (engine != null) return
        DesktopNativeLibrary.load()
        check(cacheRoot.isDirectory || cacheRoot.mkdirs()) {
            "Cannot create the torrent cache directory."
        }
        val created = TorrentStreamEngine(cacheRoot, scope)
        var http: LocalHttpStreamServer? = null
        try {
            check(created.startSession()) { created.status.value.stateText }
            created.setUiVisible(visible)
            http = LocalHttpStreamServer(8080, created)
            http.start(5000, true)
            engine = created
            server = http
            lastActivity.set(now())
            mutableView.update { it.copy(
                running = true,
                engine = EngineStatus(),
                lanAddress = "http://${LocalHttpStreamServer.getLocalLanIpAddress()}:${http.listeningPort}",
                notice = "Gateway running · port ${http.listeningPort}"
            ) }
            telemetry = scope.launch {
                var prior = EngineStatus()
                created.status.collect { status ->
                    if (status.cachedBytes > prior.cachedBytes) lastActivity.set(now())
                    if (engine === created) {
                        mutableView.update { it.copy(engine = status) }
                    }
                    if (status.stateText != prior.stateText &&
                        ((!status.isReadyForDdl && status.stateText != "Idle" &&
                            !status.stateText.startsWith("Fetching") &&
                            !status.stateText.startsWith("Loading")) ||
                            status.stateText.contains("error", true) ||
                            status.stateText.startsWith("Unable") ||
                            status.stateText.startsWith("Invalid") ||
                            status.stateText.startsWith("Timed out") ||
                            status.stateText.contains("unavailable", true))) {
                        errors.trySend(status.stateText)
                    }
                    prior = status
                }
            }
        } catch (failure: Throwable) {
            http?.stop()
            created.stopSession()
            engine = null
            server = null
            throw failure
        }
    }

    private fun requireCanChangeTorrent() {
        check(saveJob?.isActive != true && (server?.activeStreams ?: 0) == 0) {
            "Finish or cancel active transfers before changing torrents."
        }
    }

    private suspend fun cancelSaveInternal() {
        saveJob?.cancel()
        saveStream.getAndSet(null)?.let { runCatching { it.close() } }
        saveJob?.join()
        saveJob = null
    }

    private suspend fun stopInternal(message: String) {
        mutableView.update { it.copy(busy = true) }
        cancelSaveInternal()
        telemetry?.cancelAndJoin()
        telemetry = null
        server?.stop()
        server = null
        engine?.stopSession()
        engine = null
        exportFinished = false
        mutableView.update { it.copy(
            running = false, busy = false, engine = EngineStatus(),
            lanAddress = "", notice = message
        ) }
    }

    private fun report(error: Throwable) {
        val detail = error.message?.takeIf { it.isNotBlank() }
            ?: error.javaClass.simpleName
        val message = detail.take(500)
        mutableView.update { it.copy(notice = message) }
        errors.trySend(message)
    }

    companion object {
        private const val MAX_METADATA_BYTES = 16L * 1024 * 1024
        private fun now() = System.nanoTime()
    }
}
