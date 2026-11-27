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
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.util.Base64
import android.widget.EditText
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val UPDATE_REPO = "mohamed225369-cmd/Nazzelha"
private const val UPDATE_API = "https://api.github.com/repos/" + UPDATE_REPO
private const val APK_NAME = "Nazzelha-arm64.apk"
private const val MAX_APK = 250L * 1024 * 1024
private const val KEY_ALIAS = "nazzelha.update.token.v1"
private fun updateFile(context: Context) = File(File(context.cacheDir, "app-update"), APK_NAME)
private fun shaHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

private class UpdateCredentials(context: Context) {
    private val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    fun token(): String? {
        val encrypted = prefs.getString("token", null) ?: return null
        return runCatching {
            val parts = encrypted.split(":")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }
    fun save(token: String, auto: Boolean) {
        require(token.length in 15..500 && token.none { it.isWhitespace() }) { "Enter a valid read-only GitHub token." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString("token", encoded).putBoolean("auto", auto).putLong("last", 0).commit()) { "Could not save update access." }
    }
    fun clear() {
        prefs.edit().remove("token").remove("last").remove("auto").apply()
    }
    fun autoDue() = prefs.getBoolean("auto", false) && System.currentTimeMillis() - prefs.getLong("last", 0) >= 24L * 60 * 60 * 1000
    fun markChecked() { prefs.edit().putLong("last", System.currentTimeMillis()).apply() }
}

private data class UpdateRelease(val code: Long, val name: String, val notes: String, val asset: Long, val bytes: Long, val hash: String)

private object UpdateNetwork {
    private fun connection(address: String, token: String, accept: String): HttpsURLConnection {
        var url = URL(address)
        repeat(6) {
            require(url.protocol == "https" && url.port in listOf(-1, 443) && url.userInfo == null) { "Unsafe update redirect." }
            require(url.host in setOf("api.github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")) { "Untrusted update host." }
            val c = url.openConnection() as HttpsURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 25000
            c.setRequestProperty("Accept", accept)
            c.setRequestProperty("User-Agent", "Nazzelha-Android-Updater")
            if (url.host == "api.github.com") {
                c.setRequestProperty("Authorization", "Bearer " + token)
                c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            val status = c.responseCode
            if (status == 200) return c
            val location = c.getHeaderField("Location")
            c.disconnect()
            if (status in setOf(301, 302, 303, 307, 308) && location != null) {
                url = URL(url, location)
            } else {
                error(when (status) {
                    401, 403 -> "Update access denied. Check your GitHub read-only token."
                    404 -> "No accessible tested release yet, or update access is not configured."
                    else -> "Update server returned HTTP " + status
                })
            }
        }
        error("Too many update redirects.")
    }
    private fun json(address: String, token: String, accept: String): JSONObject {
        val conn = connection(address, token, accept)
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
    fun latest(token: String, current: Long): UpdateRelease? {
        val release = json(UPDATE_API + "/releases/latest", token, "application/vnd.github+json")
        check(!release.optBoolean("draft") && !release.optBoolean("prerelease")) { "Release is not a stable version." }
        val assets = release.getJSONArray("assets")
        fun asset(name: String): JSONObject {
            return (0 until assets.length()).map { assets.getJSONObject(it) }.singleOrNull { it.optString("name") == name }
                ?: error("Tested release is missing " + name)
        }
        val meta = json(UPDATE_API + "/releases/assets/" + asset("update.json").getLong("id"), token, "application/octet-stream")
        check(meta.getString("packageName") == "com.clipshelf.app") { "Incorrect update package." }
        val code = meta.getLong("versionCode")
        check(code > 0) { "Invalid update version." }
        if (code <= current) return null
        val apk = asset(APK_NAME)
        val bytes = meta.getLong("size")
        val hash = meta.getString("sha256").lowercase()
        check(bytes in 1..MAX_APK && bytes == apk.getLong("size") && hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid APK metadata." }
        val digest = apk.optString("digest", "")
        if (digest.startsWith("sha256:")) check(digest.removePrefix("sha256:").equals(hash, true)) { "Update metadata digest mismatch." }
        return UpdateRelease(code, meta.getString("versionName"), release.optString("body").take(1500), apk.getLong("id"), bytes, hash)
    }

    @Suppress("DEPRECATION")
    fun download(context: Context, release: UpdateRelease, token: String, cancelled: AtomicBoolean, progress: (Int) -> Unit): File {
        val finalFile = updateFile(context)
        check(finalFile.parentFile!!.mkdirs() || finalFile.parentFile!!.isDirectory) { "Cannot create update folder." }
        val part = File.createTempFile("download-", ".apk", finalFile.parentFile)
        val conn = connection(UPDATE_API + "/releases/assets/" + release.asset, token, "application/octet-stream")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            var last = -1
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
                        val n = input.read(buf)
                        if (n < 0) break
                        count += n
                        check(count <= release.bytes) { "APK exceeds expected size." }
                        digest.update(buf, 0, n)
                        output.write(buf, 0, n)
                        val percent = (count * 100 / release.bytes).toInt().coerceAtMost(99)
                        if (percent != last) { last = percent; progress(percent) }
                    }
                }
            }
            check(count == release.bytes && shaHex(digest.digest()) == release.hash) { "Downloaded APK failed integrity verification." }
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
            if (cancelled.get()) throw InterruptedException()
            check(part.renameTo(finalFile)) { "Could not finalize the update." }
            progress(100)
            return finalFile
        } finally { conn.disconnect(); part.delete() }
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
    private val store = UpdateCredentials(activity)
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
        if (store.autoDue() && store.token() != null) checkUpdates(false)
    }
    fun close() {
        closed = true; cancel.set(true); dialog?.dismiss(); work.shutdownNow()
    }
    fun settings() {
        if (busy) return
        val configured = store.token() != null
        AlertDialog.Builder(activity).setTitle("App updates")
            .setMessage("Installed version: " + versionLabel() + "\nUpdate channel: private GitHub releases")
            .setItems(arrayOf("Check for updates", "Configure private update access", "Open releases in browser", "Forget update access")) { _, i ->
                when (i) {
                    0 -> checkUpdates(true)
                    1 -> configure()
                    2 -> browse()
                    3 -> { store.clear(); Toast.makeText(activity, "Update access removed.", Toast.LENGTH_SHORT).show() }
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
    private fun configure() {
        val input = EditText(activity).apply {
            hint = "GitHub read-only token"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true); importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val auto = CheckBox(activity).apply { text = "Check daily when opening the app"; isChecked = true }
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, 0, p, 0); addView(input); addView(auto)
        }
        AlertDialog.Builder(activity).setTitle("Private update access")
            .setMessage("Use a fine-grained GitHub token with Contents: read-only for Nazzelha. It stays encrypted on this device. Never share it in chat.")
            .setView(wrap).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                runCatching { store.save(input.text.toString().trim(), auto.isChecked) }
                    .onSuccess { checkUpdates(true) }.onFailure { notice("Could not save update access. Check the token and try again.") }
            }.show()
    }
    fun checkUpdates(manual: Boolean) {
        if (busy || !alive()) return
        val token = store.token() ?: run { if (manual) configure(); return }
        val current = version().longVersionCode
        busy = true; store.markChecked()
        if (manual) dialog = AlertDialog.Builder(activity).setTitle("Checking for updates…")
            .setView(ProgressBar(activity)).setCancelable(false).create().apply { show() }
        work.execute {
            val result = runCatching { UpdateNetwork.latest(token, current) }
            main {
                busy = false; dialog?.dismiss(); dialog = null
                result.onSuccess { release ->
                    if (release == null) { if (manual) notice("You're using the latest version.") }
                    else AlertDialog.Builder(activity).setTitle("Update available · " + release.name)
                        .setMessage("Download: " + "%.1f".format(release.bytes / (1024.0 * 1024)) + " MB\n\n" + release.notes)
                        .setNegativeButton("Later", null).setPositiveButton("Download") { _, _ -> download(release, token) }.show()
                }.onFailure { if (manual) notice(it.message?.take(240) ?: "Could not check for updates.") }
            }
        }
    }
    private fun download(release: UpdateRelease, token: String) {
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
            val result = runCatching { UpdateNetwork.download(activity.applicationContext, release, token, signal) { p ->
                main { progress.progress = p; label.text = "Downloading update · " + p + "%" }
            } }
            main {
                busy = false; dialog?.dismiss(); dialog = null
                if (!signal.get()) result.onSuccess {
                    AlertDialog.Builder(activity).setTitle("Update verified")
                        .setMessage("Android will ask you to confirm installation.").setNegativeButton("Later", null)
                        .setPositiveButton("Install") { _, _ -> install() }.show()
                }.onFailure { notice(it.message?.take(240) ?: "Update download failed.") }
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
