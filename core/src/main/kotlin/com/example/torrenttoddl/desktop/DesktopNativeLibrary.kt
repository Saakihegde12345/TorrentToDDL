package com.example.torrenttoddl.desktop

import java.nio.file.Files
import java.util.Collections
import java.util.Locale

/** Desktop only: Android continues to load its own ABI-specific JNI library. */
object DesktopNativeLibrary {
    private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        val platform = desktopPlatform(System.getProperty("os.name"), System.getProperty("os.arch"))
        val (host, architecture, suffix) = platform
        val name = "libtorrent4j$suffix"
        val loader = DesktopNativeLibrary::class.java.classLoader
        val resource = listOf("lib/$architecture/$name", "lib/$name", name)
            .flatMap { Collections.list(loader.getResources(it)) }
            // Android and Linux jars use the same resource name. Never pick Android.
            .firstOrNull { it.toExternalForm().contains("libtorrent4j-$host-") }
            ?: error("Missing $host $architecture native binary. For Apple Silicon, run python scripts/build-apple-native.py macos-arm64 before Gradle.")
        val directory = Files.createTempDirectory("torrenttoddl-jni-").toFile()
        directory.deleteOnExit()
        val library = directory.resolve(name)
        resource.openStream().use { input ->
            library.outputStream().use { input.copyTo(it) }
        }
        library.deleteOnExit()
        System.setProperty("libtorrent4j.jni.path", library.absolutePath)
        Class.forName("org.libtorrent4j.swig.libtorrent_jni")
        loaded = true
    }
}

internal data class DesktopPlatform(val host: String, val architecture: String, val suffix: String)

internal fun desktopPlatform(osName: String, archName: String): DesktopPlatform {
    val os = osName.lowercase(Locale.ROOT)
    val architecture = when (archName.lowercase(Locale.ROOT)) {
        "amd64", "x86_64" -> "x86_64"
        "arm64", "aarch64" -> "arm64"
        else -> error("Unsupported desktop architecture: $archName")
    }
    val (host, suffix) = when {
        os.startsWith("windows") -> "windows" to ".dll"
        os.startsWith("mac") -> "macos" to ".dylib"
        os.startsWith("linux") -> "linux" to ".so"
        else -> error("Unsupported desktop OS: $osName")
    }
    require(architecture == "x86_64" || host == "macos") {
        "This preview supports ARM64 on macOS; $osName / $archName is not packaged."
    }
    return DesktopPlatform(host, architecture, suffix)
}
