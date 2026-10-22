package com.example.torrenttoddl.desktop

import java.io.File
import java.io.PrintWriter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Private OS pipes carry commands/status; the worker exposes no control port. */
class GatewayProcess(private val root: File = applicationDirectory()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Mutex()
    private val state = MutableStateFlow(DesktopView())
    val view = state.asStateFlow()
    private val settingsFile = File(root, "settings.properties")
    private val preferences = MutableStateFlow(DesktopSettings.from(readProperties(settingsFile)))
    val settings = preferences.asStateFlow()
    @Volatile private var process: Process? = null
    private var input: PrintWriter? = null
    private var reader: Job? = null
    private var closed = false

    init { writeProperties(settingsFile, preferences.value.properties()) }

    fun start() = command { ensureStarted() }
    fun stop() = command { stopInternal() }
    fun openMagnet(value: String) = send("MAGNET", value)
    fun openTorrent(file: File) = send("TORRENT", file.absolutePath)
    fun pause(id: String, paused: Boolean) = send("PAUSE", id, paused.toString())
    fun remove(id: String) = send("REMOVE", id)
    fun save(id: String, index: Int, destination: File? = null) = send("SAVE", id, index.toString(), destination?.absolutePath ?: "")
    fun saveAll(id: String) = send("SAVE_ALL", id)
    fun cancelSave(id: String) = send("CANCEL", id)

    fun saveSettings(value: DesktopSettings) = command {
        require(process == null) { "Stop the backend before applying settings. Existing files are preserved." }
        value.validate()
        writeProperties(settingsFile, value.properties())
        preferences.value = value
        state.update { it.copy(notice = "Settings saved") }
    }

    fun showError(error: Throwable) { state.update { it.copy(notice = error.message ?: error.javaClass.simpleName) } }

    private fun send(name: String, vararg fields: String) = command {
        ensureStarted()
        check(state.value.running) { "The gateway could not start. Check the status message." }
        input!!.println((listOf(name) + fields.map(::encodeText)).joinToString("\t"))
        check(!input!!.checkError()) { "The gateway process disconnected." }
    }

    private suspend fun ensureStarted() {
        if (process?.isAlive == true) return
        preferences.value.validate()
        val java = File(System.getProperty("java.home"), "bin/" + if (isWindows()) "java.exe" else "java")
        val classpath = System.getProperty("torrenttoddl.worker.classpath", System.getProperty("java.class.path"))
        val packaged = System.getProperty("jpackage.app-path")?.let(::File)
        // jpackage strips java.exe from its runtime. Reuse its native launcher
        // in worker mode; source/test runs use the development JDK executable.
        val command = if (packaged?.isFile == true) listOf(packaged.absolutePath, "--gateway-worker", root.absolutePath)
            else {
                check(java.isFile) { "The Java runtime is missing." }
                listOf(java.absolutePath, "-Xmx768m", "-cp", classpath,
                    "com.example.torrenttoddl.desktop.MainKt", "--gateway-worker", root.absolutePath)
            }
        val created = ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.appendTo(File(root, "backend.log"))).start()
        process = created
        input = PrintWriter(created.outputStream, true, Charsets.UTF_8)
        state.update { it.copy(busy = true, notice = "Starting backend…", backendPid = created.pid()) }
        reader = scope.launch {
            try {
                created.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (line.startsWith("STATE\t") && process === created) {
                            val next = decodeView(line.substringAfter('\t'))
                            state.value = next.copy(backendPid = if (created.isAlive) created.pid() else 0)
                        }
                    }
                }
            } catch (error: Exception) {
                if (process === created) showError(error)
            } finally {
                if (process === created) {
                    val code = created.waitFor()
                    process = null
                    state.update {
                        val reason = if (!it.running && it.notice != "Backend stopped") it.notice
                            else if (code == 0) "Backend stopped" else "Backend exited ($code). Check backend.log."
                        it.stopped(reason)
                    }
                }
            }
        }
        try {
            withTimeout(15000) { view.first { it.running || !created.isAlive } }
            check(created.isAlive && state.value.running) { state.value.notice }
        } catch (error: Exception) {
            stopInternal()
            throw error
        }
    }

    private suspend fun stopInternal() {
        val worker = process ?: return
        state.update { it.copy(busy = true, notice = "Stopping backend…") }
        input?.println("QUIT")
        input?.close(); input = null
        val ended = runInterruptible { worker.waitFor(8, TimeUnit.SECONDS) }
        if (!ended) {
            worker.destroy()
            if (!runInterruptible { worker.waitFor(2, TimeUnit.SECONDS) }) worker.destroyForcibly()
        }
        check(runInterruptible { worker.waitFor(5, TimeUnit.SECONDS) }) { "Could not terminate gateway process ${worker.pid()}." }
        process = null
        reader?.join(); reader = null
        state.update { it.stopped("Backend stopped · port released") }
    }

    suspend fun shutdown() {
        commands.withLock { closed = true; stopInternal() }
        scope.cancel()
    }

    private fun command(block: suspend () -> Unit): Job = scope.launch {
        commands.withLock {
            if (closed) return@withLock
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showError(error) }
            finally { state.update { it.copy(busy = false) } }
        }
    }

    companion object {
        private fun isWindows() = System.getProperty("os.name").startsWith("Windows")
    }
}

private fun DesktopView.stopped(message: String) = copy(
    running = false, busy = false, backendPid = 0, activeStreams = 0, lanAddress = "", notice = message,
    torrents = torrents.map { it.copy(engine = it.engine.copy(downloadSpeedKbps = 0, peers = 0, stateText = "Stopped")) },
    exports = exports.map { if (it.finished) it else it.copy(finished = true, state = "Cancelled: backend stopped", rate = 0) }
)

internal fun gatewayWorker(root: File) = runBlocking {
    val gateway = DesktopGateway(root, DesktopSettings.from(readProperties(File(root, "settings.properties"))))
    val outputLock = Any()
    fun publish(value: DesktopView) = synchronized(outputLock) { println("STATE\t${value.encode()}"); System.out.flush() }
    val publishing = launch(Dispatchers.IO) {
        // File metadata can be large. Publish once per telemetry tick, plus startup.
        gateway.view.collect { publish(it) }
    }
    try {
        gateway.start().join()
        if (!gateway.view.value.running) { publish(gateway.view.value); return@runBlocking }
        System.`in`.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val fields = line.split('\t')
                val args = fields.drop(1).map(::decodeText)
                if (fields[0] == "QUIT") break
                when (fields[0]) {
                    "MAGNET" -> gateway.openMagnet(args[0]).join()
                    "TORRENT" -> gateway.openTorrent(File(args[0])).join()
                    "PAUSE" -> gateway.pause(args[0], args[1].toBoolean()).join()
                    "REMOVE" -> gateway.remove(args[0]).join()
                    "SAVE" -> gateway.save(args[0], args[1].toInt(), args[2].takeIf { it.isNotEmpty() }?.let(::File)).join()
                    "SAVE_ALL" -> gateway.saveAll(args[0]).join()
                    "CANCEL" -> gateway.cancelSave(args[0]).join()
                }
            }
        }
    } finally {
        gateway.shutdown()
        publishing.cancelAndJoin()
    }
}
