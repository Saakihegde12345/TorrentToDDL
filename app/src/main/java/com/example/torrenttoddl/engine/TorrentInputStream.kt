package com.example.torrenttoddl.engine

import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentHandle
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import kotlin.math.min

class TorrentInputStream(
    private val torrentHandle: TorrentHandle,
    private val targetFile: File,
    private val fileOffsetInTorrent: Long,
    private val fileLength: Long,
    private val pieceLength: Int,
    initialByteOffset: Long = 0L,
    endByteOffset: Long = fileLength
) : InputStream() {

    private var currentOffsetInFile: Long = initialByteOffset
    private val readableEndOffset = endByteOffset.coerceIn(0L, fileLength.coerceAtLeast(0L))

    @Volatile
    private var raf: RandomAccessFile? = null

    @Volatile
    private var closed = false

    init {
        if (fileLength > 0L && pieceLength > 0 && currentOffsetInFile in 0L..fileLength) {
            prioritizeWindow(currentOffsetInFile)
        }
    }

    private fun prioritizeWindow(byteOffsetInFile: Long) {
        if (closed || fileLength <= 0L || pieceLength <= 0 || byteOffsetInFile !in 0L..fileLength) return

        val globalByteOffset = addOffsets(fileOffsetInTorrent, byteOffsetInFile) ?: return
        val lastByteInFile = addOffsets(fileOffsetInTorrent, fileLength - 1L) ?: return
        val startPieceLong = globalByteOffset / pieceLength
        val endPieceLong = lastByteInFile / pieceLength
        if (startPieceLong > Int.MAX_VALUE || endPieceLong > Int.MAX_VALUE) return

        val startPiece = startPieceLong.toInt()
        val endPiece = endPieceLong.toInt()

        val lookAhead = min(startPieceLong + 8L, endPieceLong).toInt()
        for (piece in startPiece..lookAhead) {
            try {
                if (!torrentHandle.isValid) return
                if (!torrentHandle.havePiece(piece)) {
                    torrentHandle.piecePriority(piece, Priority.TOP_PRIORITY)
                    val deadlineMs = 500 + ((piece - startPiece) * 250)
                    torrentHandle.setPieceDeadline(piece, deadlineMs)
                }
            } catch (_: Exception) {
                return
            }
        }
    }

    private fun waitForPieceAt(byteOffsetInFile: Long): Boolean {
        if (closed || fileLength <= 0L || pieceLength <= 0 || byteOffsetInFile !in 0L until fileLength) {
            return false
        }

        val globalOffset = addOffsets(fileOffsetInTorrent, byteOffsetInFile) ?: return false
        val pieceIndexLong = globalOffset / pieceLength
        if (pieceIndexLong > Int.MAX_VALUE) return false
        val pieceIndex = pieceIndexLong.toInt()

        if (!hasPiece(pieceIndex)) {
            prioritizeWindow(byteOffsetInFile)
        }

        while (!closed && isHandleValid() && !hasPiece(pieceIndex)) {
            if (!sleepBriefly(150L)) return false
        }
        return !closed && isHandleValid() && hasPiece(pieceIndex)
    }

    private fun ensureRandomAccessFile(): RandomAccessFile? {
        raf?.let { return it }

        while (!closed && isHandleValid() && !targetFile.exists()) {
            if (!sleepBriefly(100L)) return null
        }
        if (closed || !isHandleValid() || !targetFile.exists()) return null

        synchronized(this) {
            if (closed || !isHandleValid()) return null
            raf?.let { return it }
            return try {
                RandomAccessFile(targetFile, "r").also { raf = it }
            } catch (_: IOException) {
                null
            }
        }
    }

    override fun read(): Int {
        val singleByte = ByteArray(1)
        val bytesRead = read(singleByte, 0, 1)
        return if (bytesRead <= 0) -1 else (singleByte[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || off > b.size || len > b.size - off) {
            throw IndexOutOfBoundsException("Invalid offset/length: off=$off len=$len size=${b.size}")
        }
        if (len == 0) return 0
        if (closed || currentOffsetInFile < 0L || currentOffsetInFile >= readableEndOffset) return -1
        if (fileLength <= 0L || pieceLength <= 0) return -1

        if (!waitForPieceAt(currentOffsetInFile)) {
            return -1
        }

        val globalOffset = addOffsets(fileOffsetInTorrent, currentOffsetInFile) ?: return -1
        val bytesRemainingInPiece = pieceLength - (globalOffset % pieceLength)
        val bytesRemainingInFile = readableEndOffset - currentOffsetInFile
        val maxReadableNow = min(len.toLong(), min(bytesRemainingInPiece, bytesRemainingInFile)).toInt()
        if (maxReadableNow <= 0) return -1

        val randomAccess = ensureRandomAccessFile() ?: return -1
        val actualRead = try {
            randomAccess.seek(currentOffsetInFile)
            randomAccess.read(b, off, maxReadableNow)
        } catch (_: IOException) {
            -1
        }

        if (actualRead == 0) return -1

        if (actualRead > 0) {
            val previousPiece = (globalOffset / pieceLength).toInt()
            currentOffsetInFile += actualRead
            val newGlobalOffset = addOffsets(fileOffsetInTorrent, currentOffsetInFile)
            val newPiece = if (newGlobalOffset == null) {
                previousPiece
            } else {
                (newGlobalOffset / pieceLength).toInt()
            }
            if (newPiece > previousPiece) {
                prioritizeWindow(currentOffsetInFile)
            }
        }
        return actualRead
    }

    override fun close() {
        val fileToClose: RandomAccessFile?
        synchronized(this) {
            if (closed) return
            closed = true
            fileToClose = raf
            raf = null
        }
        try {
            fileToClose?.close()
        } catch (_: IOException) {
        }
        super.close()
    }

    private fun isHandleValid(): Boolean = try {
        torrentHandle.isValid
    } catch (_: Exception) {
        false
    }

    private fun hasPiece(pieceIndex: Int): Boolean = try {
        isHandleValid() && torrentHandle.havePiece(pieceIndex)
    } catch (_: Exception) {
        false
    }

    private fun sleepBriefly(millis: Long): Boolean = try {
        Thread.sleep(millis)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private fun addOffsets(first: Long, second: Long): Long? {
        if (first < 0L || second < 0L || first > Long.MAX_VALUE - second) return null
        return first + second
    }
}
