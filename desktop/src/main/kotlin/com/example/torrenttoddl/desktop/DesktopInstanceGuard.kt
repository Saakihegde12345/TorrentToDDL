package com.example.torrenttoddl.desktop

import java.io.Closeable
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

/** Prevent two desktop windows from controlling the same queue/cache. */
internal fun acquireDesktopInstance(root: File): Closeable? {
    root.mkdirs()
    val channel = FileChannel.open(File(root, "desktop.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
    if (lock == null) { channel.close(); return null }
    return Closeable { try { lock.release() } finally { channel.close() } }
}
