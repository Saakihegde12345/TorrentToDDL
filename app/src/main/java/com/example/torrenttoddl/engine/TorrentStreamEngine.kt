package com.example.torrenttoddl.engine

import kotlinx.coroutines.CancellationException
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
import org.libtorrent4j.Sha1Hash
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
    private val stateLock = Any()
    private val sessionLock = Any()

    @Volatile
    private var activeHandle: TorrentHandle? = null

    @Volatile
    private var activeTorrentInfo: TorrentInfo? = null

    private var loadJob: Job? = null
    private var telemetryJob: Job? = null
    private var loadGeneration = 0L
    private var expectedInfoHash: Sha1Hash? = null
    private var retiredHandle: TorrentHandle? = null

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status

    private val alertListener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(
            AlertType.ADD_TORRENT.swig(),
            AlertType.METADATA_RECEIVED.swig(),
            AlertType.TORRENT_FINISHED.swig()
        )

        override fun alert(alert: Alert<*>) {
            try {
                when (alert.type()) {
                    AlertType.ADD_TORRENT -> {
                        val handle = (alert as AddTorrentAlert).handle()
                        handleFromAlert(handle)
                    }
                    AlertType.METADATA_RECEIVED -> {
                        val handle = (alert as MetadataReceivedAlert).handle()
                        handleFromAlert(handle)
                    }
                    else -> Unit
                }
            } catch (e: Exception) {
                reportAlertError(e)
            }
        }
    }

    fun startSession(): Boolean {
        synchronized(sessionLock) {
            if (sessionManager.isRunning) return true

            return try {
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
                true
            } catch (e: Exception) {
                runCatching { sessionManager.removeListener(alertListener) }
                synchronized(stateLock) {
                    _status.value = EngineStatus(
                        stateText = "Unable to start torrent session: ${errorText(e)}"
                    )
                }
                false
            }
        }
    }

    fun resolveMagnetOrTorrent(magnetUri: String) {
        val generation = beginLoad("Fetching magnet metadata from DHT/Peers...")
        val normalizedMagnet = magnetUri.trim()
        if (!normalizedMagnet.startsWith("magnet:", ignoreCase = true)) {
            reportLoadError(generation, "Invalid magnet URI")
            return
        }
        if (!startSession()) {
            reportLoadError(generation, "Torrent session is not available")
            return
        }

        val job = scope.launch(Dispatchers.IO) {
            try {
                val metadataBytes = sessionManager.fetchMagnet(
                    normalizedMagnet,
                    MAGNET_METADATA_TIMEOUT_SECONDS,
                    downloadRootDir
                )
                if (!isCurrentGeneration(generation)) return@launch

                if (metadataBytes == null) {
                    reportLoadError(
                        generation,
                        "Timed out fetching magnet metadata. Check swarm health."
                    )
                    return@launch
                }

                val torrentInfo = TorrentInfo.bdecode(metadataBytes)
                addTorrent(generation, torrentInfo)
            } catch (e: CancellationException) {
                if (isCurrentGeneration(generation) && isActive) throw e
            } catch (e: Exception) {
                reportLoadError(generation, "Error loading magnet: ${errorText(e)}")
            }
        }
        rememberLoadJob(generation, job)
    }

    fun loadTorrentBytes(bytes: ByteArray) {
        val generation = beginLoad("Loading torrent metadata...")
        if (!startSession()) {
            reportLoadError(generation, "Torrent session is not available")
            return
        }

        val torrentBytes = bytes.copyOf()
        val job = scope.launch(Dispatchers.IO) {
            try {
                val torrentInfo = TorrentInfo.bdecode(torrentBytes)
                addTorrent(generation, torrentInfo)
            } catch (e: CancellationException) {
                if (isCurrentGeneration(generation) && isActive) throw e
            } catch (e: Exception) {
                reportLoadError(generation, "Invalid .torrent file: ${errorText(e)}")
            }
        }
        rememberLoadJob(generation, job)
    }

    private fun addTorrent(generation: Long, torrentInfo: TorrentInfo) {
        synchronized(sessionLock) {
            if (!isCurrentGeneration(generation)) return

            synchronized(stateLock) {
                if (loadGeneration != generation) return
                expectedInfoHash = torrentInfo.infoHash()
            }

            sessionManager.download(torrentInfo, downloadRootDir)
        }
    }

    private fun handleFromAlert(handle: TorrentHandle) {
        synchronized(sessionLock) {
            val info = runCatching { handle.torrentFile() }.getOrNull() ?: return
            val generation = synchronized(stateLock) {
                val expected = expectedInfoHash ?: return
                if (isRetiredHandle(handle) || !safeInfoHashMatches(handle, expected)) return
                loadGeneration
            }

            onMetadataReady(handle, info, generation)
        }
    }

    private fun onMetadataReady(handle: TorrentHandle, info: TorrentInfo, generation: Long) {
        try {
            handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        } catch (e: Exception) {
            reportLoadError(generation, "Unable to configure torrent: ${errorText(e)}")
            removeTorrent(handle)
            return
        }

        val entries = try {
            val fileStorage = info.files()
            mutableListOf<TorrentFileEntry>().apply {
                for (index in 0 until fileStorage.numFiles()) {
                    add(
                        TorrentFileEntry(
                            index = index,
                            name = fileStorage.fileName(index),
                            relativePath = fileStorage.filePath(index),
                            sizeBytes = fileStorage.fileSize(index),
                            offsetInTorrent = fileStorage.fileOffset(index)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            reportLoadError(generation, "Unable to read torrent metadata: ${errorText(e)}")
            removeTorrent(handle)
            return
        }

        synchronized(stateLock) {
            if (loadGeneration != generation) return
            val expected = expectedInfoHash ?: return
            if (isRetiredHandle(handle) || !safeInfoHashMatches(handle, expected)) return

            activeHandle = handle
            activeTorrentInfo = info
            retiredHandle = null
            _status.value = EngineStatus(
                stateText = "DDL Stream Active (Sequential Mode)",
                torrentName = info.name(),
                files = entries,
                isReadyForDdl = true
            )
        }
        startTelemetryLoop(generation)
    }

    private fun startTelemetryLoop(generation: Long) {
        synchronized(stateLock) {
            if (loadGeneration != generation || activeHandle == null) return
            telemetryJob?.cancel()
            val job = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val handle = synchronized(stateLock) {
                        if (loadGeneration == generation) activeHandle else null
                    } ?: break

                    if (!safeIsValid(handle)) {
                        reportActiveFailure(generation, handle, "Torrent session is no longer active")
                        break
                    }

                    try {
                        val torrentStatus = handle.status()
                        var stillCurrent = true
                        synchronized(stateLock) {
                            if (loadGeneration != generation || activeHandle !== handle) {
                                stillCurrent = false
                            } else {
                                _status.value = _status.value.copy(
                                    progressPercent = torrentStatus.progress() * 100f,
                                    downloadSpeedKbps = torrentStatus.downloadPayloadRate() / 1024,
                                    peers = torrentStatus.numPeers()
                                )
                            }
                        }
                        if (!stillCurrent) break
                    } catch (e: Exception) {
                        reportActiveFailure(
                            generation,
                            handle,
                            "Torrent telemetry unavailable: ${errorText(e)}"
                        )
                        break
                    }
                    delay(1000L)
                }
            }
            telemetryJob = job
        }
    }

    fun openFileStream(
        fileIndex: Int,
        startByteOffset: Long,
        endByteOffset: Long? = null
    ): Pair<TorrentFileEntry, TorrentInputStream>? {
        val streamData = synchronized(stateLock) {
            val handle = activeHandle ?: return null
            val info = activeTorrentInfo ?: return null
            if (!safeIsValid(handle)) return null

            val entry = _status.value.files.getOrNull(fileIndex) ?: return null
            if (entry.sizeBytes < 0L || startByteOffset < 0L || startByteOffset > entry.sizeBytes) {
                return null
            }

            val readableEnd = endByteOffset ?: entry.sizeBytes
            if (readableEnd < startByteOffset || readableEnd > entry.sizeBytes) return null

            val targetFile = resolveTargetFile(entry.relativePath) ?: return null
            StreamData(
                entry = entry,
                handle = handle,
                info = info,
                targetFile = targetFile,
                endByteOffset = readableEnd
            )
        }

        return try {
            val inputStream = TorrentInputStream(
                torrentHandle = streamData.handle,
                targetFile = streamData.targetFile,
                fileOffsetInTorrent = streamData.entry.offsetInTorrent,
                fileLength = streamData.entry.sizeBytes,
                pieceLength = streamData.info.pieceLength(),
                initialByteOffset = startByteOffset,
                endByteOffset = streamData.endByteOffset
            )
            streamData.entry to inputStream
        } catch (_: Exception) {
            null
        }
    }

    fun stopSession() {
        synchronized(sessionLock) {
            val oldHandle = synchronized(stateLock) {
                loadGeneration += 1L
                loadJob?.cancel()
                loadJob = null
                telemetryJob?.cancel()
                telemetryJob = null
                retiredHandle = activeHandle
                val old = activeHandle
                activeHandle = null
                activeTorrentInfo = null
                expectedInfoHash = null
                _status.value = EngineStatus()
                old
            }

            removeTorrent(oldHandle)
            runCatching { sessionManager.removeListener(alertListener) }
            if (sessionManager.isRunning) {
                runCatching { sessionManager.stop() }
            }
        }
    }

    fun resetSession(): Boolean = synchronized(sessionLock) {
        stopSession()
        startSession()
    }

    private fun beginLoad(stateText: String): Long {
        synchronized(sessionLock) {
            val oldHandle = synchronized(stateLock) {
                loadGeneration += 1L
                loadJob?.cancel()
                loadJob = null
                telemetryJob?.cancel()
                telemetryJob = null
                retiredHandle = activeHandle
                val old = activeHandle
                activeHandle = null
                activeTorrentInfo = null
                expectedInfoHash = null
                _status.value = EngineStatus(stateText = stateText)
                old
            }
            removeTorrent(oldHandle)
            return synchronized(stateLock) { loadGeneration }
        }
    }

    private fun rememberLoadJob(generation: Long, job: Job) {
        synchronized(stateLock) {
            if (loadGeneration == generation) {
                loadJob = job
            } else {
                job.cancel()
            }
        }
    }

    private fun reportLoadError(generation: Long, message: String) {
        val oldHandle = synchronized(stateLock) {
            if (loadGeneration != generation) return
            val old = activeHandle
            activeHandle = null
            activeTorrentInfo = null
            expectedInfoHash = null
            _status.value = EngineStatus(stateText = message)
            old
        }
        removeTorrent(oldHandle)
    }

    private fun reportActiveFailure(generation: Long, handle: TorrentHandle, message: String) {
        val shouldRemove = synchronized(stateLock) {
            if (loadGeneration != generation || activeHandle !== handle) return
            activeHandle = null
            activeTorrentInfo = null
            expectedInfoHash = null
            _status.value = EngineStatus(stateText = message)
            true
        }
        if (shouldRemove) removeTorrent(handle)
    }

    private fun reportAlertError(error: Exception) {
        synchronized(stateLock) {
            if (expectedInfoHash == null) return
            _status.value = _status.value.copy(
                stateText = "Torrent alert error: ${errorText(error)}"
            )
        }
    }

    private fun isCurrentGeneration(generation: Long): Boolean = synchronized(stateLock) {
        loadGeneration == generation
    }

    private fun safeInfoHashMatches(handle: TorrentHandle, expected: Sha1Hash): Boolean =
        runCatching { handle.infoHash() == expected }.getOrDefault(false)

    private fun isRetiredHandle(handle: TorrentHandle): Boolean {
        val retired = retiredHandle ?: return false
        return retired === handle || runCatching { retired == handle }.getOrDefault(false)
    }

    private fun safeIsValid(handle: TorrentHandle): Boolean = try {
        handle.isValid
    } catch (_: Exception) {
        false
    }

    private fun removeTorrent(handle: TorrentHandle?) {
        if (handle == null) return
        synchronized(sessionLock) {
            try {
                if (safeIsValid(handle) && sessionManager.isRunning) {
                    sessionManager.remove(handle)
                }
            } catch (_: Exception) {
                // The session may already have removed an invalid handle.
            }
        }
    }

    private fun resolveTargetFile(relativePath: String): File? = try {
        val root = downloadRootDir.canonicalFile
        val target = File(root, relativePath).canonicalFile
        val rootPath = root.path.trimEnd(File.separatorChar)
        if (target.path == root.path || target.path.startsWith(rootPath + File.separator)) {
            target
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    private fun errorText(error: Throwable): String =
        error.localizedMessage?.trim()?.takeIf { it.isNotEmpty() } ?: error.javaClass.simpleName

    private data class StreamData(
        val entry: TorrentFileEntry,
        val handle: TorrentHandle,
        val info: TorrentInfo,
        val targetFile: File,
        val endByteOffset: Long
    )

    companion object {
        private const val MAGNET_METADATA_TIMEOUT_SECONDS = 60
    }
}
