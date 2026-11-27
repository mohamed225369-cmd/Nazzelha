package com.clipshelf.app

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream

/** NZD1 is a bounded, streamed copy/literal patch against an exact signed APK. */
internal object ApkDelta {
    private const val MAGIC = 0x4e5a4431
    private const val MAX_SIZE = 250L * 1024 * 1024
    fun apply(base: File, patch: File, output: File, expectedSize: Long,
              canceled: () -> Boolean = { false }, progress: (Int) -> Unit = {}) {
        require(expectedSize in 1..MAX_SIZE)
        require(base.canonicalFile != output.canonicalFile && patch.canonicalFile != output.canonicalFile)
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        var operations = 0
        fun checkCanceled() {
            if (canceled() || Thread.currentThread().isInterrupted) throw InterruptedException("Canceled")
        }
        try {
            DataInputStream(BufferedInputStream(GZIPInputStream(patch.inputStream()))).use { input ->
                check(input.readInt() == MAGIC) { "Unsupported APK patch." }
                RandomAccessFile(base, "r").use { old ->
                    FileOutputStream(output).use { out ->
                        while (true) {
                            checkCanceled()
                            val type = input.readUnsignedByte()
                            if (type == 0) break
                            check(++operations <= 100_000 && type in 1..2) { "Invalid patch operation." }
                            val offset = if (type == 1) input.readLong() else 0L
                            val length = input.readInt()
                            check(length > 0 && length.toLong() <= expectedSize - written) { "Patch output exceeds expected size." }
                            if (type == 1) {
                                check(offset >= 0 && length.toLong() <= old.length() && offset <= old.length() - length) { "Invalid patch source range." }
                                old.seek(offset)
                            }
                            var left = length
                            while (left > 0) {
                                checkCanceled()
                                val n = minOf(left, buffer.size)
                                if (type == 1) old.readFully(buffer, 0, n) else input.readFully(buffer, 0, n)
                                out.write(buffer, 0, n)
                                written += n
                                left -= n
                            }
                            progress((written * 100 / expectedSize).toInt())
                        }
                        check(written == expectedSize && input.read() == -1) { "Incomplete or trailing patch data." }
                        out.fd.sync()
                    }
                }
            }
        } catch (e: Exception) {
            output.delete()
            throw e
        }
    }
}
