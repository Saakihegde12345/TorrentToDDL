package com.example.torrenttoddl.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.torrenttoddl.engine.TorrentStreamEngine
import com.example.torrenttoddl.server.LocalHttpStreamServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

class TorrentGatewayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var engine: TorrentStreamEngine
        private set
    private var httpServer: LocalHttpStreamServer? = null
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): TorrentGatewayService = this@TorrentGatewayService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Gateway Ready on port $SERVER_PORT"))

        val cacheDir = File(getExternalFilesDir(null) ?: filesDir, "torrent_cache").apply { mkdirs() }
        engine = TorrentStreamEngine(cacheDir, serviceScope)
        engine.startSession()

        httpServer = LocalHttpStreamServer(SERVER_PORT, engine).apply {
            start(5000, false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val magnet = intent?.getStringExtra(EXTRA_MAGNET_URI)
        if (!magnet.isNullOrBlank()) {
            engine.resolveMagnetOrTorrent(magnet)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        httpServer?.stop()
        engine.stopSession()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Torrent DDL Gateway",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
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
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Gateway", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "torrent_ddl_channel"
        const val NOTIFICATION_ID = 1001
        const val SERVER_PORT = 8080
        const val EXTRA_MAGNET_URI = "extra_magnet_uri"
        const val ACTION_STOP_SERVICE = "com.example.torrenttoddl.STOP_SERVICE"
    }
}
