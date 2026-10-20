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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.core.content.ContextCompat
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
            submitPendingInputIfPossible()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            gatewayService = null
            inFlightInputKey = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lastHandledIntentKey = savedInstanceState?.getString(STATE_LAST_INTENT_KEY)
        initialMagnetFromIntent = savedInstanceState?.getString(STATE_MAGNET).orEmpty()
        pendingInput = savedInstanceState?.let(::restorePendingInput)
        handleIncomingIntent(intent)

        startAndBindGatewayService()

        setContent {
            MaterialTheme {
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
                        TorrentConverterScreen(
                            initialMagnet = initialMagnetFromIntent,
                            status = status,
                            incomingError = incomingError,
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
        }.onSuccess {
            Toast.makeText(this, "Enqueued in Android DownloadManager", Toast.LENGTH_SHORT).show()
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
            ContextCompat.startForegroundService(this, serviceIntent)
            serviceBound = bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
            if (!serviceBound) {
                incomingError = "Unable to connect to the local gateway service"
            }
        } catch (error: Exception) {
            incomingError = "Unable to start the local gateway service: ${errorText(error)}"
        }
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

    override fun onDestroy() {
        pendingTorrentReadJob?.cancel()
        if (serviceBound) {
            runCatching { unbindService(connection) }
            serviceBound = false
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
    onReset: () -> Unit = {}
) {
    var magnetInput by remember(initialMagnet) { mutableStateOf(initialMagnet) }
    val lanIp = remember { LocalHttpStreamServer.getLocalLanIpAddress() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Torrent to Direct Download",
            style = MaterialTheme.typography.headlineSmall
        )

        OutlinedTextField(
            value = magnetInput,
            onValueChange = { magnetInput = it },
            label = { Text("Paste Magnet URI (magnet:?xt=urn:btih:...)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = { onConvertMagnet(magnetInput.trim()) },
                enabled = magnetInput.startsWith("magnet:", ignoreCase = true),
                modifier = Modifier.weight(1f)
            ) {
                Text("Convert Magnet")
            }

            OutlinedButton(
                onClick = onPickTorrentFile,
                modifier = Modifier.weight(1f)
            ) {
                Text("Pick .torrent File")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Status: ${status.stateText}", style = MaterialTheme.typography.bodyMedium)
                if (status.torrentName.isNotBlank()) {
                    Text("Torrent: ${status.torrentName}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Speed: ${status.downloadSpeedKbps} KB/s | Peers: ${status.peers} | Cached: ${
                            "%.1f".format(Locale.US, status.progressPercent)
                        }%"
                    )
                }
            }
        }

        incomingError?.let { error ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Could not open the incoming torrent: $error",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        when {
            status.isReadyForDdl && status.files.isEmpty() -> {
                Text("The torrent has no files to stream.")
                Spacer(modifier = Modifier.weight(1f))
            }

            status.isReadyForDdl -> {
                Text(
                    text = "Generated Direct Links (${status.files.size} files):",
                    style = MaterialTheme.typography.titleMedium
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = status.files,
                            key = { fileEntry -> "${fileEntry.index}:${fileEntry.relativePath}" }
                        ) { fileEntry ->
                            FileDdlCard(
                                fileEntry = fileEntry,
                                lanIp = lanIp,
                                onCopyLink = onCopyLink,
                                onDownloadWithSystemManager = onDownloadWithSystemManager,
                                onOpenExternalApp = onOpenExternalApp
                            )
                        }
                    }
                }
            }

            isLoadingState(status.stateText) -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(status.stateText)
                }
                Spacer(modifier = Modifier.weight(1f))
            }

            isErrorState(status.stateText) -> {
                Text(
                    text = status.stateText,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.weight(1f))
            }

            else -> {
                Text("Paste a magnet URI or choose a .torrent file to begin.")
                Spacer(modifier = Modifier.weight(1f))
            }
        }

        OutlinedButton(
            onClick = onReset,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reset Gateway")
        }
    }
}

private fun isLoadingState(stateText: String): Boolean =
    stateText.startsWith("Fetching", ignoreCase = true) ||
        stateText.startsWith("Loading", ignoreCase = true) ||
        stateText.startsWith("Starting", ignoreCase = true)

private fun isErrorState(stateText: String): Boolean =
    stateText.isNotBlank() &&
        !stateText.equals("Idle", ignoreCase = true) &&
        !isLoadingState(stateText)

@Composable
fun FileDdlCard(
    fileEntry: TorrentFileEntry,
    lanIp: String,
    onCopyLink: (String) -> Unit,
    onDownloadWithSystemManager: (String, String) -> Unit,
    onOpenExternalApp: (String) -> Unit
) {
    val encodedName = URLEncoder.encode(fileEntry.name, "UTF-8").replace("+", "%20")
    val localDdl = "http://127.0.0.1:${TorrentGatewayService.SERVER_PORT}/files/${fileEntry.index}/$encodedName"
    val lanDdl = "http://$lanIp:${TorrentGatewayService.SERVER_PORT}/files/${fileEntry.index}/$encodedName"
    val sizeMb = "%.2f MB".format(Locale.US, fileEntry.sizeBytes / (1024.0 * 1024.0))

    androidx.compose.material3.OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "${fileEntry.name} ($sizeMb)",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth(),
                softWrap = true,
                overflow = TextOverflow.Clip
            )

            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "On-device URL: $localDdl",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                        softWrap = true
                    )
                    Text(
                        text = "LAN URL: $lanDdl",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                        softWrap = true
                    )
                }
            }

            OutlinedButton(
                onClick = { onCopyLink(lanDdl) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Copy LAN Link")
            }
            Button(
                onClick = { onDownloadWithSystemManager(localDdl, fileEntry.name) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Download on This Device")
            }
            OutlinedButton(
                onClick = { onOpenExternalApp(localDdl) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open on This Device")
            }
        }
    }
}
