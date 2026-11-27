package com.clipshelf.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val UPDATE_REPO = "mohamed225369-cmd/Nazzelha"
private const val RELEASE_BASE = "https://github.com/" + UPDATE_REPO + "/releases"
private const val APK_NAME = "Nazzelha-arm64.apk"
private const val MAX_APK = 250L * 1024 * 1024
private fun updateFile(context: Context) = File(File(context.cacheDir, "app-update"), APK_NAME)
private fun shaHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

private class UpdatePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    init {
        // Remove legacy private-channel credentials, if previously configured.
        if (prefs.contains("token")) {
            prefs.edit().remove("token").apply()
            runCatching { java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("nazzelha.update.token.v1") }
        }
    }
    fun automatic() = prefs.getBoolean("auto", false)
    fun toggle() { prefs.edit().putBoolean("auto", !automatic()).putLong("last", 0).apply() }
    fun autoDue() = automatic() && System.currentTimeMillis() - prefs.getLong("last", 0) >= 24L * 60 * 60 * 1000
    fun markChecked() { prefs.edit().putLong("last", System.currentTimeMillis()).apply() }
}

private class UpdateHttpError(val status: Int, message: String) : java.io.IOException(message)

private data class UpdateDelta(val asset: String, val bytes: Long, val hash: String, val baseHash: String, val baseSize: Long)
private data class UpdateRelease(val code: Long, val name: String, val notes: String, val asset: String, val bytes: Long, val hash: String, val delta: UpdateDelta? = null)
private class DeltaFallback(message: String, cause: Throwable) : java.io.IOException(message, cause)

private fun checkCanceled(cancelled: AtomicBoolean) {
    if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedException("Canceled")
}
private fun fileHash(file: File, cancelled: AtomicBoolean = AtomicBoolean(false)): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            checkCanceled(cancelled)
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    return shaHex(digest.digest())
}

private object UpdateNetwork {
    private fun connection(address: String, accept: String): HttpsURLConnection {
        var url = URL(address)
        repeat(6) {
            require(url.protocol == "https" && url.port in listOf(-1, 443) && url.userInfo == null) { "Unsafe update redirect." }
            require(url.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")) { "Untrusted update host." }
            val c = url.openConnection() as HttpsURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 25000
            c.setRequestProperty("Accept", accept)
            c.setRequestProperty("User-Agent", "Nazzelha-Android-Updater")
            val status = c.responseCode
            if (status == 200) return c
            val location = c.getHeaderField("Location")
            c.disconnect()
            if (status in setOf(301, 302, 303, 307, 308) && location != null) {
                url = URL(url, location)
            } else {
                throw UpdateHttpError(status, when (status) {
                    403, 429 -> "GitHub temporarily refused the update request. Try again later."
                    404 -> "The requested update file is not available."
                    else -> "Update server returned HTTP " + status
                })
            }
        }
        error("Too many update redirects.")
    }
    private fun json(address: String, accept: String): JSONObject {
        val conn = connection(address, accept)
        try {
            return conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    check(out.size() + n <= 1024 * 1024) { "Update metadata is too large." }
                    out.write(buf, 0, n)
                }
                JSONObject(out.toString("UTF-8"))
            }
        } finally { conn.disconnect() }
    }
    fun latest(context: Context, current: Long): UpdateRelease? {
        val meta = try { json(RELEASE_BASE + "/latest/download/update.json", "application/octet-stream") }
        catch (e: UpdateHttpError) { if (e.status == 404) return null else throw e }
        check(meta.getString("packageName") == "com.clipshelf.app") { "Incorrect update package." }
        val code = meta.getLong("versionCode")
        check(code > 0) { "Invalid update version." }
        if (code <= current) return null
        val name = meta.getString("versionName")
        check(name.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "Invalid release version." }
        val bytes = meta.getLong("size")
        val hash = meta.getString("sha256").lowercase()
        check(bytes in 1..MAX_APK && hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid APK metadata." }
        // Pin the APK to the manifest version, not the mutable latest alias.
        val address = RELEASE_BASE + "/download/v" + name + "/" + APK_NAME
        val delta = runCatching {
            // Split/repacked APKs do not have a byte-identical base; use full updates.
            val base = File(context.applicationInfo.sourceDir)
            if (!context.applicationInfo.splitSourceDirs.isNullOrEmpty() || !base.isFile) null
            else {
                val entries = meta.optJSONArray("deltas")
                val expectedName = "delta-$current-$code.nzd.gz"
                var match: UpdateDelta? = null
                if (entries != null && entries.length() <= 20) {
                    for (i in 0 until entries.length()) {
                        val d = entries.optJSONObject(i) ?: continue
                        if (d.optLong("fromVersionCode") != current || d.optString("asset") != expectedName) continue
                        val size = d.optLong("size")
                        val baseSize = d.optLong("baseSize")
                        val patchHash = d.optString("sha256").lowercase()
                        val baseHash = d.optString("baseSha256").lowercase()
                        if (size !in 1 until bytes || baseSize != base.length() ||
                            !patchHash.matches(Regex("[0-9a-f]{64}")) || !baseHash.matches(Regex("[0-9a-f]{64}"))) continue
                        if (fileHash(base) == baseHash) {
                            match = UpdateDelta(RELEASE_BASE + "/download/v" + name + "/" + expectedName, size, patchHash, baseHash, baseSize)
                            break
                        }
                    }
                }
                match
            }
        }.getOrNull()
        return UpdateRelease(code, name, meta.optString("releaseNotes", "A new tested version is available.").take(1500), address, bytes, hash, delta)
    }

    private fun fetch(address: String, bytes: Long, hash: String, target: File,
                      cancelled: AtomicBoolean, progress: (Int) -> Unit) {
        val conn = connection(address, "application/octet-stream")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            var last = -1
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        checkCanceled(cancelled)
                        val n = input.read(buf)
                        if (n < 0) break
                        count += n
                        check(count <= bytes) { "Update exceeds expected size." }
                        digest.update(buf, 0, n)
                        output.write(buf, 0, n)
                        val percent = (count * 100 / bytes).toInt().coerceAtMost(99)
                        if (percent != last) { last = percent; progress(percent) }
                    }
                }
            }
            check(count == bytes && shaHex(digest.digest()) == hash) { "Downloaded file failed integrity verification." }
        } finally { conn.disconnect() }
    }

    @Suppress("DEPRECATION")
    fun download(context: Context, release: UpdateRelease, cancelled: AtomicBoolean, progress: (Int) -> Unit): File {
        val finalFile = updateFile(context)
        val directory = finalFile.parentFile!!
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create update folder." }
        val part = File.createTempFile("download-", ".apk", directory)
        try {
            val delta = release.delta
            if (delta == null) {
                fetch(release.asset, release.bytes, release.hash, part, cancelled, progress)
            } else {
                val patch = File.createTempFile("delta-", ".gz", directory)
                try {
                    val base = File(context.applicationInfo.sourceDir)
                    check(base.length() == delta.baseSize && fileHash(base, cancelled) == delta.baseHash) { "Installed APK differs from the patch base." }
                    fetch(delta.asset, delta.bytes, delta.hash, patch, cancelled) { progress(it * 90 / 100) }
                    ApkDelta.apply(base, patch, part, release.bytes, { cancelled.get() }) { progress(90 + it * 9 / 100) }
                    check(part.length() == release.bytes && fileHash(part, cancelled) == release.hash) { "Reconstructed APK failed integrity verification." }
                } catch (e: Exception) {
                    checkCanceled(cancelled)
                    if (e is InterruptedException) throw e
                    throw DeltaFallback("Small update could not be applied. " + (e.message ?: ""), e)
                } finally { patch.delete() }
            }
            val pm = context.packageManager
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val current = pm.getPackageInfo(context.packageName, flags)
            val next = pm.getPackageArchiveInfo(part.absolutePath, flags) ?: error("Invalid APK.")
            check(next.packageName == current.packageName && next.longVersionCode == release.code &&
                next.longVersionCode > current.longVersionCode) { "APK package or version mismatch." }
            fun certs(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners?.map { shaHex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet().orEmpty()
            val installed = certs(current)
            check(installed.isNotEmpty() && installed == certs(next)) {
                "This APK has a different signing key. It cannot update the installed version."
            }
            checkCanceled(cancelled)
            check(part.renameTo(finalFile)) { "Could not finalize the update." }
            progress(100)
            return finalFile
        } finally { part.delete() }
    }
}

/** Only the verified APK in private cache can be granted to the system installer. */
class UpdateApkProvider : ContentProvider() {
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        require(uri.path == "/" + APK_NAME)
        return updateFile(context ?: error("Provider unavailable")).also { check(it.isFile) }
    }
    override fun getType(uri: Uri) = "application/vnd.android.package-archive"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = file(uri)
        val cols = projection?.map { it }?.toTypedArray() ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply { addRow(cols.map { when (it) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}

class AppUpdateUi(private val activity: Activity) {
    private val store = UpdatePreferences(activity)
    private val work = Executors.newSingleThreadExecutor()
    private var busy = false
    private var closed = false
    private var cancel = AtomicBoolean(false)
    private var dialog: AlertDialog? = null
    private fun alive() = !closed && !activity.isFinishing && !activity.isDestroyed
    private fun main(action: () -> Unit) = activity.runOnUiThread { if (alive()) action() }
    @Suppress("DEPRECATION")
    private fun version() = activity.packageManager.getPackageInfo(activity.packageName, 0)
    fun versionLabel() = version().versionName ?: "Unknown"
    fun autoCheck() {
        if (store.autoDue()) checkUpdates(false)
    }
    fun close() {
        closed = true; cancel.set(true); dialog?.dismiss(); work.shutdownNow()
    }
    fun settings() {
        if (busy) { notice("An update check or download is already in progress."); return }
        AlertDialog.Builder(activity).setTitle("App updates · " + versionLabel())
            .setItems(arrayOf("Check for updates", "Automatic checks · " + if (store.automatic()) "On" else "Off", "Open releases in browser")) { _, i ->
                when (i) {
                    0 -> checkUpdates(true)
                    1 -> { store.toggle(); settings() }
                    2 -> browse()
                }
            }.setNegativeButton("Close", null).show()
    }
    private fun browse() {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/" + UPDATE_REPO + "/releases"))) }
            .onFailure { notice("No browser available.") }
    }
    private fun notice(text: String) {
        if (alive()) AlertDialog.Builder(activity).setTitle("App updates").setMessage(text).setPositiveButton("OK", null).show()
    }
    fun checkUpdates(manual: Boolean) {
        if (busy || !alive()) return
        val current = version().longVersionCode
        busy = true; store.markChecked()
        if (manual) dialog = AlertDialog.Builder(activity).setTitle("Checking for updates…")
            .setView(ProgressBar(activity)).setCancelable(false).create().apply { show() }
        work.execute {
            val result = runCatching { UpdateNetwork.latest(activity.applicationContext, current) }
            main {
                busy = false; dialog?.dismiss(); dialog = null
                result.onSuccess { release ->
                    if (release == null) { if (manual) notice("No newer published version is available.") }
                    else AlertDialog.Builder(activity).setTitle("Update available · " + release.name)
                        .setMessage(if (release.delta == null) "Full update: " + sizeLabel(release.bytes) + "\n\n" + release.notes
                            else "Small update: " + sizeLabel(release.delta.bytes) + " (full APK: " + sizeLabel(release.bytes) + ")\nOnly changed data is downloaded.\n\n" + release.notes)
                        .setNegativeButton("Later", null).setPositiveButton("Download") { _, _ -> download(release) }.show()
                }.onFailure { if (manual) notice(it.message?.take(240) ?: "Could not check for updates.") }
            }
        }
    }
    private fun sizeLabel(bytes: Long) = if (bytes < 1024 * 1024) "%.0f KB".format(bytes / 1024.0) else "%.1f MB".format(bytes / (1024.0 * 1024))
    private fun download(release: UpdateRelease) {
        if (busy || !alive()) return
        busy = true; cancel = AtomicBoolean(false)
        val signal = cancel
        val label = TextView(activity).apply { text = "Downloading update · 0%" }
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = false }
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p); addView(label); addView(progress)
        }
        dialog = AlertDialog.Builder(activity).setTitle("Downloading " + release.name).setView(view)
            .setCancelable(false).setNegativeButton("Cancel") { _, _ -> signal.set(true) }.create().apply { show() }
        work.execute {
            val result = runCatching { UpdateNetwork.download(activity.applicationContext, release, signal) { p ->
                main { progress.progress = p; label.text = (if (release.delta != null && p >= 90) "Applying update · " else "Downloading update · ") + p + "%" }
            } }
            main {
                busy = false; dialog?.dismiss(); dialog = null
                if (!signal.get()) result.onSuccess {
                    AlertDialog.Builder(activity).setTitle("Update verified")
                        .setMessage("Android will ask you to confirm installation.").setNegativeButton("Later", null)
                        .setPositiveButton("Install") { _, _ -> install() }.show()
                }.onFailure {
                    if (it is DeltaFallback) AlertDialog.Builder(activity).setTitle("Full update needed")
                        .setMessage("The small update could not be applied. Download the full APK (" + sizeLabel(release.bytes) + ") instead?")
                        .setNegativeButton("Later", null)
                        .setPositiveButton("Download full APK") { _, _ -> download(release.copy(delta = null)) }.show()
                    else notice(it.message?.take(240) ?: "Update download failed.")
                }
            }
        }
    }
    private fun install() {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity).setTitle("Allow app updates")
                .setMessage("Allow Nazzelha to install updates in Android settings, then return and tap Install.")
                .setNegativeButton("Cancel", null).setPositiveButton("Open settings") { _, _ ->
                    runCatching {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName)))
                        AlertDialog.Builder(activity).setTitle("Install verified update").setMessage("After granting permission, tap Install.")
                            .setNegativeButton("Later", null).setPositiveButton("Install") { _, _ -> install() }.show()
                    }.onFailure { notice("Could not open Android install settings.") }
                }.show()
            return
        }
        val uri = Uri.parse("content://" + activity.packageName + ".updates/" + APK_NAME)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Nazzelha update", uri) }
        runCatching { activity.startActivity(intent) }.onFailure { notice("Could not open Android installer.") }
    }
}
