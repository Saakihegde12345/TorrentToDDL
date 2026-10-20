package com.example.torrenttoddl.desktop

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class FileExportTest {
    @Test fun savesExactBytesReportsRealProgressAndRefusesOverwrite() {
        val root = Files.createTempDirectory("export-test").toFile()
        try {
            val target = root.resolve("saved.bin").toPath()
            val payload = ByteArray(300_001) { (it % 251).toByte() }
            var reported = 0L
            exportFile(
                ByteArrayInputStream(payload), payload.size.toLong(), target
            ) { reported = it }
            assertArrayEquals(payload, Files.readAllBytes(target))
            assertEquals(payload.size.toLong(), reported)
            assertThrows(IllegalArgumentException::class.java) {
                exportFile(ByteArrayInputStream(byteArrayOf(1)), 1L, target) {}
            }
            assertArrayEquals(payload, Files.readAllBytes(target))
            assertEquals(1, root.listFiles()!!.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun truncatedAndInterruptedTransfersRemovePartialFiles() {
        val root = Files.createTempDirectory("export-test").toFile()
        try {
            val target = root.resolve("saved.bin").toPath()
            assertThrows(EOFException::class.java) {
                exportFile(ByteArrayInputStream(byteArrayOf(1)), 2L, target) {}
            }
            assertFalse(Files.exists(target))
            assertEquals(0, root.listFiles()!!.size)

            val interrupted = object : InputStream() {
                override fun read(): Int = throw InterruptedException("cancelled")
            }
            assertThrows(InterruptedException::class.java) {
                exportFile(interrupted, 2L, target) {}
            }
            assertFalse(Files.exists(target))
            assertEquals(0, root.listFiles()!!.size)
        } finally {
            root.deleteRecursively()
        }
    }
}
