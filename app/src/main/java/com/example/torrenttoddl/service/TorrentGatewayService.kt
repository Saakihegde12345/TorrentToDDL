package com.example.torrenttoddl.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.torrenttoddl.MainActivity
import com.example.torrenttoddl.engine.TorrentStreamEngine
import com.example.torrenttoddl.server.LocalHttpStreamServer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TorrentGatewayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var engine: TorrentStreamEngine
        private set
    private var httpServer: LocalHttpStreamServer? = null
    private val binder = LocalBinder()
    private var foregroundActive = false
    private var lastMagnetRequest: String? = null
    private var stopping = false

    inner class LocalBinder : Binder() {
        fun getService(): TorrentGatewayService = this@TorrentGatewayService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        promoteToForeground()

        val cacheDir = File(getExternalFilesDir(null) ?: filesDir, "torrent_cache").apply { mkdirs() }
        engine = TorrentStreamEngine(cacheDir, serviceScope)

        httpServer = LocalHttpStreamServer(SERVER_PORT, engine).apply {
            start(5000, false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopGateway()
                return START_NOT_STICKY
            }

            ACTION_RESET_SERVICE -> {
                promoteToForeground()
                resetSession()
                return START_NOT_STICKY
            }
        }

        if (!foregroundActive) promoteToForeground()

        val magnet = intent?.getStringExtra(EXTRA_MAGNET_URI)?.trim()
        if (!magnet.isNullOrBlank() && magnet != lastMagnetRequest) {
            lastMagnetRequest = magnet
            engine.resolveMagnetOrTorrent(magnet)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun resetSession() {
        if (!::engine.isInitialized) return
        promoteToForeground()
        lastMagnetRequest = null
        serviceScope.launch(Dispatchers.IO) {
            engine.resetSession()
        }
    }

    private fun stopGateway() {
        if (stopping) return
        stopping = true
        val serverToStop = httpServer
        httpServer = null
        serviceScope.launch(Dispatchers.IO) {
            if (::engine.isInitialized) engine.stopSession()
            serverToStop?.stop()
            withContext(Dispatchers.Main) {
                foregroundActive = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        val serverToStop = httpServer
        httpServer = null
        // Native teardown joins its alert thread; never block Android's main
        // thread, and keep this scope alive until cleanup has completed.
        serviceScope.launch(Dispatchers.IO) {
            try {
                if (::engine.isInitialized) engine.stopSession()
                serverToStop?.stop()
            } finally {
                serviceScope.cancel()
            }
        }
        foregroundActive = false
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Torrent DDL Gateway",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun promoteToForeground() {
        if (foregroundActive) return
        val notification = buildNotification("Gateway Ready on port $SERVER_PORT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundActive = true
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, TorrentGatewayService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Torrent-to-DDL Local Gateway")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Gateway", stopPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
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
