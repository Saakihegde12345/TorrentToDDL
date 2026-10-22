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
import java.io.InputStream

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
    val isReadyForDdl: Boolean = false,
    val infoHash: String = "",
    val cachedBytes: Long = 0L,
    val totalBytes: Long = 0L
)

interface TorrentFileSource {
    val status: StateFlow<EngineStatus>
    fun openFileStream(fileIndex: Int, startByteOffset: Long, endByteOffset: Long? = null): Pair<TorrentFileEntry, InputStream>?
}

data class EngineOptions(
    val seedAfterDownload: Boolean = false,
    val downloadLimitKiB: Int = 0,
    val uploadLimitKiB: Int = 0,
    val connections: Int = 200
)

class TorrentStreamEngine(
    private val downloadRootDir: File,
    private val scope: CoroutineScope,
    private val sessionManager: SessionManager = SessionManager(),
    private val options: EngineOptions = EngineOptions()
) : TorrentFileSource {
    private val stateLock = Any()
    private val sessionLock = Any()

    @Volatile
    private var activeHandle: TorrentHandle? = null

    @Volatile
    private var activePieceLength: Int = 0

    @Volatile
    private var uiVisible = true

    @Volatile private var userPaused = false

    private var loadJob: Job? = null
    private var telemetryJob: Job? = null
    private var loadGeneration = 0L
    private var expectedInfoHash: Sha1Hash? = null
    private var retiredHandle: TorrentHandle? = null

    private val _status = MutableStateFlow(EngineStatus())
    override val status: StateFlow<EngineStatus> = _status

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
            return try {
                val settings = SettingsPack()
                settings.downloadRateLimit(options.downloadLimitKiB * 1024)
                settings.uploadRateLimit(options.uploadLimitKiB * 1024)
                settings.connectionsLimit(options.connections)
                settings.setBoolean(
                    org.libtorrent4j.swig.settings_pack.bool_types.enable_dht.swigValue(),
                    true
                )
                settings.setBoolean(
                    org.libtorrent4j.swig.settings_pack.bool_types.enable_lsd.swigValue(),
                    true
                )
                if (sessionManager.isRunning) {
                    sessionManager.applySettings(settings)
                    sessionManager.resume()
                } else {
                    sessionManager.addListener(alertListener)
                    sessionManager.start(SessionParams(settings))
                }
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

        val job = scope.launch(Dispatchers.IO) {
            try {
                synchronized(sessionLock) {
                    if (!isCurrentGeneration(generation)) return@launch
                    if (!startSession()) {
                        reportLoadError(generation, "Torrent session is not available")
                        return@launch
                    }
                }
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

        val torrentBytes = bytes.copyOf()
        val job = scope.launch(Dispatchers.IO) {
            try {
                synchronized(sessionLock) {
                    if (!isCurrentGeneration(generation)) return@launch
                    if (!startSession()) {
                        reportLoadError(generation, "Torrent session is not available")
                        return@launch
                    }
                }
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
        // Alert handles borrow native memory from the alert. Never retain one or
        // capture it in a coroutine: the next alert batch can free that memory.
        val hash = handle.infoHash().toHex()
        val generation = synchronized(stateLock) {
            val expected = expectedInfoHash ?: return
            if (expected.toHex() != hash) return
            loadGeneration
        }
        scope.launch(Dispatchers.IO) {
            try {
                synchronized(sessionLock) {
                    val expected = synchronized(stateLock) {
                        if (loadGeneration != generation) return@launch
                        expectedInfoHash ?: return@launch
                    }
                    // find() returns an owned torrent_handle, unlike alert.handle().
                    val ownedHandle = sessionManager.find(expected) ?: return@launch
                    if (isRetiredHandle(ownedHandle)) return@launch
                    val info = ownedHandle.torrentFile() ?: return@launch
                    onMetadataReady(ownedHandle, info, generation)
                }
            } catch (e: Exception) {
                reportLoadError(generation, "Unable to initialize torrent: ${errorText(e)}")
            }
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
            // Snapshot primitive metadata while the session still owns info.
            activePieceLength = info.pieceLength()
            retiredHandle = null
            _status.value = EngineStatus(
                stateText = "DDL Stream Active (Sequential Mode)",
                torrentName = info.name(),
                files = entries,
                isReadyForDdl = true,
                infoHash = expected.toHex()
            )
        }
        startTelemetryLoop(generation)
    }

    private fun startTelemetryLoop(generation: Long) {
        synchronized(stateLock) {
            if (loadGeneration != generation || activeHandle == null) return
            telemetryJob?.cancel()
            val job = scope.launch(Dispatchers.IO) {
                var cachedTransfersPaused = false
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
                        // progress() also measures file-checking tasks. Use payload
                        // counters for the cache meter, and seeding state for pausing.
                        val cachedBytes = torrentStatus.totalWantedDone().coerceAtLeast(0L)
                        val totalBytes = torrentStatus.totalWanted().coerceAtLeast(0L)
                        val fullyCached = torrentStatus.isSeeding
                        val cachePercent = cachedPercent(cachedBytes, totalBytes, fullyCached)
                        if (fullyCached && !options.seedAfterDownload && !cachedTransfersPaused) {
                            synchronized(sessionLock) {
                                if (!isCurrentGeneration(generation)) return@launch
                                // A gateway need not keep seeding after caching. Keep
                                // verified disk data readable, but stop peer transfers
                                // and discovery until the next explicit torrent load.
                                sessionManager.pause()
                                val settings = SettingsPack()
                                settings.setBoolean(
                                    org.libtorrent4j.swig.settings_pack.bool_types.enable_dht.swigValue(), false
                                )
                                settings.setBoolean(
                                    org.libtorrent4j.swig.settings_pack.bool_types.enable_lsd.swigValue(), false
                                )
                                sessionManager.applySettings(settings)
                                cachedTransfersPaused = true
                            }
                        }
                        var stillCurrent = true
                        synchronized(stateLock) {
                            if (loadGeneration != generation || activeHandle !== handle) {
                                stillCurrent = false
                            } else {
                                _status.value = _status.value.copy(
                                    stateText = if (userPaused) "Paused" else if (fullyCached) {
                                        if (options.seedAfterDownload) "Seeding" else "Cached (seeding disabled)"
                                    } else "Downloading",
                                    progressPercent = cachePercent,
                                    cachedBytes = cachedBytes,
                                    totalBytes = totalBytes,
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
                    delay(if (cachedTransfersPaused) 10000L else if (uiVisible) 1000L else 5000L)
                }
            }
            telemetryJob = job
        }
    }

    override fun openFileStream(
        fileIndex: Int,
        startByteOffset: Long,
        endByteOffset: Long?
    ): Pair<TorrentFileEntry, TorrentInputStream>? {
        val streamData = synchronized(stateLock) {
            val handle = activeHandle ?: return null
            val pieceLength = activePieceLength
            if (pieceLength <= 0) return null
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
                pieceLength = pieceLength,
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
                pieceLength = streamData.pieceLength,
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
                activePieceLength = 0
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

    fun resetSession() {
        stopSession()
    }

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
    }

    fun pauseTransfers(paused: Boolean) {
        synchronized(sessionLock) {
            userPaused = paused
            if (paused) sessionManager.pause()
            else if (options.seedAfterDownload || _status.value.progressPercent < 100f) sessionManager.resume()
        }
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
                activePieceLength = 0
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
            activePieceLength = 0
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
            activePieceLength = 0
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
        val pieceLength: Int,
        val targetFile: File,
        val endByteOffset: Long
    )

    companion object {
        private const val MAGNET_METADATA_TIMEOUT_SECONDS = 60
    }
}

// Never use libtorrent's current-task progress as downloaded-byte progress.
internal fun cachedPercent(cachedBytes: Long, totalBytes: Long, seeding: Boolean): Float =
    if (totalBytes > 0) (cachedBytes.coerceAtLeast(0L).toDouble() * 100.0 / totalBytes).toFloat().coerceIn(0f, 100f)
    else if (seeding) 100f else 0f
