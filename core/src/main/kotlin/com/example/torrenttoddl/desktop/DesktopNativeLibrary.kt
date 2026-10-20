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
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
        require(arch in setOf("amd64", "x86_64")) {
            "This preview needs an x64 JVM and x64 desktop native library; detected $os / $arch."
        }
        val (host, suffix) = when {
            os.startsWith("windows") -> "windows" to ".dll"
            os.startsWith("mac") -> "macos" to ".dylib"
            os.startsWith("linux") -> "linux" to ".so"
            else -> error("Unsupported desktop OS: $os")
        }
        val name = "libtorrent4j$suffix"
        val loader = DesktopNativeLibrary::class.java.classLoader
        val resource = listOf("lib/x86_64/$name", "lib/$name", name)
            .flatMap { Collections.list(loader.getResources(it)) }
            // Android and Linux jars use the same resource name. Never pick Android.
            .firstOrNull { it.toExternalForm().contains("libtorrent4j-$host-") }
            ?: error("Missing $host x64 native binary. Install the matching desktop package.")
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
