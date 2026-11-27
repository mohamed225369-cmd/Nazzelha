package com.clipshelf.app

import android.content.Context
import android.system.Os
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import javax.net.ssl.HttpsURLConnection

internal data class EngineUpdate(val before: String, val after: String) {
    val changed get() = before != after
    fun description() = if (changed) "Previous version: $before\nInstalled version: $after\nUpdate verified. The engine is ready." else "Installed version: $before\nNo newer stable version available."
}

/** Official stable Python zipapp, validated before replacing the initialized binary.
 *  Engine holds an exclusive write lock for all calls that replace the binary. */
internal object EngineMaintenance {
    private const val RELEASES = "https://github.com/yt-dlp/yt-dlp/releases"
    private const val MAX_BINARY = 32L * 1024 * 1024
    private val versionPattern = Regex("\\d{4}\\.\\d{2}\\.\\d{2}(?:\\.\\d+)?")
    private val hosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")
    private fun binary(context: Context) = File(File(File(context.noBackupFilesDir, YoutubeDL.baseName), YoutubeDL.ytdlpDirName), YoutubeDL.ytdlpBin)
    private fun backup(context: Context) = File(binary(context).parentFile!!, ".nazzelha-previous")
    private fun replace(from: File, to: File) = Os.rename(from.absolutePath, to.absolutePath)
    private fun copySync(from: File, to: File) {
        FileOutputStream(to).use { output -> from.inputStream().use { it.copyTo(output) }; output.fd.sync() }
    }
    fun recover(context: Context) {
        val old = backup(context)
        if (old.isFile) replace(old, binary(context))
    }
    fun version(): String {
        val req = YoutubeDLRequest(emptyList<String>()).addOption("--ignore-config").addOption("--version")
        val result = YoutubeDL.execute(req, UUID.randomUUID().toString(), false, null)
        check(result.exitCode == 0) { "yt-dlp version check failed: " + result.err }
        return result.out.lineSequence().map(String::trim).firstOrNull { versionPattern.matches(it) }
            ?: error("The installed yt-dlp did not return a valid version.")
    }
    private fun checkCanceled(canceled: () -> Boolean) {
        if (canceled() || Thread.currentThread().isInterrupted) throw InterruptedException("Canceled")
    }
    private fun connection(url: URL): HttpsURLConnection {
        require(url.protocol == "https" && url.host in hosts && url.userInfo == null && url.port in listOf(-1,443)) { "Untrusted engine update URL." }
        return (url.openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("User-Agent", "Nazzelha-Engine-Updater")
            setRequestProperty("Accept-Encoding", "identity")
        }
    }
    private fun stableTag(): String {
        val url = URL(RELEASES + "/latest")
        val c = connection(url)
        try {
            val status = c.responseCode
            check(status in setOf(301,302,303,307,308)) { "Could not find latest stable yt-dlp (HTTP $status)." }
            val target = URL(url, c.getHeaderField("Location") ?: error("Missing release redirect."))
            check(target.protocol == "https" && target.host == "github.com" && target.port in listOf(-1,443) &&
                target.userInfo == null && target.path.startsWith("/yt-dlp/yt-dlp/releases/tag/")) { "Unexpected release location." }
            return target.path.substringAfterLast('/').also { check(versionPattern.matches(it)) { "Unexpected stable version." } }
        } finally { c.disconnect() }
    }
    private fun open(address: String, canceled: () -> Boolean): HttpsURLConnection {
        var url = URL(address)
        repeat(6) {
            checkCanceled(canceled)
            val c = connection(url)
            val status = try { c.responseCode } catch (e: Exception) { c.disconnect(); throw e }
            if (status == 200) return c
            val location = c.getHeaderField("Location")
            c.disconnect()
            if (status in setOf(301,302,303,307,308) && location != null) url = URL(url, location)
            else throw IOException("Engine update server returned HTTP $status.")
        }
        error("Too many engine update redirects.")
    }
    private fun fetch(address: String, target: File, limit: Long, canceled: () -> Boolean): String {
        val c = open(address, canceled)
        try {
            val declared = c.contentLengthLong
            check(declared <= limit) { "Engine update file too large." }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            c.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkCanceled(canceled)
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        check(total <= limit) { "Engine update file too large." }
                        digest.update(buffer, 0, n); output.write(buffer, 0, n)
                    }
                    output.fd.sync()
                }
            }
            check(total > 0 && (declared < 0 || total == declared)) { "Incomplete engine update file." }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        } finally { c.disconnect() }
    }
    private fun newer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map(String::toInt); val b = current.split('.').map(String::toInt)
        for (i in 0..3) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
    fun update(context: Context, canceled: () -> Boolean = { false }): EngineUpdate {
        recover(context)
        checkCanceled(canceled)
        val before = version()
        val tag = stableTag()
        if (!newer(tag, before)) return EngineUpdate(before, before)
        val installed = binary(context)
        check(installed.isFile) { "Cannot locate initialized yt-dlp." }
        val old = backup(context)
        val directory = installed.parentFile!!
        val staged = File.createTempFile("nazzelha-engine-", ".zip", directory)
        val sums = File.createTempFile("nazzelha-sums-", ".txt", directory)
        val previous = File.createTempFile("nazzelha-backup-", ".tmp", directory)
        try {
            val base = RELEASES + "/download/" + tag
            fetch(base + "/SHA2-256SUMS", sums, 512L * 1024, canceled)
            val matches = sums.readLines().mapNotNull { Regex("^([0-9a-fA-F]{64})\\s+\\*?yt-dlp\\s*$").matchEntire(it) }
            check(matches.size == 1) { "Official checksum for yt-dlp not found." }
            val expected = matches.single().groupValues[1].lowercase()
            check(fetch(base + "/yt-dlp", staged, MAX_BINARY, canceled) == expected) { "yt-dlp checksum verification failed." }
            ZipFile(staged).use { zip ->
                check(zip.getEntry("__main__.py") != null && zip.getEntry("yt_dlp/version.py") != null) { "Invalid yt-dlp zipapp." }
            }
            checkCanceled(canceled)
            check(!old.exists()) { "Earlier engine update still needs recovery." }
            copySync(installed, previous)
            replace(previous, old)
            try {
                replace(staged, installed)
                val after = version()
                check(after == tag) { "Updated engine returned $after instead of $tag." }
                check(old.delete()) { "Could not finalize engine update." }
                return EngineUpdate(before, after)
            } catch (e: Exception) {
                if (old.isFile) replace(old, installed)
                throw IOException("New yt-dlp did not validate; previous version restored. " + (e.message ?: ""), e)
            }
        } finally { staged.delete(); sums.delete(); previous.delete() }
    }
}
