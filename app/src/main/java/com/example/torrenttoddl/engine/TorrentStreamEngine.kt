package com.example.torrenttoddl.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.MetadataReceivedAlert
import java.io.File

data class TorrentFileEntry(
    val index: Int,
    val name: String,
    val relativePath: String,
    val sizeBytes: Long,
    val offsetInTorrent: Long
)

data class EngineStatus(
    val stateText: String = "Idle",
    val torrentName: String = "",
    val progressPercent: Float = 0f,
    val downloadSpeedKbps: Int = 0,
    val peers: Int = 0,
    val files: List<TorrentFileEntry> = emptyList(),
    val isReadyForDdl: Boolean = false
)

class TorrentStreamEngine(
    private val downloadRootDir: File,
    private val scope: CoroutineScope
) {
    private val sessionManager = SessionManager()
    private var activeHandle: TorrentHandle? = null
    private var activeTorrentInfo: TorrentInfo? = null
    private var telemetryJob: Job? = null

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status

    private val alertListener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(
            AlertType.ADD_TORRENT.swig(),
            AlertType.METADATA_RECEIVED.swig(),
            AlertType.TORRENT_FINISHED.swig()
        )

        override fun alert(alert: Alert<*>) {
            when (alert.type()) {
                AlertType.ADD_TORRENT -> {
                    val handle = (alert as AddTorrentAlert).handle()
                    activeHandle = handle
                    handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                    if (handle.torrentFile() != null) {
                        onMetadataReady(handle, handle.torrentFile())
                    }
                }
                AlertType.METADATA_RECEIVED -> {
                    val handle = (alert as MetadataReceivedAlert).handle()
                    activeHandle = handle
                    handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                    onMetadataReady(handle, handle.torrentFile())
                }
                else -> {}
            }
        }
    }

    fun startSession() {
        if (sessionManager.isRunning) return
        val settings = SettingsPack()
        settings.setBoolean(
            org.libtorrent4j.swig.settings_pack.bool_types.enable_dht.swigValue(),
            true
        )
        settings.setBoolean(
            org.libtorrent4j.swig.settings_pack.bool_types.enable_lsd.swigValue(),
            true
        )
        sessionManager.addListener(alertListener)
        sessionManager.start(SessionParams(settings))
    }

    fun resolveMagnetOrTorrent(magnetUri: String) {
        startSession()
        _status.value = EngineStatus(stateText = "Fetching magnet metadata from DHT/Peers...")

        scope.launch(Dispatchers.IO) {
            try {
                val metadataBytes = sessionManager.fetchMagnet(magnetUri, 60, downloadRootDir)
                if (metadataBytes != null) {
                    val ti = TorrentInfo.bdecode(metadataBytes)
                    sessionManager.download(ti, downloadRootDir)
                } else {
                    _status.value = EngineStatus(stateText = "Timed out fetching magnet metadata. Check swarm health.")
                }
            } catch (e: Exception) {
                _status.value = EngineStatus(stateText = "Error: ${e.localizedMessage}")
            }
        }
        startTelemetryLoop()
    }

    fun loadTorrentBytes(bytes: ByteArray) {
        startSession()
        scope.launch(Dispatchers.IO) {
            try {
                val ti = TorrentInfo.bdecode(bytes)
                sessionManager.download(ti, downloadRootDir)
                startTelemetryLoop()
            } catch (e: Exception) {
                _status.value = EngineStatus(stateText = "Invalid .torrent file: ${e.localizedMessage}")
            }
        }
    }

    private fun onMetadataReady(handle: TorrentHandle, info: TorrentInfo) {
        activeTorrentInfo = info
        val fileStorage = info.files()
        val entries = mutableListOf<TorrentFileEntry>()

        for (i in 0 until fileStorage.numFiles()) {
            entries.add(
                TorrentFileEntry(
                    index = i,
                    name = fileStorage.fileName(i),
                    relativePath = fileStorage.filePath(i),
                    sizeBytes = fileStorage.fileSize(i),
                    offsetInTorrent = fileStorage.fileOffset(i)
                )
            )
        }

        _status.value = _status.value.copy(
            stateText = "DDL Stream Active (Sequential Mode)",
            torrentName = info.name(),
            files = entries,
            isReadyForDdl = true
        )
    }

    private fun startTelemetryLoop() {
        telemetryJob?.cancel()
        telemetryJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val handle = activeHandle
                if (handle != null && handle.isValid) {
                    val s = handle.status()
                    _status.value = _status.value.copy(
                        progressPercent = s.progress() * 100f,
                        downloadSpeedKbps = s.downloadPayloadRate() / 1024,
                        peers = s.numPeers()
                    )
                }
                delay(1000)
            }
        }
    }

    fun openFileStream(fileIndex: Int, startByteOffset: Long): Pair<TorrentFileEntry, TorrentInputStream>? {
        val handle = activeHandle ?: return null
        val info = activeTorrentInfo ?: return null
        val entry = _status.value.files.getOrNull(fileIndex) ?: return null

        val targetFile = File(downloadRootDir, entry.relativePath)
        val inputStream = TorrentInputStream(
            torrentHandle = handle,
            targetFile = targetFile,
            fileOffsetInTorrent = entry.offsetInTorrent,
            fileLength = entry.sizeBytes,
            pieceLength = info.pieceLength(),
            initialByteOffset = startByteOffset
        )
        return Pair(entry, inputStream)
    }

    fun stopSession() {
        telemetryJob?.cancel()
        sessionManager.removeListener(alertListener)
        if (sessionManager.isRunning) {
            sessionManager.stop()
        }
    }
}
