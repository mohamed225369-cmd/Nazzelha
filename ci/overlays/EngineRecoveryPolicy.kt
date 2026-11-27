package com.clipshelf.app

import java.util.Locale

internal class EngineCommandFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

internal object EngineRecoveryPolicy {
    fun shouldUpdate(message: String): Boolean {
        val m = message.lowercase(Locale.ROOT)
        // A stale-version WARNING alone is not a reason to replace the engine.
        val failure = m.lineSequence().filter { !it.trimStart().startsWith("warning:") }.joinToString("\n")
        val permanent = listOf("private video", "video is private", "video unavailable", "video is unavailable",
            "has been removed", "not available in your country", "sign in to confirm", "login required",
            "members-only", "age-restricted", "copyright", "drm", "no space left", "permission denied",
            "ffmpeg not found", "ffprobe and ffmpeg not found", "requested format is not available",
            "http error 429", "http error 404", "http error 401", "timed out", "network is unreachable",
            "unable to resolve host", "temporary failure in name resolution", "certificate verify failed")
        if (permanent.any { it in failure }) return false
        val extractorFailure = listOf("signature extraction failed", "nsig extraction failed",
            "signature decipher", "n challenge solving failed", "signature solving failed",
            "unable to extract", "unsupported url", "unknown option", "no such option",
            "no supported javascript runtime", "unable to solve n challenge", "challenge solver failed")
        // Some yt-dlp versions emit the extraction failure as a WARNING, then fail the command.
        // Require an actual non-warning error as well.
        val actualError = failure.lineSequence().any { it.trimStart().startsWith("error:") || it.contains("traceback (most recent call last)") }
        if (actualError && extractorFailure.any { it in m }) return true
        if ("yt-dlp" in failure && listOf("update to the latest", "outdated version", "version is too old", "please update").any { it in failure }) return true
        val old = "older than 90 days" in m || "yt-dlp version is outdated" in m
        return old && actualError && ("unable to download video data" in failure && "http error 403" in failure)
    }

    fun summary(message: String): String {
        val lines = message.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        return (lines.lastOrNull { it.startsWith("ERROR:", true) }
            ?: lines.lastOrNull { !it.startsWith("WARNING:", true) && !it.startsWith("It is strongly recommended", true) }
            ?: lines.lastOrNull() ?: "Download failed").take(280)
    }

    fun <T> once(
        canceled: () -> Boolean,
        refresh: () -> Boolean,
        retrying: () -> Unit,
        operation: () -> T
    ): T {
        if (canceled()) throw InterruptedException("Canceled")
        try { return operation() }
        catch (first: EngineCommandFailure) {
            if (canceled()) throw InterruptedException("Canceled")
            if (!shouldUpdate(first.message.orEmpty())) throw first
            val updated = try { refresh() }
            catch (e: Exception) {
                if (canceled() || e is InterruptedException) throw InterruptedException("Canceled")
                throw EngineCommandFailure(first.message.orEmpty() + "\n\nEngine update failed: " + (e.message ?: e.javaClass.simpleName), first)
            }
            if (canceled()) throw InterruptedException("Canceled")
            if (!updated) throw EngineCommandFailure(first.message.orEmpty() + "\n\nNo newer engine installed (already current or checked recently).", first)
            retrying()
            // Deliberately not recursive: one retry only.
            try { return operation() }
            catch (second: EngineCommandFailure) {
                throw EngineCommandFailure("Original failure:\n" + first.message.orEmpty() + "\n\nAfter engine update, retry failed:\n" + second.message.orEmpty(), second)
            }
        }
    }
}
