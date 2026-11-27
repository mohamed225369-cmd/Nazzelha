package com.clipshelf.app

import org.junit.Assert.*
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class ApkDeltaTest {
    private fun fixture(block: (File, File, File) -> Unit) {
        val dir = Files.createTempDirectory("apk-delta-test").toFile()
        try {
            val base = File(dir, "base").apply { writeText("0123456789") }
            block(base, File(dir, "patch.gz"), File(dir, "out"))
        } finally { dir.deleteRecursively() }
    }
    private fun patch(file: File, body: DataOutputStream.() -> Unit) {
        DataOutputStream(GZIPOutputStream(file.outputStream())).use { it.writeInt(0x4e5a4431); it.body() }
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected invalid patch to fail") } catch (_: Exception) {}
    }
    @Test fun copiesAndLiterals() = fixture { base, p, out ->
        patch(p) { writeByte(1); writeLong(2); writeInt(4); writeByte(2); writeInt(3); writeBytes("xyz"); writeByte(0) }
        ApkDelta.apply(base,p,out,7)
        assertEquals("2345xyz",out.readText())
    }
    @Test fun rejectsSourceOverflow() = fixture { base,p,out ->
        patch(p) { writeByte(1); writeLong(9); writeInt(4); writeByte(0) }
        rejected { ApkDelta.apply(base,p,out,4) }; assertFalse(out.exists())
    }
    @Test fun rejectsOutputOverflow() = fixture { base,p,out ->
        patch(p) { writeByte(2); writeInt(4); writeBytes("abcd"); writeByte(0) }
        rejected { ApkDelta.apply(base,p,out,3) }
    }
    @Test fun rejectsTrailingBytes() = fixture { base,p,out ->
        patch(p) { writeByte(2); writeInt(1); writeBytes("a"); writeByte(0); writeByte(1) }
        rejected { ApkDelta.apply(base,p,out,1) }
    }
    @Test fun rejectsTruncatedLiteral() = fixture { base,p,out ->
        patch(p) { writeByte(2); writeInt(3); writeBytes("a") }
        rejected { ApkDelta.apply(base,p,out,3) }
    }
    @Test fun cancellationDeletesOutput() = fixture { base,p,out ->
        patch(p) { writeByte(1); writeLong(0); writeInt(10); writeByte(0) }
        rejected { ApkDelta.apply(base,p,out,10,{ true }) }; assertFalse(out.exists())
    }
    @Test fun realSignedReleasePairs() {
        val path = System.getenv("NAZZELHA_DELTA_CASES") ?: return
        File(path).readLines().filter { it.isNotBlank() }.forEach { row ->
            val (base, patch, target, size, hash) = row.split('\t')
            val out = File.createTempFile("nazzelha-reconstructed-", ".apk")
            try {
                ApkDelta.apply(File(base), File(patch), out, size.toLong())
                val digest = MessageDigest.getInstance("SHA-256")
                out.inputStream().use { input ->
                    val buf = ByteArray(65536)
                    while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf,0,n) }
                }
                assertEquals(hash,digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
                assertEquals(File(target).length(),out.length())
            } finally { out.delete() }
        }
    }
}
