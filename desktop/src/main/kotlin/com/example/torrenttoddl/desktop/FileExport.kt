package com.example.torrenttoddl.desktop

import java.io.EOFException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/** Never overwrite a user's file; incomplete saves stay hidden and are removed. */
internal fun exportFile(
    input: InputStream, size: Long, destination: Path,
    progress: (Long) -> Unit
) {
    require(size >= 0)
    val target = destination.toAbsolutePath().normalize()
    require(!Files.exists(target)) { "File already exists. Choose a different name." }
    val partial = Files.createTempFile(target.parent, ".torrenttoddl-", ".part")
    try {
        var written = 0L
        Files.newOutputStream(partial).use { output ->
            val buffer = ByteArray(128 * 1024)
            while (written < size) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val count = input.read(
                    buffer, 0, minOf(buffer.size.toLong(), size - written).toInt()
                )
                if (count < 0) {
                    throw EOFException("Torrent stream ended after $written of $size bytes.")
                }
                if (count == 0) continue
                output.write(buffer, 0, count)
                written += count
                progress(written)
            }
        }
        // No REPLACE_EXISTING: a file created while downloading must also survive.
        Files.move(partial, target)
    } finally {
        Files.deleteIfExists(partial)
    }
}
