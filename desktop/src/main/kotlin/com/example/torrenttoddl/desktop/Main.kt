package com.example.torrenttoddl.desktop

import androidx.compose.foundation.*
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import java.awt.Desktop
import java.awt.Dimension
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.swing.Swing
import kotlin.system.exitProcess

private val Background = Color(0xFF0D1117)
private val Sidebar = Color(0xFF101720)
private val Panel = Color(0xFF171F2A)
private val Border = Color(0xFF293443)
private val Accent = Color(0xFFB6F268)
private val Blue = Color(0xFF70C8EA)
private val TextColor = Color(0xFFE5ECF4)
private val Muted = Color(0xFF8C9BAD)

@OptIn(ExperimentalMaterial3Api::class)
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--gateway-worker") {
        try { gatewayWorker(File(args[1])); exitProcess(0) }
        catch (error: Throwable) { error.printStackTrace(); exitProcess(1) }
    }
    if ("--smoke-test" in args) { smokeTest(); return }
    if (args.firstOrNull() == "--headless") { headlessGateway(File(args.getOrElse(1) { "data" })); return }
    val instance = acquireDesktopInstance(applicationDirectory())
    if (instance == null) {
        javax.swing.JOptionPane.showMessageDialog(null, "TorrentToDDL is already running. Open it from the system tray.")
        return
    }
    Runtime.getRuntime().addShutdownHook(Thread { instance.close() })
    application {
        val gateway = remember { GatewayProcess() }
        val view by gateway.view.collectAsState()
        val settings by gateway.settings.collectAsState()
        val state = rememberWindowState(width = 1340.dp, height = 860.dp)
        val scope = rememberCoroutineScope()
        var hidden by remember { mutableStateOf(false) }
        var closing by remember { mutableStateOf(false) }
        var page by remember { mutableStateOf("Torrents") }
        var magnetDialog by remember { mutableStateOf(false) }
        var selectedId by remember { mutableStateOf<String?>(null) }
        val selected = view.torrents.firstOrNull { it.id == selectedId } ?: view.torrents.firstOrNull()
        fun quit() {
            if (!closing) { closing = true; scope.launch { try { gateway.shutdown() } finally { exitApplication() } } }
        }
        if (SystemTray.isSupported()) Tray(
            icon = painterResource("torrenttoddl-logo.svg"), tooltip = "TorrentToDDL",
            onAction = { hidden = false; state.isMinimized = false },
            menu = {
                Item("Open TorrentToDDL") { hidden = false; state.isMinimized = false }
                Item("Stop backend", enabled = view.running) { gateway.stop() }
                Separator()
                Item("Quit") { quit() }
            }
        )
        Window(
            title = "TorrentToDDL", state = state, visible = !hidden,
            icon = painterResource("torrenttoddl-logo.svg"),
            onCloseRequest = {
                if (settings.closeToTray && SystemTray.isSupported()) hidden = true else quit()
            }
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(1000, 680) }
            suspend fun chooseFile(save: Boolean = false, directory: Boolean = false, initial: File? = null): File? =
                withContext(Dispatchers.Swing) {
                    JFileChooser(initial?.takeIf { it.isDirectory } ?: initial?.parentFile).apply {
                        if (directory) fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        else if (!save) fileFilter = FileNameExtensionFilter("Torrent files", "torrent")
                        if (save) selectedFile = initial
                    }.let { chooser ->
                        val result = if (save) chooser.showSaveDialog(window) else chooser.showOpenDialog(window)
                        if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
                    }
                }
            fun chooseFolder(current: String, result: (String) -> Unit) {
                scope.launch { try { chooseFile(directory = true, initial = File(current))?.let { result(it.absolutePath) } }
                    catch (error: Exception) { gateway.showError(error) } }
            }
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Accent, onPrimary = Background, secondary = Blue, background = Background,
                surface = Panel, surfaceVariant = Sidebar, outline = Border, onBackground = TextColor,
                onSurface = TextColor, onSurfaceVariant = Muted
            )) {
                Surface(color = Background) {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.weight(1f)) {
                            Column(Modifier.width(184.dp).fillMaxHeight().background(Sidebar).padding(20.dp)) {
                                Image(painterResource("torrenttoddl-logo.svg"), "TorrentToDDL", Modifier.size(40.dp))
                                Spacer(Modifier.height(14.dp))
                                Text("TorrentToDDL", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text("DESKTOP WORKSPACE", color = Muted, style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.height(36.dp))
                                Text("LIBRARY", color = Muted, style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.height(10.dp))
                                listOf("Torrents", "Downloads", "Settings").forEach { target ->
                                    Surface(color = if (page == target) Accent.copy(alpha = .12f) else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().clickable { page = target }) {
                                        Row(Modifier.padding(vertical = 12.dp, horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(target, color = if (page == target) Accent else Muted,
                                                fontWeight = if (page == target) FontWeight.SemiBold else FontWeight.Normal)
                                            if (target != "Settings") Text(if (target == "Torrents") "${view.torrents.size}" else "${view.exports.count { !it.finished }}", color = Muted)
                                        }
                                    }
                                    Spacer(Modifier.height(5.dp))
                                }
                                Spacer(Modifier.weight(1f))
                                HorizontalDivider(color = Border)
                                Spacer(Modifier.height(16.dp))
                                Text(if (view.running) "●  Backend online" else "○  Backend stopped", color = if (view.running) Accent else Muted,
                                    style = MaterialTheme.typography.labelMedium)
                                Text("${settings.concurrentDownloads} download slots", color = Muted, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { quit() }, enabled = !closing) { Text("Quit application", color = Muted) }
                            }
                            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 28.dp, vertical = 24.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(page, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                                        Text(when (page) {
                                            "Settings" -> "Make this workspace yours."
                                            "Downloads" -> "Files saved to your chosen destination."
                                            else -> "Manage transfers. Stream files. Stay in control."
                                        }, color = Muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                    OutlinedButton(onClick = { magnetDialog = true }, enabled = !closing && !view.busy) { Text("+ Magnet") }
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedButton(onClick = { scope.launch {
                                        try { chooseFile()?.let { gateway.openTorrent(it) } } catch (error: Exception) { gateway.showError(error) }
                                    } }, enabled = !closing && !view.busy) { Text("Add torrent") }
                                    Spacer(Modifier.width(12.dp))
                                    Button(onClick = { if (view.running) gateway.stop() else gateway.start() }, enabled = !closing && !view.busy) {
                                        Text(if (view.busy) "Working…" else if (view.running) "Stop backend" else "Start backend")
                                    }
                                }
                                Spacer(Modifier.height(24.dp))
                                when (page) {
                                    "Settings" -> SettingsPanel(settings, view.running || view.busy, ::chooseFolder, gateway::saveSettings)
                                    "Downloads" -> DownloadsPanel(view, gateway)
                                    else -> {
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Metric("RECEIVING", bytes(view.torrents.sumOf { it.engine.downloadSpeedKbps.toLong() } * 1024) + "/s", Blue, Modifier.weight(1f))
                                            Metric("CACHED", bytes(view.torrents.sumOf { it.engine.cachedBytes }), TextColor, Modifier.weight(1f))
                                            Metric("PEERS", "${view.torrents.sumOf { it.engine.peers }}", TextColor, Modifier.weight(1f))
                                            Metric("SAVING", "${view.exports.count { !it.finished }} files", Accent, Modifier.weight(1f))
                                        }
                                        Spacer(Modifier.height(22.dp))
                                        TorrentTable(view, selected?.id, { selectedId = it }, Modifier.weight(.44f))
                                        Spacer(Modifier.height(16.dp))
                                        FilePanel(selected, view, settings, gateway, Modifier.weight(.56f),
                                            onSaveAs = { torrent, file -> scope.launch {
                                                try { chooseFile(save = true, initial = File(settings.downloadDirectory, safeName(file.name)))?.let {
                                                    gateway.save(torrent.id, file.index, it)
                                                } } catch (error: Exception) { gateway.showError(error) }
                                            } })
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().background(Sidebar).padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(view.notice, color = if (view.notice.contains("fail", true) || view.notice.contains("error", true)) MaterialTheme.colorScheme.error else Muted,
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            Text(if (view.backendPid > 0) "PID ${view.backendPid}   •   ${view.activeStreams} streams   •   :${settings.port}" else "No backend process",
                                color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (magnetDialog) {
                        var magnet by remember { mutableStateOf("") }
                        AlertDialog(onDismissRequest = { magnetDialog = false }, title = { Text("Add magnet link") },
                            text = { OutlinedTextField(magnet, { magnet = it }, label = { Text("magnet:?xt=…") }, modifier = Modifier.width(560.dp), minLines = 3) },
                            confirmButton = { Button(onClick = { gateway.openMagnet(magnet); magnetDialog = false }, enabled = magnet.trim().startsWith("magnet:", true)) { Text("Add to queue") } },
                            dismissButton = { TextButton(onClick = { magnetDialog = false }) { Text("Cancel") } })
                    }
                }
            }
        }
    }
}

@Composable private fun Metric(label: String, value: String, color: Color, modifier: Modifier) {
    Surface(color = Panel, shape = RoundedCornerShape(10.dp), modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(8.dp))
            Text(value, color = color, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable private fun TorrentTable(view: DesktopView, selected: String?, select: (String) -> Unit, modifier: Modifier) {
    Column(modifier.border(1.dp, Border, RoundedCornerShape(10.dp))) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("NAME / STATUS", Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.labelSmall)
            Text("PROGRESS", Modifier.width(140.dp), color = Muted, style = MaterialTheme.typography.labelSmall)
            Text("RECEIVED", Modifier.width(100.dp), color = Muted, style = MaterialTheme.typography.labelSmall)
            Text("SPEED", Modifier.width(90.dp), color = Muted, style = MaterialTheme.typography.labelSmall)
            Text("PEERS", Modifier.width(42.dp), color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        if (view.torrents.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Your next download starts here", style = MaterialTheme.typography.titleMedium, color = TextColor)
                Spacer(Modifier.height(8.dp)); Text("Add a magnet link or a .torrent file to build your queue.", color = Muted)
            }
        } else LazyColumn(Modifier.fillMaxSize()) {
            items(view.torrents, key = { it.id }) { t ->
                Row(Modifier.fillMaxWidth().background(if (t.id == selected) Blue.copy(alpha = .1f) else Color.Transparent)
                    .clickable { select(t.id) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(t.engine.torrentName.ifBlank { "Resolving metadata…" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(if (t.paused) "Paused" else t.engine.stateText, color = if (t.paused) Muted else Blue, style = MaterialTheme.typography.labelSmall)
                    }
                    Column(Modifier.width(140.dp).padding(end = 18.dp)) {
                        Text("%.1f%%".format(Locale.ROOT, t.engine.progressPercent), style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(5.dp))
                        LinearProgressIndicator(progress = { t.engine.progressPercent / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Accent)
                    }
                    Text(bytes(t.engine.cachedBytes), Modifier.width(100.dp), style = MaterialTheme.typography.bodySmall)
                    Text(bytes(t.engine.downloadSpeedKbps.toLong() * 1024) + "/s", Modifier.width(90.dp), style = MaterialTheme.typography.bodySmall)
                    Text("${t.engine.peers}", Modifier.width(42.dp), style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider(color = Border.copy(alpha = .5f))
            }
        }
    }
}

@Composable private fun FilePanel(
    torrent: DesktopTorrent?, view: DesktopView, settings: DesktopSettings, gateway: GatewayProcess,
    modifier: Modifier, onSaveAs: (DesktopTorrent, com.example.torrenttoddl.engine.TorrentFileEntry) -> Unit
) {
    var query by remember(torrent?.id) { mutableStateOf("") }
    var removeDialog by remember { mutableStateOf(false) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Files", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (torrent != null) {
                TextButton(onClick = { gateway.pause(torrent.id, !torrent.paused) }, enabled = view.running && !view.busy) { Text(if (torrent.paused) "Resume" else "Pause") }
                TextButton(onClick = { removeDialog = true }, enabled = view.running && !view.busy) { Text("Remove", color = Muted) }
                OutlinedButton(onClick = { gateway.saveAll(torrent.id) }, enabled = view.running && torrent.engine.isReadyForDdl) { Text("Save all files") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Destination: ${settings.downloadDirectory}", color = Muted, modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(query, { query = it }, placeholder = { Text("Filter files", style = MaterialTheme.typography.bodySmall) },
                singleLine = true, modifier = Modifier.width(220.dp).height(52.dp))
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.fillMaxSize().border(1.dp, Border, RoundedCornerShape(8.dp))) {
            val files = torrent?.engine?.files.orEmpty().filter { it.relativePath.contains(query, true) }
            items(files, key = { it.index }) { file ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        Text(file.relativePath, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(bytes(file.sizeBytes), Modifier.width(90.dp), color = Muted, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { gateway.save(torrent!!.id, file.index) }, enabled = view.running) { Text("Save") }
                    TextButton(onClick = { onSaveAs(torrent!!, file) }, enabled = view.running) { Text("Save as") }
                    TextButton(onClick = {
                        val link = torrentLink(view.lanAddress, torrent!!.id, file, settings.accessToken)
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(link), null)
                    }, enabled = view.running) { Text("Copy link") }
                }
                HorizontalDivider(color = Border.copy(alpha = .5f))
            }
            if (files.isEmpty()) item { Text(if (torrent == null) "Select a torrent to inspect its files." else "Waiting for matching file metadata…",
                Modifier.padding(24.dp), color = Muted) }
        }
    }
    if (removeDialog && torrent != null) AlertDialog(onDismissRequest = { removeDialog = false },
        title = { Text("Remove torrent from the queue?") }, text = { Text("Cached data and files you saved will be kept.") },
        confirmButton = { TextButton(onClick = { gateway.remove(torrent.id); removeDialog = false }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removeDialog = false }) { Text("Cancel") } })
}

@Composable private fun DownloadsPanel(view: DesktopView, gateway: GatewayProcess) {
    if (view.exports.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Saved files and queued exports will appear here.", color = Muted)
    } else LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(view.exports.asReversed(), key = { it.id }) { e ->
            Surface(color = Panel, shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        if (!e.finished) TextButton(onClick = { gateway.cancelSave(e.id) }) { Text("Cancel") }
                        else if (!e.failed && e.state == "Saved") TextButton(onClick = {
                            runCatching { Desktop.getDesktop().open(File(e.destination).parentFile) }.onFailure(gateway::showError)
                        }) { Text("Show folder") }
                    }
                    Text(e.destination, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { if (e.total > 0) (e.bytes.toDouble() / e.total).toFloat() else if (e.finished) 1f else 0f },
                        modifier = Modifier.fillMaxWidth(), color = if (e.failed) MaterialTheme.colorScheme.error else Accent)
                    Spacer(Modifier.height(8.dp))
                    Text("${e.state}  ·  ${bytes(e.bytes)} / ${bytes(e.total)}  ·  ${bytes(e.rate)}/s", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable private fun SettingsPanel(
    saved: DesktopSettings, locked: Boolean, browse: (String, (String) -> Unit) -> Unit, save: (DesktopSettings) -> Any
) {
    var downloads by remember(saved) { mutableStateOf(saved.downloadDirectory) }
    var cache by remember(saved) { mutableStateOf(saved.cacheDirectory) }
    var port by remember(saved) { mutableStateOf("${saved.port}") }
    var down by remember(saved) { mutableStateOf("${saved.downloadLimitKiB}") }
    var up by remember(saved) { mutableStateOf("${saved.uploadLimitKiB}") }
    var connections by remember(saved) { mutableStateOf("${saved.connectionsPerTorrent}") }
    var slots by remember(saved) { mutableStateOf("${saved.concurrentDownloads}") }
    var lan by remember(saved) { mutableStateOf(saved.shareOnLan) }
    var seed by remember(saved) { mutableStateOf(saved.seedAfterDownload) }
    var tray by remember(saved) { mutableStateOf(saved.closeToTray) }
    var key by remember(saved) { mutableStateOf(saved.accessToken) }
    var showKey by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SettingsGroup("Storage", "Choose where downloads and reusable torrent data are kept.") {
            FolderField("Download folder", downloads, { downloads = it }, browse)
            FolderField("Torrent cache folder", cache, { cache = it }, browse)
            Text("Exports are separate copies. Allow space for both cache and saved files. Changing cache folder preserves the old data.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        SettingsGroup("Transfers", "Limits apply to each torrent. Use 0 for unlimited bandwidth.") {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(down, { down = it }, label = { Text("Download KiB/s") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(up, { up = it }, label = { Text("Upload KiB/s") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(slots, { slots = it }, label = { Text("Concurrent downloads (1–8)") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(connections, { connections = it }, label = { Text("Connections per torrent") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            ToggleRow("Continue seeding completed torrents", "Share verified pieces with peers after a download finishes.", seed) { seed = it }
        }
        SettingsGroup("Gateway & sharing", "Links require your access key. Keep shared links private.") {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(port, { port = it }, label = { Text("HTTP port") }, singleLine = true, modifier = Modifier.width(180.dp))
                ToggleRow("Allow LAN sharing", "Off restricts downloads to this computer.", lan, Modifier.weight(1f)) { lan = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(key, { key = it }, label = { Text("Access key") }, singleLine = true, modifier = Modifier.weight(1f),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation())
                TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") }
                OutlinedButton(onClick = { key = newAccessToken() }) { Text("Rotate key") }
            }
            Text("Public internet access requires an HTTPS host or VPN. The app does not open router ports automatically.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        SettingsGroup("Desktop behavior", "Transfers continue while minimized. Stop terminates the backend process.") {
            ToggleRow("Close to system tray", "Keep transfers running when closing the window. Use Quit to exit completely.", tray) { tray = it }
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = {
                try {
                    val value = saved.copy(downloadDirectory = downloads.trim(), cacheDirectory = cache.trim(), port = port.toInt(),
                        downloadLimitKiB = down.toInt(), uploadLimitKiB = up.toInt(), connectionsPerTorrent = connections.toInt(),
                        concurrentDownloads = slots.toInt(), shareOnLan = lan, seedAfterDownload = seed, closeToTray = tray, accessToken = key.trim())
                    value.validate(); save(value); error = ""
                } catch (failure: Exception) { error = failure.message ?: "Check the settings values." }
            }, enabled = !locked) { Text("Apply settings") }
            Text(if (locked) "Stop the backend before applying settings." else "Settings are saved on this computer.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable private fun SettingsGroup(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, color = Muted, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

@Composable private fun FolderField(label: String, value: String, change: (String) -> Unit, browse: (String, (String) -> Unit) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(value, change, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { browse(value, change) }) { Text("Browse…") }
    }
}

@Composable private fun ToggleRow(title: String, description: String, checked: Boolean, modifier: Modifier = Modifier, change: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, change)
    }
}

private fun bytes(value: Long): String = when {
    value >= (1L shl 30) -> String.format(Locale.ROOT, "%.1f GiB", value.toDouble() / (1L shl 30))
    value >= (1L shl 20) -> String.format(Locale.ROOT, "%.1f MiB", value.toDouble() / (1L shl 20))
    value >= 1024 -> String.format(Locale.ROOT, "%.1f KiB", value / 1024.0)
    else -> "${value.coerceAtLeast(0)} B"
}

/** Runs through the packaged launcher and starts/stops an actual child backend. */
private fun smokeTest() = runBlocking {
    val root = Files.createTempDirectory("torrenttoddl-process-").toFile()
    val port = java.net.ServerSocket(0).use { it.localPort }
    writeProperties(File(root, "settings.properties"), DesktopSettings(port = port, cacheDirectory = File(root, "cache").path).properties())
    val gateway = GatewayProcess(root)
    try {
        gateway.start().join()
        check(gateway.view.value.running) { gateway.view.value.notice }
        val pid = gateway.view.value.backendPid
        check(pid != ProcessHandle.current().pid()) { "Backend must be a separate process." }
        val connection = URL("http://127.0.0.1:$port/files/0/check.bin").openConnection() as HttpURLConnection
        try { connection.connectTimeout = 3000; connection.readTimeout = 3000; check(connection.responseCode == 401) }
        finally { connection.disconnect() }
        gateway.stop().join()
        check(!gateway.view.value.running && gateway.view.value.backendPid == 0L)
        check(ProcessHandle.of(pid).map { !it.isAlive }.orElse(true)) { "Stop left a backend process running." }
        java.net.ServerSocket(port).use { }
        check(System.getProperty("libtorrent4j.jni.path") == null) { "UI process must never load the native engine." }
        println("Desktop backend process, authentication, port release and shutdown checks passed.")
    } finally { gateway.shutdown(); root.deleteRecursively() }
}
