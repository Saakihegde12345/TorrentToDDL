package com.example.torrenttoddl.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties

data class DesktopSettings(
    val downloadDirectory: String = File(System.getProperty("user.home"), "Downloads/TorrentToDDL").absolutePath,
    val cacheDirectory: String = File(applicationDirectory(), "cache").absolutePath,
    val port: Int = 8080,
    val shareOnLan: Boolean = true,
    val accessToken: String = newAccessToken(),
    val seedAfterDownload: Boolean = true,
    val downloadLimitKiB: Int = 0,
    val uploadLimitKiB: Int = 0,
    val connectionsPerTorrent: Int = 200,
    val concurrentDownloads: Int = 3,
    val closeToTray: Boolean = true,
    val adminToken: String = newAccessToken()
) {
    fun validate() {
        require(File(downloadDirectory).isAbsolute && File(cacheDirectory).isAbsolute) { "Choose absolute download and cache folders." }
        require(port in 1024..65535) { "Gateway port must be between 1024 and 65535." }
        require(accessToken.matches(Regex("[A-Za-z0-9_-]{32,128}"))) { "Use a generated access key (32–128 URL-safe characters)." }
        require(adminToken.matches(Regex("[A-Za-z0-9_-]{32,128}")) && adminToken != accessToken) { "Use a separate generated administration key." }
        require(downloadLimitKiB in 0..1_000_000 && uploadLimitKiB in 0..1_000_000) { "Speed limits must be 0–1000000 KiB/s; 0 is unlimited." }
        require(connectionsPerTorrent in 10..2000 && concurrentDownloads in 1..8) { "Use 10–2000 connections and 1–8 simultaneous downloads." }
    }

    fun properties() = Properties().apply {
        setProperty("downloadDirectory", downloadDirectory); setProperty("cacheDirectory", cacheDirectory)
        setProperty("port", "$port"); setProperty("shareOnLan", "$shareOnLan"); setProperty("accessToken", accessToken)
        setProperty("seedAfterDownload", "$seedAfterDownload"); setProperty("downloadLimitKiB", "$downloadLimitKiB")
        setProperty("uploadLimitKiB", "$uploadLimitKiB"); setProperty("connectionsPerTorrent", "$connectionsPerTorrent")
        setProperty("concurrentDownloads", "$concurrentDownloads"); setProperty("closeToTray", "$closeToTray")
        setProperty("adminToken", adminToken)
    }

    companion object {
        fun from(p: Properties) = DesktopSettings(
            downloadDirectory = p.getProperty("downloadDirectory") ?: File(System.getProperty("user.home"), "Downloads/TorrentToDDL").absolutePath,
            cacheDirectory = p.getProperty("cacheDirectory") ?: File(applicationDirectory(), "cache").absolutePath,
            port = p.getProperty("port", "8080").toInt(), shareOnLan = p.getProperty("shareOnLan", "true").toBoolean(),
            accessToken = p.getProperty("accessToken") ?: newAccessToken(), seedAfterDownload = p.getProperty("seedAfterDownload", "true").toBoolean(),
            downloadLimitKiB = p.getProperty("downloadLimitKiB", "0").toInt(), uploadLimitKiB = p.getProperty("uploadLimitKiB", "0").toInt(),
            connectionsPerTorrent = p.getProperty("connectionsPerTorrent", "200").toInt(), concurrentDownloads = p.getProperty("concurrentDownloads", "3").toInt(),
            closeToTray = p.getProperty("closeToTray", "true").toBoolean(), adminToken = p.getProperty("adminToken") ?: newAccessToken()
        ).also { it.validate() }
    }
}

fun newAccessToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

fun applicationDirectory(): File {
    val home = System.getProperty("user.home")
    return when {
        System.getProperty("os.name").startsWith("Windows") -> File(System.getenv("LOCALAPPDATA") ?: home, "TorrentToDDL")
        System.getProperty("os.name").startsWith("Mac") -> File(home, "Library/Application Support/TorrentToDDL")
        else -> File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "TorrentToDDL")
    }
}

internal fun readProperties(file: File): Properties = Properties().apply {
    if (file.exists()) file.inputStream().use { load(it) }
}

internal fun writeProperties(file: File, properties: Properties) {
    Files.createDirectories(file.toPath().toAbsolutePath().parent)
    val temporary = Files.createTempFile(file.toPath().toAbsolutePath().parent, ".settings-", ".tmp")
    try {
        Files.newOutputStream(temporary).use { properties.store(it, "TorrentToDDL") }
        try {
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally { Files.deleteIfExists(temporary) }
}
