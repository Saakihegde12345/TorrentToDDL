package com.example.torrenttoddl

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.torrenttoddl.engine.EngineStatus
import com.example.torrenttoddl.engine.TorrentFileEntry
import com.example.torrenttoddl.server.LocalHttpStreamServer
import com.example.torrenttoddl.service.TorrentGatewayService
import java.net.URLEncoder

class MainActivity : ComponentActivity() {

    private var gatewayService by mutableStateOf<TorrentGatewayService?>(null)
    private var initialMagnetFromIntent by mutableStateOf("")

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TorrentGatewayService.LocalBinder
            gatewayService = binder.getService()
            if (initialMagnetFromIntent.isNotBlank()) {
                gatewayService?.engine?.resolveMagnetOrTorrent(initialMagnetFromIntent)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            gatewayService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == Intent.ACTION_VIEW && intent.data?.scheme == "magnet") {
            initialMagnetFromIntent = intent.data.toString()
        }

        val serviceIntent = Intent(this, TorrentGatewayService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val service = gatewayService
                    val torrentPicker = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument()
                    ) { uri: Uri? ->
                        if (uri != null) {
                            contentResolver.openInputStream(uri)?.use { stream ->
                                service?.engine?.loadTorrentBytes(stream.readBytes())
                            }
                        }
                    }

                    if (service != null) {
                        val status by service.engine.status.collectAsState()
                        TorrentConverterScreen(
                            initialMagnet = initialMagnetFromIntent,
                            status = status,
                            onConvertMagnet = { magnet ->
                                service.engine.resolveMagnetOrTorrent(magnet)
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
                            }
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                            Text("Starting Local Gateway Service...")
                        }
                    }
                }
            }
        }
    }

    private fun copyToClipboard(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Direct Download Link", url))
        Toast.makeText(this, "DDL copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun enqueueSystemDownload(url: String, fileName: String) {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setDescription("Downloading via Local Torrent-to-DDL Gateway")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        Toast.makeText(this, "Enqueued in Android DownloadManager", Toast.LENGTH_SHORT).show()
    }

    private fun openInExternalApp(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(Intent.createChooser(intent, "Open Direct Link With"))
    }

    override fun onDestroy() {
        unbindService(connection)
        super.onDestroy()
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
    onOpenExternalApp: (String) -> Unit
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { onConvertMagnet(magnetInput.trim()) },
                enabled = magnetInput.startsWith("magnet:"),
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
                            "%.1f".format(status.progressPercent)
                        }%"
                    )
                }
            }
        }

        if (status.isReadyForDdl) {
            Text(
                text = "Generated Direct Links (${status.files.size} files):",
                style = MaterialTheme.typography.titleMedium
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(status.files) { fileEntry ->
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
}

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
    val sizeMb = "%.2f MB".format(fileEntry.sizeBytes / (1024.0 * 1024.0))

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = "${fileEntry.name} ($sizeMb)", style = MaterialTheme.typography.titleSmall)
            Text(text = "Local DDL: $localDdl", style = MaterialTheme.typography.bodySmall)
            Text(text = "Wi-Fi/Hotspot DDL: $lanDdl", style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onCopyLink(localDdl) }) {
                    Text("Copy Link")
                }
                Button(onClick = { onDownloadWithSystemManager(localDdl, fileEntry.name) }) {
                    Text("Direct Download")
                }
                OutlinedButton(onClick = { onOpenExternalApp(localDdl) }) {
                    Text("Stream / 1DM")
                }
            }
        }
    }
}
