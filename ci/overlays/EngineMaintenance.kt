package com.clipshelf.app

import android.content.Context
import android.system.Os
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID

internal data class EngineUpdate(val before: String, val after: String) {
    val changed get() = before != after
    fun description() = if (changed) "Previous version: $before\nInstalled version: $after\nUpdate verified. The engine is ready." else "Installed version: $before\nNo newer stable version available."
}

/** Delegate release resolution, download and checksum validation to yt-dlp itself.
 * Engine serializes updates against all active metadata/download commands. */
internal object EngineMaintenance {
    private val versionPattern = Regex("\\d{4}\\.\\d{2}\\.\\d{2}(?:\\.\\d+)?")
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
    private fun checkCanceled(canceled: () -> Boolean) {
        if (canceled() || Thread.currentThread().isInterrupted) throw InterruptedException("Canceled")
    }
    fun version(): String {
        val req = YoutubeDLRequest(emptyList<String>()).addOption("--ignore-config").addOption("--version")
        val result = YoutubeDL.execute(req, UUID.randomUUID().toString(), false, null)
        check(result.exitCode == 0) { "yt-dlp version check failed: " + result.err }
        return result.out.lineSequence().map(String::trim).firstOrNull { versionPattern.matches(it) }
            ?: error("The installed yt-dlp did not return a valid version.")
    }
    fun update(context: Context, canceled: () -> Boolean = { false }): EngineUpdate {
        recover(context)
        checkCanceled(canceled)
        val before = version()
        val installed = binary(context)
        check(installed.isFile) { "Cannot locate initialized yt-dlp." }
        val old = backup(context)
        val previous = File.createTempFile("nazzelha-backup-", ".tmp", installed.parentFile!!)
        try {
            copySync(installed, previous)
            replace(previous, old)
            try {
                checkCanceled(canceled)
                // Built-in CLI update, pinned to the official stable channel.
                val req = YoutubeDLRequest(emptyList<String>())
                    .addOption("--ignore-config")
                    .addOption("--socket-timeout", "20")
                    .addOption("--update-to", "stable")
                val id = UUID.randomUUID().toString()
                val result = YoutubeDL.execute(req, id, false) { _, _, _ ->
                    if (canceled()) YoutubeDL.destroyProcessById(id)
                }
                checkCanceled(canceled)
                val log = result.out + "\n" + result.err
                check(result.exitCode == 0) { log.ifBlank { "yt-dlp update command failed." } }
                val lower = log.lowercase(Locale.ROOT)
                check(listOf("skipping verification", "unverified builds", "no hash information found",
                    "hash could not be found", "unable to verify", "error:", "traceback (most recent call last)")
                    .none { it in lower }) { log.ifBlank { "Engine update did not verify." } }
                val after = version()
                check(old.delete()) { "Could not finalize engine update." }
                return EngineUpdate(before, after)
            } catch (e: Exception) {
                if (old.isFile) replace(old, installed)
                if (e is InterruptedException || canceled()) throw InterruptedException("Canceled")
                throw IOException("yt-dlp update failed; previous version restored. " + (e.message ?: ""), e)
            }
        } finally { previous.delete() }
    }
}
