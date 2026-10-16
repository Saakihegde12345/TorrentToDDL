package com.example.torrenttoddl.engine

import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentHandle
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import kotlin.math.min

class TorrentInputStream(
    private val torrentHandle: TorrentHandle,
    private val targetFile: File,
    private val fileOffsetInTorrent: Long,
    private val fileLength: Long,
    private val pieceLength: Int,
    initialByteOffset: Long = 0L
) : InputStream() {

    private var currentOffsetInFile: Long = initialByteOffset
    private var raf: RandomAccessFile? = null
    @Volatile
    private var closed = false

    init {
        prioritizeWindow(currentOffsetInFile)
    }

    private fun prioritizeWindow(byteOffsetInFile: Long) {
        if (!torrentHandle.isValid) return
        val globalByteOffset = fileOffsetInTorrent + byteOffsetInFile
        val startPiece = (globalByteOffset / pieceLength).toInt()
        val endPiece = ((fileOffsetInTorrent + fileLength - 1) / pieceLength).toInt()

        val lookAhead = min(startPiece + 8, endPiece)
        for (piece in startPiece..lookAhead) {
            if (!torrentHandle.havePiece(piece)) {
                torrentHandle.piecePriority(piece, Priority.TOP_PRIORITY)
                val deadlineMs = 500 + ((piece - startPiece) * 250)
                torrentHandle.setPieceDeadline(piece, deadlineMs)
            }
        }
    }

    private fun waitForPieceAt(byteOffsetInFile: Long): Boolean {
        val globalOffset = fileOffsetInTorrent + byteOffsetInFile
        val pieceIndex = (globalOffset / pieceLength).toInt()

        if (!torrentHandle.havePiece(pieceIndex)) {
            prioritizeWindow(byteOffsetInFile)
        }

        while (!closed && torrentHandle.isValid && !torrentHandle.havePiece(pieceIndex)) {
            Thread.sleep(150)
        }
        return !closed && torrentHandle.isValid && torrentHandle.havePiece(pieceIndex)
    }

    private fun ensureRandomAccessFile(): RandomAccessFile {
        if (raf == null) {
            while (!closed && !targetFile.exists()) {
                Thread.sleep(100)
            }
            raf = RandomAccessFile(targetFile, "r")
        }
        return raf!!
    }

    override fun read(): Int {
        val singleByte = ByteArray(1)
        val bytesRead = read(singleByte, 0, 1)
        return if (bytesRead == -1) -1 else (singleByte[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed || currentOffsetInFile >= fileLength) return -1

        if (!waitForPieceAt(currentOffsetInFile)) {
            return -1
        }

        val globalOffset = fileOffsetInTorrent + currentOffsetInFile
        val bytesRemainingInPiece = pieceLength - (globalOffset % pieceLength)
        val bytesRemainingInFile = fileLength - currentOffsetInFile
        val maxReadableNow = min(len.toLong(), min(bytesRemainingInPiece, bytesRemainingInFile)).toInt()

        val randomAccess = ensureRandomAccessFile()
        randomAccess.seek(currentOffsetInFile)
        val actualRead = randomAccess.read(b, off, maxReadableNow)

        if (actualRead > 0) {
            val previousPiece = ((fileOffsetInTorrent + currentOffsetInFile) / pieceLength).toInt()
            currentOffsetInFile += actualRead
            val newPiece = ((fileOffsetInTorrent + currentOffsetInFile) / pieceLength).toInt()
            if (newPiece > previousPiece) {
                prioritizeWindow(currentOffsetInFile)
            }
        }
        return actualRead
    }

    override fun close() {
        closed = true
        try {
            raf?.close()
        } catch (_: Exception) {
        }
        super.close()
    }
}
