package com.example.torrenttoddl.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import com.example.torrenttoddl.engine.TorrentStreamEngine
import com.example.torrenttoddl.server.LocalHttpStreamServer
import org.libtorrent4j.SessionManager

private val Graphite = Color(0xFF0B0F13)
private val Panel = Color(0xFF151D23)
private val Lime = Color(0xFFCEFA67)
private val Cyan = Color(0xFF83DBE8)
private val Ink = Color(0xFFF1F5EE)

@OptIn(ExperimentalMaterial3Api::class)
fun main(args: Array<String>) {
    if ("--smoke-test" in args) {
        smokeTest()
        return
    }
    application {
        val gateway = remember { DesktopGateway(cacheDirectory()) }
        val state = rememberWindowState(width = 1040.dp, height = 800.dp)
        val uiScope = rememberCoroutineScope()
        var closing by remember { mutableStateOf(false) }
        val view by gateway.view.collectAsState()
        val snackbar = remember { SnackbarHostState() }

        LaunchedEffect(Unit) {
            gateway.messages.collect { snackbar.showSnackbar(it) }
        }
        LaunchedEffect(state) {
            snapshotFlow { state.isMinimized }.collect {
                gateway.setVisible(!it)
            }
        }

        Window(
            title = "TorrentToDDL · Desktop Preview",
            state = state,
            icon = painterResource("torrenttoddl-logo.svg"),
            onCloseRequest = {
                if (!closing) {
                    closing = true
                    uiScope.launch {
                        try {
                            gateway.shutdown()
                        } finally {
                            exitApplication()
                        }
                    }
                }
            }
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(800, 620)
            }
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Lime,
                onPrimary = Graphite,
                secondary = Cyan,
                background = Graphite,
                surface = Panel,
                onBackground = Ink,
                onSurface = Ink
            )) {
                Scaffold(
                    containerColor = Graphite,
                    snackbarHost = { SnackbarHost(snackbar) }
                ) { inset ->
                    var magnet by remember { mutableStateOf("") }
                    val enabled = !closing && !view.busy

                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(inset),
                        contentPadding = PaddingValues(32.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Image(
                                    painterResource("torrenttoddl-logo.svg"),
                                    "TorrentToDDL",
                                    Modifier.size(72.dp)
                                )
                                Spacer(Modifier.width(18.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "YOUR DEVICE. YOUR GATEWAY.",
                                        color = Cyan,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    Text(
                                        "TorrentToDDL",
                                        style = MaterialTheme.typography.headlineLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "Desktop preview · Local by design",
                                        color = Ink.copy(alpha = .65f)
                                    )
                                }
                                Button(
                                    enabled = enabled,
                                    onClick = {
                                        if (view.running) gateway.stop()
                                        else gateway.start()
                                    }
                                ) {
                                    Text(
                                        if (closing) "Closing…"
                                        else if (view.running) "Stop Gateway"
                                        else "Start Gateway"
                                    )
                                }
                            }
                        }

                        item {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = Panel
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(22.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Text(
                                        if (view.running) "GATEWAY ONLINE"
                                        else "GATEWAY OFF",
                                        color = if (view.running) Lime else Cyan
                                    )
                                    Text(view.notice)
                                    OutlinedTextField(
                                        value = magnet,
                                        onValueChange = { magnet = it },
                                        label = { Text("Paste a magnet link") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = enabled
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Button(
                                            enabled = enabled && magnet.isNotBlank(),
                                            onClick = { gateway.openMagnet(magnet) }
                                        ) {
                                            Text("Resolve magnet")
                                        }
                                        OutlinedButton(
                                            enabled = enabled,
                                            onClick = {
                                                uiScope.launch {
                                                    try {
                                                        val file = withContext(Dispatchers.Swing) {
                                                            JFileChooser().apply {
                                                                fileFilter = FileNameExtensionFilter(
                                                                    "Torrent metadata", "torrent"
                                                                )
                                                            }.let {
                                                                if (it.showOpenDialog(window) ==
                                                                    JFileChooser.APPROVE_OPTION
                                                                ) it.selectedFile else null
                                                            }
                                                        }
                                                        file?.let { gateway.openTorrent(it) }
                                                    } catch (error: Exception) {
                                                        gateway.showError(error)
                                                    }
                                                }
                                            }
                                        ) {
                                            Text("Browse .torrent")
                                        }
                                    }
                                    if (view.running) {
                                        Text(view.lanAddress, color = Cyan)
                                    }
                                }
                            }
                        }

                        item {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = Panel
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(22.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        "TORRENT CACHE",
                                        color = Cyan,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    Text(
                                        view.engine.torrentName.ifBlank {
                                            "Waiting for a torrent"
                                        },
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                    Text(
                                        view.engine.stateText,
                                        color = Ink.copy(alpha = .7f)
                                    )
                                    LinearProgressIndicator(
                                        progress = view.engine.progressPercent / 100f,
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Cyan
                                    )
                                    Text(
                                        bytes(view.engine.cachedBytes) + " / " +
                                            bytes(view.engine.totalBytes) + " · " +
                                            bytes(view.engine.downloadSpeedKbps.toLong() * 1024) +
                                            "/s · " + view.engine.peers + " peers"
                                    )
                                }
                            }
                        }

                        if (view.saveName.isNotEmpty()) item {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = Panel
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(22.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        "SAVE TO DEVICE",
                                        color = Lime,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    Text(
                                        view.saveName,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    LinearProgressIndicator(
                                        progress = if (view.saveTotal > 0) {
                                            (view.savedBytes.toDouble() / view.saveTotal)
                                                .toFloat().coerceIn(0f, 1f)
                                        } else 0f,
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Lime
                                    )
                                    Text(
                                        bytes(view.savedBytes) + " / " +
                                            bytes(view.saveTotal) + " · " +
                                            bytes(view.saveRate) + "/s"
                                    )
                                    Text(
                                        view.saveMessage,
                                        color = Ink.copy(alpha = .7f)
                                    )
                                    if (view.saving) {
                                        OutlinedButton(
                                            enabled = enabled,
                                            onClick = { gateway.cancelSave() }
                                        ) {
                                            Text("Cancel save")
                                        }
                                    }
                                }
                            }
                        }

                        items(view.engine.files, key = { it.index }) { entry ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Panel
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        entry.name,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        bytes(entry.sizeBytes),
                                        color = Ink.copy(alpha = .6f)
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Button(
                                            enabled = enabled && view.running && !view.saving,
                                            onClick = {
                                                val expectedHash = view.engine.infoHash
                                                uiScope.launch {
                                                    try {
                                                        val destination = withContext(Dispatchers.Swing) {
                                                            JFileChooser().apply {
                                                                selectedFile = File(
                                                                    entry.name
                                                                        .substringAfterLast('/')
                                                                        .substringAfterLast('\\')
                                                                )
                                                            }.let {
                                                                if (it.showSaveDialog(window) ==
                                                                    JFileChooser.APPROVE_OPTION
                                                                ) it.selectedFile else null
                                                            }
                                                        }
                                                        destination?.let {
                                                            gateway.save(entry, it, expectedHash)
                                                        }
                                                    } catch (error: Exception) {
                                                        gateway.showError(error)
                                                    }
                                                }
                                            }
                                        ) {
                                            Text("Save to device")
                                        }

                                        OutlinedButton(
                                            enabled = enabled && view.running,
                                            onClick = {
                                                runCatching {
                                                    val link = gateway.lanLink(
                                                        entry, view.engine.infoHash
                                                    )
                                                    Toolkit.getDefaultToolkit()
                                                        .systemClipboard.setContents(
                                                            StringSelection(link), null
                                                        )
                                                    uiScope.launch {
                                                        snackbar.showSnackbar("LAN link copied")
                                                    }
                                                }.onFailure(gateway::showError)
                                            }
                                        ) {
                                            Text("Copy LAN link")
                                        }

                                        TextButton(
                                            enabled = enabled && view.running,
                                            onClick = {
                                                uiScope.launch(Dispatchers.IO) {
                                                    runCatching {
                                                        val link = gateway.lanLink(
                                                            entry, view.engine.infoHash
                                                        ).replace(
                                                            view.lanAddress,
                                                            "http://127.0.0.1:8080"
                                                        )
                                                        check(Desktop.isDesktopSupported()) {
                                                            "Opening a browser is unavailable."
                                                        }
                                                        Desktop.getDesktop().browse(URI(link))
                                                    }.onFailure(gateway::showError)
                                                }
                                            }
                                        ) {
                                            Text("Open")
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Text(
                                "Cache bytes and saved bytes are separate measurements. " +
                                    "Cached files can save at disk speed.\n" +
                                    "LAN links are accessible to devices that can reach port 8080. " +
                                    "Use a trusted network.\n" +
                                    "Visible windows stay online. Minimized idle gateways stop; " +
                                    "closing the window stops all transfers.",
                                color = Ink.copy(alpha = .55f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun bytes(value: Long): String {
    val number = value.coerceAtLeast(0)
    return when {
        number >= (1L shl 30) ->
            String.format(Locale.ROOT, "%.1f GiB", number / (1L shl 30).toDouble())
        number >= (1L shl 20) ->
            String.format(Locale.ROOT, "%.1f MiB", number / (1L shl 20).toDouble())
        number >= (1L shl 10) ->
            String.format(Locale.ROOT, "%.1f KiB", number / (1L shl 10).toDouble())
        else -> "$number B"
    }
}

private fun cacheDirectory(): File {
    val home = System.getProperty("user.home")
    return when {
        System.getProperty("os.name").startsWith("Windows") ->
            File(System.getenv("LOCALAPPDATA") ?: home, "TorrentToDDL/cache")
        System.getProperty("os.name").startsWith("Mac") ->
            File(home, "Library/Caches/TorrentToDDL")
        else ->
            File(System.getenv("XDG_CACHE_HOME") ?: "$home/.cache", "TorrentToDDL")
    }
}

/** Also invoked through the packaged launcher to verify the bundled runtime/JNI. */
private fun smokeTest() = runBlocking {
    DesktopNativeLibrary.load()
    val root = Files.createTempDirectory("torrenttoddl-smoke-").toFile()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val manager = SessionManager()
    val engine = TorrentStreamEngine(root, scope, manager)
    val server = LocalHttpStreamServer(0, engine)
    try {
        check(!manager.isRunning) { "Startup must not enable networking." }
        check(engine.startSession()) { engine.status.value.stateText }
        server.start(3000, true)
        val connection = URL(
            "http://127.0.0.1:${server.listeningPort}/files/0/preview.bin"
        ).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            check(connection.responseCode == 503)
        } finally {
            connection.disconnect()
        }
        println("Desktop native library and HTTP gateway smoke test passed.")
    } finally {
        server.stop()
        engine.stopSession()
        check(!manager.isRunning) {
            "Shutdown left torrent networking running."
        }
        scope.cancel()
        root.deleteRecursively()
    }
}
