package com.example.torrenttoddl

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.core.view.WindowCompat
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.heightIn
import com.example.torrenttoddl.service.GatewayState
import androidx.lifecycle.lifecycleScope
import com.example.torrenttoddl.engine.EngineStatus
import com.example.torrenttoddl.engine.TorrentFileEntry
import com.example.torrenttoddl.server.LocalHttpStreamServer
import com.example.torrenttoddl.service.TorrentGatewayService
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var notificationsEnabled by mutableStateOf(true)
    private var gatewayService by mutableStateOf<TorrentGatewayService?>(null)
    private var initialMagnetFromIntent by mutableStateOf("")
    private var incomingError by mutableStateOf<String?>(null)

    private var pendingInput: IncomingInput? = null
    private var pendingTorrentReadJob: Job? = null
    private var lastHandledIntentKey: String? = null
    private var inFlightInputKey: String? = null
    private var serviceBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? TorrentGatewayService.LocalBinder ?: return
            serviceBound = true
            gatewayService = binder.getService()
            gatewayService?.setUiVisible(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
            submitPendingInputIfPossible()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            gatewayService = null
            inFlightInputKey = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(11, 15, 19)
        window.navigationBarColor = android.graphics.Color.rgb(11, 15, 19)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishAndRemoveTask()
            }
        })

        lastHandledIntentKey = savedInstanceState?.getString(STATE_LAST_INTENT_KEY)
        initialMagnetFromIntent = savedInstanceState?.getString(STATE_MAGNET).orEmpty()
        pendingInput = savedInstanceState?.let(::restorePendingInput)
        handleIncomingIntent(intent)

        startAndBindGatewayService()

        setContent {
            TorrentAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val service = gatewayService
                    val torrentPicker = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument()
                    ) { uri: Uri? ->
                        if (uri != null) {
                            retainUriPermission(uri)
                            queueTorrentUri(uri)
                        }
                    }

                    if (service != null) {
                        val status by service.engine.status.collectAsState()
                        val runtime by service.state.collectAsState()
                        LaunchedEffect(runtime.running) {
                            if (runtime.running) submitPendingInputIfPossible()
                        }
                        LaunchedEffect(runtime.errorSerial) {
                            runtime.error?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
                        }
                        LaunchedEffect(incomingError) {
                            incomingError?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
                        }
                        TorrentConverterScreen(
                            initialMagnet = initialMagnetFromIntent,
                            status = status,
                            incomingError = incomingError,
                            gateway = runtime,
                            notificationsEnabled = notificationsEnabled,
                            onStartGateway = { startGateway() },
                            onStopGateway = { service.stopGateway() },
                            onEnableNotifications = {
                                startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
                            },
                            onConvertMagnet = { magnet ->
                                submitMagnet(magnet)
                            },
                            onPickTorrentFile = {
                                torrentPicker.launch(arrayOf("application/x-bittorrent", "*/*"))
                            },
                            onCopyLink = { url -> copyToClipboard(url) },
                            onDownloadWithSystemManager = { url, fileName ->
                                enqueueSystemDownload(url, fileName)
                            },
                            onOpenExternalApp = { url ->
                                openInExternalApp(url)
                            },
                            onReset = {
                                resetGateway()
                            }
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator()
                                Text("Starting Local Gateway Service...")
                                if (pendingInput?.torrentUri != null) {
                                    Text("Waiting to open the selected .torrent file...")
                                }
                                incomingError?.let { error ->
                                    Text(
                                        text = error,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        requestNotificationPermissionIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_LAST_INTENT_KEY, lastHandledIntentKey)
        outState.putString(STATE_MAGNET, initialMagnetFromIntent)
        pendingInput?.let { input ->
            outState.putString(STATE_PENDING_KEY, input.key)
            input.torrentUri?.let { uri -> outState.putString(STATE_PENDING_URI, uri.toString()) }
            input.magnet?.let { magnet -> outState.putString(STATE_PENDING_MAGNET, magnet) }
        }
        super.onSaveInstanceState(outState)
    }

    private fun copyToClipboard(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Direct Download Link", url))
        Toast.makeText(this, "DDL copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun enqueueSystemDownload(url: String, fileName: String) {
        val service = gatewayService
        if (service == null || !service.state.value.running || service.state.value.changing) {
            Toast.makeText(this, "Start the gateway before downloading", Toast.LENGTH_LONG).show()
            return
        }
        service.markUserActivity()
        val safeFileName = fileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\r\\n\\\"/]"), "_")
            .ifBlank { "download.bin" }

        runCatching {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(safeFileName)
                .setDescription("Downloading via Local Torrent-to-DDL Gateway")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeFileName)

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
        }.onSuccess { id ->
            service.trackDownload(id, safeFileName)
            Toast.makeText(this, "Download queued; progress is shown in the app", Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            Toast.makeText(
                this,
                "Could not enqueue download: ${errorText(error)}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openInExternalApp(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(Intent.createChooser(intent, "Open Direct Link With"))
        }.onFailure { error ->
            Toast.makeText(
                this,
                "No app can open this link: ${errorText(error)}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun startAndBindGatewayService() {
        val serviceIntent = Intent(this, TorrentGatewayService::class.java)
        try {
            serviceBound = bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
            if (!serviceBound) {
                incomingError = "Unable to connect to the local gateway service"
            }
        } catch (error: Exception) {
            incomingError = "Unable to start the local gateway service: ${errorText(error)}"
        }
    }

    private fun startGateway() {
        requestNotificationPermissionIfNeeded()
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, TorrentGatewayService::class.java))
        }.onFailure {
            incomingError = "Unable to start gateway: ${errorText(it)}"
            Toast.makeText(this, incomingError, Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            notificationsEnabled = gatewayNotificationsEnabled()
            if (!notificationsEnabled) Toast.makeText(this, "Notifications are disabled. Enable them in app settings to see gateway progress.", Toast.LENGTH_LONG).show()
        }
    }

    private fun gatewayNotificationsEnabled(): Boolean {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        val channel = manager.getNotificationChannel(TorrentGatewayService.CHANNEL_ID)
        return NotificationManagerCompat.from(this).areNotificationsEnabled() &&
            (channel == null || channel.importance != android.app.NotificationManager.IMPORTANCE_NONE)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun handleIncomingIntent(incomingIntent: Intent?) {
        val incoming = incomingIntent?.toIncomingInput() ?: return
        if (incoming.key == lastHandledIntentKey) return

        pendingTorrentReadJob?.cancel()
        pendingTorrentReadJob = null
        inFlightInputKey = null
        lastHandledIntentKey = incoming.key
        pendingInput = incoming
        incomingError = null

        if (incoming.magnet != null) {
            initialMagnetFromIntent = incoming.magnet
        } else {
            initialMagnetFromIntent = ""
            incoming.torrentUri?.let { retainUriPermission(it, incomingIntent.flags) }
        }
        submitPendingInputIfPossible()
    }

    private fun queueTorrentUri(uri: Uri) {
        pendingTorrentReadJob?.cancel()
        pendingTorrentReadJob = null
        inFlightInputKey = null
        pendingInput = IncomingInput(
            key = "picker:$uri",
            torrentUri = uri
        )
        initialMagnetFromIntent = ""
        incomingError = null
        submitPendingInputIfPossible()
    }

    private fun submitMagnet(magnet: String) {
        val normalizedMagnet = magnet.trim()
        if (!normalizedMagnet.startsWith("magnet:", ignoreCase = true)) return

        pendingTorrentReadJob?.cancel()
        pendingTorrentReadJob = null
        inFlightInputKey = null
        incomingError = null
        initialMagnetFromIntent = normalizedMagnet
        pendingInput = IncomingInput(
            key = "manual:$normalizedMagnet",
            magnet = normalizedMagnet
        )
        submitPendingInputIfPossible()
    }

    private fun submitPendingInputIfPossible() {
        val service = gatewayService ?: return
        val input = pendingInput ?: return
        if (input.key == inFlightInputKey) return
        if (!service.state.value.running || service.state.value.changing) {
            if (!service.state.value.changing) startGateway()
            return
        }
        if (service.state.value.downloads.any { !it.finished }) {
            incomingError = "Stop current downloads before loading another torrent"
            pendingInput = null
            return
        }
        service.markUserActivity()

        if (input.magnet != null) {
            inFlightInputKey = input.key
            pendingInput = null
            service.engine.resolveMagnetOrTorrent(input.magnet)
            inFlightInputKey = null
            return
        }

        val uri = input.torrentUri ?: return
        inFlightInputKey = input.key
        pendingTorrentReadJob = lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { readTorrentBytes(uri) }
            withContext(Dispatchers.Main.immediate) {
                pendingTorrentReadJob = null
                if (gatewayService !== service || pendingInput?.key != input.key) {
                    inFlightInputKey = null
                    return@withContext
                }

                result.onSuccess { bytes ->
                    pendingInput = null
                    inFlightInputKey = null
                    incomingError = null
                    service.engine.loadTorrentBytes(bytes)
                }.onFailure { error ->
                    inFlightInputKey = null
                    incomingError = "Unable to open torrent file: ${errorText(error)}"
                }
            }
        }
    }

    private fun readTorrentBytes(uri: Uri): ByteArray {
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("The selected URI could not be opened")

        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var totalBytes = 0L
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                totalBytes += count
                if (totalBytes > MAX_TORRENT_BYTES) {
                    throw IOException("Torrent metadata is larger than 32 MB")
                }
                output.write(buffer, 0, count)
            }
            if (totalBytes == 0L) throw IOException("The selected torrent file is empty")
            output.toByteArray()
        }
    }

    private fun retainUriPermission(uri: Uri, flags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun resetGateway() {
        pendingTorrentReadJob?.cancel()
        pendingTorrentReadJob = null
        inFlightInputKey = null
        pendingInput = null
        incomingError = null
        initialMagnetFromIntent = ""
        lastHandledIntentKey = null
        gatewayService?.resetSession()
    }

    override fun onStart() {
        super.onStart()
        notificationsEnabled = gatewayNotificationsEnabled()
        gatewayService?.setUiVisible(true)
    }

    override fun onStop() {
        gatewayService?.setUiVisible(false)
        super.onStop()
    }

    override fun onDestroy() {
        pendingTorrentReadJob?.cancel()
        if (serviceBound) {
            runCatching { unbindService(connection) }
            serviceBound = false
        }
        if (isFinishing && !isChangingConfigurations) {
            stopService(Intent(this, TorrentGatewayService::class.java))
        }
        super.onDestroy()
    }

    private fun Intent.toIncomingInput(): IncomingInput? {
        if (action != Intent.ACTION_VIEW) return null
        val uri = data ?: clipData
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.uri
            ?: return null
        val key = listOf(
            action,
            type?.lowercase(Locale.ROOT).orEmpty(),
            uri.toString()
        ).joinToString("|")

        if (uri.scheme.equals("magnet", ignoreCase = true)) {
            return IncomingInput(key = key, magnet = uri.toString())
        }

        val mimeType = type?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        val looksLikeTorrent = mimeType == TORRENT_MIME_TYPE ||
            mimeType == "application/x-torrent" ||
            (mimeType == null && uri.lastPathSegment?.endsWith(".torrent", ignoreCase = true) == true)
        return if (looksLikeTorrent) {
            IncomingInput(key = key, torrentUri = uri)
        } else {
            null
        }
    }

    private fun restorePendingInput(state: Bundle): IncomingInput? {
        val key = state.getString(STATE_PENDING_KEY) ?: return null
        val magnet = state.getString(STATE_PENDING_MAGNET)
        val uri = state.getString(STATE_PENDING_URI)?.let(Uri::parse)
        return if (magnet != null) {
            IncomingInput(key = key, magnet = magnet)
        } else if (uri != null) {
            IncomingInput(key = key, torrentUri = uri)
        } else {
            null
        }
    }

    private fun errorText(error: Throwable): String =
        error.localizedMessage?.trim()?.takeIf { it.isNotEmpty() } ?: error.javaClass.simpleName

    private data class IncomingInput(
        val key: String,
        val magnet: String? = null,
        val torrentUri: Uri? = null
    )

    companion object {
        private const val TORRENT_MIME_TYPE = "application/x-bittorrent"
        private const val MAX_TORRENT_BYTES = 32L * 1024L * 1024L
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 4101
        private const val STATE_LAST_INTENT_KEY = "last_intent_key"
        private const val STATE_MAGNET = "initial_magnet"
        private const val STATE_PENDING_KEY = "pending_input_key"
        private const val STATE_PENDING_URI = "pending_torrent_uri"
        private const val STATE_PENDING_MAGNET = "pending_magnet"
    }
}

private val Ink = androidx.compose.ui.graphics.Color(0xFF0B0F13)
private val Panel = androidx.compose.ui.graphics.Color(0xFF141B22)
private val Edge = androidx.compose.ui.graphics.Color(0xFF2B3540)
private val Lime = androidx.compose.ui.graphics.Color(0xFFCEFA67)
private val Ice = androidx.compose.ui.graphics.Color(0xFF83DBE8)
private val Paper = androidx.compose.ui.graphics.Color(0xFFF1F5EE)
private val Muted = androidx.compose.ui.graphics.Color(0xFF9AA8B5)

@Composable
private fun TorrentAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = Lime, onPrimary = Ink, secondary = Ice, onSecondary = Ink,
            background = Ink, onBackground = Paper, surface = Panel, onSurface = Paper,
            surfaceVariant = Panel, onSurfaceVariant = Muted, outline = Edge,
            error = androidx.compose.ui.graphics.Color(0xFFFF9A9A)
        ),
        content = content
    )
}

@Composable
private fun Eyebrow(text: String, color: androidx.compose.ui.graphics.Color = Muted) {
    Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
}

@Composable
private fun StatusPill(text: String, active: Boolean = false) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (active) Lime.copy(alpha = .12f) else Edge.copy(alpha = .5f))
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(if (active) Lime else Muted))
        Text(text, color = if (active) Lime else Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.material3.OutlinedCard(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = androidx.compose.material3.CardDefaults.outlinedCardColors(containerColor = Panel),
        border = androidx.compose.foundation.BorderStroke(1.dp, Edge)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

private fun sizeLabel(bytes: Long): String {
    if (bytes < 0) return "Size pending"
    val value = bytes.toDouble()
    return when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GiB".format(Locale.US, value / (1024 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.1f MiB".format(Locale.US, value / (1024 * 1024))
        bytes >= 1024 -> "%.1f KiB".format(Locale.US, value / 1024)
        else -> "$bytes B"
    }
}

@Composable
fun TorrentConverterScreen(
    initialMagnet: String,
    status: EngineStatus,
    onConvertMagnet: (String) -> Unit,
    onPickTorrentFile: () -> Unit,
    onCopyLink: (String) -> Unit,
    onDownloadWithSystemManager: (String, String) -> Unit,
    onOpenExternalApp: (String) -> Unit,
    incomingError: String? = null,
    onReset: () -> Unit = {},
    gateway: GatewayState = GatewayState(),
    notificationsEnabled: Boolean = true,
    onStartGateway: () -> Unit = {},
    onStopGateway: () -> Unit = {},
    onEnableNotifications: () -> Unit = {}
) {
    var magnetInput by remember(initialMagnet) { mutableStateOf(initialMagnet) }
    val lanIp = remember { LocalHttpStreamServer.getLocalLanIpAddress() }
    val canLoad = gateway.running && !gateway.changing && gateway.downloads.none { !it.finished }
    val cacheProgress = (status.progressPercent / 100f).coerceIn(0f, 1f)
    val activeSaves = gateway.downloads.count { !it.finished }

    LazyColumn(
        modifier = Modifier.fillMaxSize()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Ink, androidx.compose.ui.graphics.Color(0xFF10191E))))
            .statusBarsPadding().navigationBarsPadding().imePadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item(key = "brand") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Ink)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("TorrentToDDL", color = Paper, fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = (-.5).sp)
                        Eyebrow("THE ON-DEVICE GATEWAY")
                    }
                }
                StatusPill(if (gateway.changing) "BUSY" else if (gateway.running) "LIVE" else "OFFLINE", gateway.running)
            }
        }

        item(key = "gateway") {
            SectionCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("01 / GATEWAY", Lime)
                    Text("AUTO OFF · BACKGROUND", color = Muted, fontSize = 9.sp, letterSpacing = .7.sp)
                }
                Text(
                    if (gateway.changing) "Switching gears." else if (gateway.running) "Ready to move." else "Your device.\nYour gateway.",
                    color = Paper, fontSize = 33.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.3).sp
                )
                Text(gateway.message, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onStartGateway, enabled = !gateway.running && !gateway.changing,
                        modifier = Modifier.weight(1f).heightIn(min = 50.dp), shape = RoundedCornerShape(16.dp)) { Text("Start Gateway", fontWeight = FontWeight.Bold) }
                    OutlinedButton(onClick = onStopGateway, enabled = gateway.running && !gateway.changing,
                        modifier = Modifier.weight(1f).heightIn(min = 50.dp), shape = RoundedCornerShape(16.dp)) { Text("Stop Gateway") }
                }
            }
        }

        if (!notificationsEnabled) item(key = "notification") {
            SectionCard {
                Eyebrow("NOTIFICATIONS ARE OFF", Ice)
                Text("Allow notifications to see your gateway and transfers in the notification bar.", color = Muted, fontSize = 12.sp)
                OutlinedButton(onClick = onEnableNotifications, shape = RoundedCornerShape(14.dp)) { Text("Enable notifications") }
            }
        }

        val visibleError = incomingError ?: gateway.error
        if (visibleError != null) item(key = "error") {
            androidx.compose.material3.OutlinedCard(
                Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = .5f)),
                colors = androidx.compose.material3.CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = .07f))
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Eyebrow("NEEDS YOUR ATTENTION", MaterialTheme.colorScheme.error)
                    Text(visibleError, fontSize = 13.sp, color = Paper, lineHeight = 19.sp)
                }
            }
        }

        item(key = "input") {
            SectionCard {
                Eyebrow("02 / ADD A TORRENT")
                OutlinedTextField(
                    value = magnetInput, onValueChange = { magnetInput = it }, singleLine = true,
                    label = { Text("Magnet link", fontSize = 12.sp) },
                    placeholder = { Text("magnet:?xt=urn:btih:…", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Paper)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onConvertMagnet(magnetInput.trim()) },
                        enabled = canLoad && magnetInput.startsWith("magnet:", true),
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Resolve magnet", fontSize = 12.sp) }
                    OutlinedButton(onClick = onPickTorrentFile, enabled = canLoad,
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Browse .torrent", fontSize = 12.sp) }
                }
                if (!gateway.running) Text("Start your gateway to add a torrent.", color = Muted, fontSize = 11.sp)
                else if (activeSaves > 0) Text("Stop active transfers before changing the torrent.", color = Muted, fontSize = 11.sp)
            }
        }

        if (status.torrentName.isNotBlank() || isLoadingState(status.stateText)) item(key = "cache") {
            SectionCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("03 / TORRENT CACHE", Ice)
                    Text("%.1f%%".format(Locale.US, status.progressPercent), color = Ice, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                }
                Text(status.torrentName.ifBlank { "Finding your torrent…" }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (isLoadingState(status.stateText)) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ice)
                else LinearProgressIndicator(progress = cacheProgress, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), color = Ice, trackColor = Edge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Eyebrow("FROM PEERS")
                        Text("${sizeLabel(status.downloadSpeedKbps.toLong() * 1024)}/s", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Eyebrow("CONNECTED")
                        Text("${status.peers} peers", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    }
                }
                Text("${sizeLabel(status.cachedBytes)} / ${sizeLabel(status.totalBytes)} · ${status.stateText}", color = Muted, fontSize = 11.sp)
                Text("Internet → torrent cache. This can include data cached earlier.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }

        if (gateway.downloads.isNotEmpty()) item(key = "save-label") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Eyebrow("04 / SAVING TO DOWNLOADS", Lime)
                Text(if (activeSaves > 0) "$activeSaves active" else "Transfer history", color = Muted, fontSize = 11.sp)
            }
        }
        items(gateway.downloads.asReversed(), key = { "save:${it.id}" }) { download ->
            SaveProgressCard(download)
        }
        if (gateway.downloads.isNotEmpty()) item(key = "background-note") {
            Text("Use Home to keep transfers running. Back, Stop or Reset ends active transfers.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
        }

        if (status.isReadyForDdl) {
            item(key = "files-label") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Eyebrow("FILES / DIRECT LINKS")
                    Text("${status.files.size} available", color = Muted, fontSize = 11.sp)
                }
            }
            items(status.files, key = { "file:${status.infoHash}:${it.index}" }) { fileEntry ->
                FileDdlCard(fileEntry, lanIp, onCopyLink, onDownloadWithSystemManager, onOpenExternalApp)
            }
            if (status.files.isEmpty()) item(key = "empty-files") { Text("This torrent contains no files.", color = Muted) }
        }
        item(key = "footer") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Eyebrow("ON DEVICE. IN YOUR CONTROL.")
                    Text("TorrentToDDL / gateway", color = Muted, fontSize = 10.sp)
                }
                androidx.compose.material3.TextButton(onClick = onReset) { Text("Reset", color = Muted, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun SaveProgressCard(download: com.example.torrenttoddl.service.GatewayDownload) {
    val knownTotal = download.total > 0
    val progress = if (knownTotal) (download.bytes.toDouble() / download.total).toFloat().coerceIn(0f, 1f) else 0f
    val accent = if (download.failed) MaterialTheme.colorScheme.error else Lime
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Eyebrow("LOCAL SAVE", accent)
                Text(download.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(if (knownTotal) "%.1f%%".format(Locale.US, progress * 100) else "—", color = accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        if (knownTotal) LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), color = accent, trackColor = Edge)
        else if (!download.finished) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = accent, trackColor = Edge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${sizeLabel(download.bytes)} / ${sizeLabel(download.total)}", color = Muted, fontSize = 11.sp)
            Text("${sizeLabel(download.bytesPerSecond)}/s", color = accent, fontSize = 11.sp)
        }
        Text(download.message, color = if (download.failed) accent else Paper, fontSize = 12.sp, lineHeight = 18.sp)
        Text("Torrent cache → Downloads. Cached files can save much faster than your internet speed.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
    }
}

private fun isLoadingState(stateText: String): Boolean =
    stateText.startsWith("Fetching", true) || stateText.startsWith("Loading", true) || stateText.startsWith("Starting", true)

@Composable
fun FileDdlCard(
    fileEntry: TorrentFileEntry,
    lanIp: String,
    onCopyLink: (String) -> Unit,
    onDownloadWithSystemManager: (String, String) -> Unit,
    onOpenExternalApp: (String) -> Unit
) {
    var showLinks by remember(fileEntry.relativePath) { mutableStateOf(false) }
    val encodedName = URLEncoder.encode(fileEntry.name, "UTF-8").replace("+", "%20")
    val localDdl = "http://127.0.0.1:${TorrentGatewayService.SERVER_PORT}/files/${fileEntry.index}/$encodedName"
    val lanDdl = "http://$lanIp:${TorrentGatewayService.SERVER_PORT}/files/${fileEntry.index}/$encodedName"
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Ice.copy(alpha = .09f)), contentAlignment = Alignment.Center) {
                Text(fileEntry.name.substringAfterLast('.', "FILE").take(4).uppercase(Locale.US), color = Ice, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(fileEntry.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(sizeLabel(fileEntry.sizeBytes), color = Muted, fontSize = 12.sp)
            }
        }
        Button(onClick = { onDownloadWithSystemManager(localDdl, fileEntry.name) }, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp), shape = RoundedCornerShape(14.dp)) {
            Text("Save to device", fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onCopyLink(lanDdl) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Copy LAN link", fontSize = 11.sp) }
            OutlinedButton(onClick = { onOpenExternalApp(localDdl) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Open file", fontSize = 11.sp) }
        }
        androidx.compose.material3.TextButton(onClick = { showLinks = !showLinks }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showLinks) "Hide direct links −" else "Show direct links +", color = Muted, fontSize = 11.sp)
        }
        if (showLinks) SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("ON THIS DEVICE")
                Text(localDdl, color = Ice, fontSize = 11.sp, lineHeight = 16.sp)
                Eyebrow("LOCAL NETWORK")
                Text(lanDdl, color = Ice, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}
