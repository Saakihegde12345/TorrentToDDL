package com.example.torrenttoddl.service

import android.Manifest
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.torrenttoddl.MainActivity
import com.example.torrenttoddl.engine.TorrentStreamEngine
import com.example.torrenttoddl.server.LocalHttpStreamServer
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Keep terminal results visible even after auto-stop removes the notification.
data class GatewayDownload(
    val id: Long, val name: String, val bytes: Long = 0L, val total: Long = -1L,
    val message: String = "Queued", val finished: Boolean = false, val failed: Boolean = false,
    val lastProgressAt: Long = SystemClock.elapsedRealtime(),
    val sampledAt: Long = SystemClock.elapsedRealtime(), val bytesPerSecond: Long = 0L
)

data class GatewayState(
    val running: Boolean = false, val changing: Boolean = false,
    val message: String = "Gateway stopped", val downloads: List<GatewayDownload> = emptyList(),
    val error: String? = null, val errorSerial: Long = 0L
)

class TorrentGatewayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val transition = Mutex()
    private val _state = MutableStateFlow(GatewayState())
    val state: StateFlow<GatewayState> = _state
    lateinit var engine: TorrentStreamEngine
        private set
    private var httpServer: LocalHttpStreamServer? = null
    private var monitorJob: Job? = null
    private var foregroundActive = false
    private var lastActivity = 0L
    private var hadTransfer = false
    private val binder = LocalBinder()
    private val downloads get() = getSystemService(DOWNLOAD_SERVICE) as DownloadManager

    inner class LocalBinder : Binder() { fun getService(): TorrentGatewayService = this@TorrentGatewayService }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Torrent DDL Gateway", NotificationManager.IMPORTANCE_LOW)
        )
        val cache = File(getExternalFilesDir(null) ?: filesDir, "torrent_cache").apply { mkdirs() }
        engine = TorrentStreamEngine(cache, serviceScope)
        serviceScope.launch {
            var previous = ""
            engine.status.collect { status ->
                val message = status.stateText
                if (message != previous && (message.startsWith("Unable") || message.startsWith("Invalid") ||
                    message.startsWith("Timed out") || message.contains("error", true) ||
                    message.contains("no longer active", true))) reportError(message)
                previous = message
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopGateway("Gateway stopped")
        } else {
            // A startForegroundService request must be promoted immediately.
            try {
                promoteToForeground()
                startGateway()
            } catch (error: Exception) {
                reportError("Could not start gateway: ${error.message ?: error.javaClass.simpleName}")
                stopGateway("Gateway start failed")
            }
        }
        return START_NOT_STICKY
    }

    private fun startGateway() {
        if (_state.value.running || _state.value.changing) return
        _state.update { it.copy(changing = true, message = "Starting gateway…", error = null) }
        serviceScope.launch {
            transition.withLock {
                try {
                    httpServer = withContext(Dispatchers.IO) {
                        LocalHttpStreamServer(SERVER_PORT, engine).also { it.start(5000, false) }
                    }
                    lastActivity = SystemClock.elapsedRealtime()
                    hadTransfer = false
                    _state.update { it.copy(running = true, changing = false, message = "Gateway ready (auto-stop after 30 seconds idle)") }
                    monitorJob = serviceScope.launch { monitorGateway() }
                } catch (error: Exception) {
                    _state.update { it.copy(running = false, changing = false, message = "Gateway start failed") }
                    reportError("Could not listen on port $SERVER_PORT: ${error.message ?: error.javaClass.simpleName}")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    foregroundActive = false
                    stopSelf()
                }
            }
        }
    }

    fun markUserActivity() { lastActivity = SystemClock.elapsedRealtime() }

    fun trackDownload(id: Long, name: String) {
        markUserActivity()
        hadTransfer = true
        _state.update { it.copy(downloads = it.downloads + GatewayDownload(id, name)) }
    }

    fun reportError(message: String) {
        _state.update { it.copy(error = message, errorSerial = it.errorSerial + 1) }
    }

    fun resetSession() { stopGateway("Gateway reset; press Start Gateway to load another torrent") }

    fun stopGateway(message: String = "Gateway stopped") {
        if (_state.value.changing) {
            // Serialize stop behind an in-flight start rather than leaving it running.
            serviceScope.launch { transition.withLock { stopGatewayLocked(message) } }
            return
        }
        _state.update { it.copy(changing = true) }
        serviceScope.launch { transition.withLock { stopGatewayLocked(message) } }
    }

    private suspend fun stopGatewayLocked(message: String) {
        monitorJob?.cancel()
        monitorJob = null
        val pending = _state.value.downloads.filterNot { it.finished }
        _state.update { it.copy(running = false, changing = true, downloads = it.downloads.map { download ->
            if (download.finished) download else download.copy(finished = true, failed = true, message = "Cancelled: gateway stopped")
        }) }
        val server = httpServer
        httpServer = null
        withContext(Dispatchers.IO) {
            if (pending.isNotEmpty()) runCatching { downloads.remove(*pending.map { it.id }.toLongArray()) }
            server?.stop()
            engine.stopSession()
        }
        _state.update { it.copy(changing = false, message = message) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundActive = false
        stopSelf() // Bound UI remains available for another explicit Start.
    }

    private suspend fun monitorGateway() {
        while (currentCoroutineContext().isActive && _state.value.running) {
            delay(1000L)
            val now = SystemClock.elapsedRealtime()
            val records = _state.value.downloads.filterNot { it.finished }
            for (record in records) {
                try {
                    var updated = withContext(Dispatchers.IO) { queryDownload(record, now) }
                    if (!updated.finished && now - updated.lastProgressAt >= GatewayIdlePolicy.STALL_MS) {
                        withContext(Dispatchers.IO) { downloads.remove(record.id) }
                        updated = updated.copy(finished = true, failed = true, message = "No download progress for 3 minutes. Check torrent peers/network and retry.")
                    }
                    _state.update { it.copy(downloads = it.downloads.map { old -> if (old.id == updated.id) updated else old }) }
                    if (updated.finished && updated.failed) reportError("${updated.name}: ${updated.message}")
                } catch (error: CancellationException) { throw error
                } catch (error: Exception) {
                    reportError("Could not read Android download status: ${error.message ?: error.javaClass.simpleName}")
                    stopGateway("Gateway stopped after download-status error")
                    return
                }
            }
            val activeDownloads = _state.value.downloads.any { !it.finished }
            val server = httpServer
            val activeStreams = server?.activeStreams ?: 0
            if (activeStreams > 0) lastActivity = now
            val loading = engine.status.value.stateText.let { it.startsWith("Loading") || it.startsWith("Fetching") }
            if (!activeDownloads && activeStreams == 0 && hadTransfer) {
                stopGateway("Transfers finished; gateway stopped automatically")
                return
            }
            if (GatewayIdlePolicy.shouldStop(now - lastActivity, loading, activeDownloads, activeStreams)) {
                if (loading) reportError("Torrent metadata timed out; check the magnet and available peers")
                stopGateway("No active downloads; gateway stopped automatically")
                return
            }
            updateNotification()
        }
    }

    private fun queryDownload(old: GatewayDownload, now: Long): GatewayDownload {
        downloads.query(DownloadManager.Query().setFilterById(old.id))?.use { cursor ->
            if (!cursor.moveToFirst()) return old.copy(finished = true, failed = true, message = "Download removed or unavailable")
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val message = when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> "Completed — saved in Downloads"
                DownloadManager.STATUS_FAILED -> downloadFailure(reason)
                DownloadManager.STATUS_PAUSED -> when (reason) {
                    DownloadManager.PAUSED_WAITING_TO_RETRY -> "Waiting to retry — torrent data may not be ready"
                    DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for network"
                    DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi"
                    else -> "Paused (reason $reason)"
                }
                DownloadManager.STATUS_RUNNING -> "Downloading"
                else -> "Queued"
            }
            return old.copy(bytes = bytes, total = total, message = message,
                finished = status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED,
                failed = status == DownloadManager.STATUS_FAILED,
                lastProgressAt = if (bytes > old.bytes) now else old.lastProgressAt,
                sampledAt = now,
                bytesPerSecond = if (status == DownloadManager.STATUS_RUNNING && now > old.sampledAt && bytes >= old.bytes) {
                    ((bytes - old.bytes).toDouble() * 1000.0 / (now - old.sampledAt)).toLong()
                } else 0L)
        }
        return old.copy(finished = true, failed = true, message = "Android download service unavailable")
    }

    private fun downloadFailure(reason: Int): String = when (reason) {
        DownloadManager.ERROR_CANNOT_RESUME -> "Cannot resume interrupted transfer; retry the download"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "File already exists in Downloads; rename or remove it before retrying"
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage space"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Download storage is unavailable"
        DownloadManager.ERROR_FILE_ERROR -> "Could not write to Downloads"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "Gateway connection interrupted or torrent data stalled"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Gateway returned an unsupported response"
        in 400..599 -> "Gateway HTTP error $reason; reload the torrent and retry"
        else -> "Download failed (Android reason $reason)"
    }

    private fun promoteToForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, notification)
        foregroundActive = true
    }

    private fun updateNotification() {
        if (!foregroundActive) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 0, Intent(this, TorrentGatewayService::class.java).setAction(ACTION_STOP_SERVICE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val active = _state.value.downloads.filterNot { it.finished }
        val total = active.sumOf { it.total.coerceAtLeast(0) }
        val bytes = active.sumOf { it.bytes }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TorrentToDDL gateway running")
            .setContentText(if (active.isNotEmpty()) "${active.size} download(s) • ${bytes / 1024} KB received" else "Ready on port $SERVER_PORT • auto-stop when idle")
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentIntent(open)
            .setProgress(100, if (total > 0) (bytes.toDouble() * 100 / total).toInt().coerceIn(0,100) else 0, active.isNotEmpty() && total <= 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Gateway", stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setOnlyAlertOnce(true).setOngoing(true).build()
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        val pending = _state.value.downloads.filterNot { it.finished }.map { it.id }.toLongArray()
        val server = httpServer
        httpServer = null
        serviceScope.launch(Dispatchers.IO) {
            try {
                if (pending.isNotEmpty()) runCatching { downloads.remove(*pending) }
                server?.stop()
                engine.stopSession()
            } finally { serviceScope.cancel() }
        }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "torrent_ddl_channel"
        const val NOTIFICATION_ID = 1001
        const val SERVER_PORT = 8080
        const val EXTRA_MAGNET_URI = "extra_magnet_uri"
        const val ACTION_STOP_SERVICE = "com.example.torrenttoddl.STOP_SERVICE"
        const val ACTION_RESET_SERVICE = "com.example.torrenttoddl.RESET_SERVICE"
    }
}
