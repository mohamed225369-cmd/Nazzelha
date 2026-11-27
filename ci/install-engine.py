#!/usr/bin/env python3
from pathlib import Path
import sys
project = Path(sys.argv[1])
here = Path(__file__).resolve().parent
java = project / "app/src/main/java/com/clipshelf/app"
for name in ("EngineRecoveryPolicy.kt", "EngineMaintenance.kt"):
    (java / name).write_text((here / "overlays" / name).read_text(), encoding="utf-8")
def replace(path, old, new):
    text = path.read_text()
    if text.count(old) != 1:
        raise SystemExit(f"Expected one match in {path}: {old[:100]}")
    path.write_text(text.replace(old, new, 1))
engine = java / "Engine.kt"
replace(engine, "import java.util.UUID", """import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.TimeUnit
import com.yausername.youtubedl_android.YoutubeDLException
import android.os.SystemClock""")
replace(engine, "    @Volatile private var initialized = false", """    @Volatile private var initialized = false
    private val engineLock = ReentrantReadWriteLock(true)
    private var generation = 0L
    private var lastAutomaticCheck: Long? = null""")
replace(engine, "        YoutubeDL.init(context.applicationContext)", """        EngineMaintenance.recover(context.applicationContext)
        YoutubeDL.init(context.applicationContext)""")
replace(engine, """    fun update(context: Context): String {
        init(context)
        val result = YoutubeDL.updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
        return "${result ?: "Updated"} · ${YoutubeDL.version(context) ?: "yt-dlp"}"
    }""", """    private fun <T> locked(write: Boolean, canceled: () -> Boolean = { false }, block: () -> T): T {
        val lock = if (write) engineLock.writeLock() else engineLock.readLock()
        while (!lock.tryLock(200, TimeUnit.MILLISECONDS)) {
            if (canceled()) throw InterruptedException("Canceled")
        }
        try {
            if (canceled() || Thread.currentThread().isInterrupted) throw InterruptedException("Canceled")
            return block()
        } finally { lock.unlock() }
    }
    fun version(context: Context): String {
        init(context)
        return locked(false) { EngineMaintenance.version() }
    }
    fun update(context: Context): String {
        init(context)
        return locked(true) {
            val result = EngineMaintenance.update(context.applicationContext)
            if (result.changed) generation++
            result.description()
        }
    }
    private fun <T> command(block: () -> T): T {
        try { return block() }
        catch (e: YoutubeDLException) { throw EngineCommandFailure(e.message ?: "yt-dlp command failed", e) }
    }
    private fun <T> recovering(context: Context, canceled: () -> Boolean = { Thread.currentThread().isInterrupted },
                               status: (String) -> Unit = {}, operation: () -> T): T {
        init(context)
        var observed = -1L
        return EngineRecoveryPolicy.once(canceled, refresh = {
            status("Checking for yt-dlp update…")
            locked(true, canceled) {
                if (generation != observed) true
                else {
                    val now = SystemClock.elapsedRealtime()
                    if (lastAutomaticCheck?.let { now - it < 300_000L } == true) false
                    else {
                        lastAutomaticCheck = now
                        status("Updating yt-dlp…")
                        val result = EngineMaintenance.update(context.applicationContext, canceled)
                        if (result.changed) generation++
                        result.changed
                    }
                }
            }
        }, retrying = { status("Engine updated · retrying once…") }) {
            locked(false, canceled) {
                observed = generation
                operation()
            }
        }
    }""")
replace(engine, """    private fun json(request: YoutubeDLRequest, processId: String = UUID.randomUUID().toString()): JSONObject {
        request.addOption("--dump-single-json"); request.addOption("--skip-download")
        val r = YoutubeDL.execute(request, processId, false, null)
        if (r.exitCode != 0) error(r.err.ifBlank { "Metadata command failed" })
        return JSONObject(r.out.trim())
    }""", """    private fun json(context: Context, processId: String = UUID.randomUUID().toString(), build: () -> YoutubeDLRequest): JSONObject =
        recovering(context) {
            val request = build().addOption("--dump-single-json").addOption("--skip-download")
            val r = command { YoutubeDL.execute(request, processId, false, null) }
            if (r.exitCode != 0) throw EngineCommandFailure(r.err.ifBlank { "Metadata command failed" })
            JSONObject(r.out.trim())
        }""")
replace(engine, """        val req = request(url)
        if (playlist) { req.addOption("--yes-playlist"); req.addOption("--flat-playlist"); req.addOption("--ignore-errors") }
        else req.addOption("--no-playlist")
        val root = json(req)""", """        val root = json(context) {
            request(url).apply {
                if (playlist) { addOption("--yes-playlist"); addOption("--flat-playlist"); addOption("--ignore-errors") }
                else addOption("--no-playlist")
            }
        }""")
replace(engine, """        val req = request(clip.url).addOption("--no-playlist")
        val info = parseClip(json(req, processId), clip.index, clip.url)""", """        val info = parseClip(json(context, processId) { request(clip.url).addOption("--no-playlist") }, clip.index, clip.url)""")
replace(engine, """    fun download(context: Context, job: Job, processId: String, canceled: () -> Boolean, progress: (TransferUpdate) -> Unit): Uri {
        init(context)""", """    fun download(context: Context, job: Job, processId: String, canceled: () -> Boolean, progress: (TransferUpdate) -> Unit): Uri =
        recovering(context, canceled, { status -> progress(TransferUpdate(Phase.PROCESSING, null, status)) }) {
            downloadOnce(context, job, processId, canceled, progress)
        }

    private fun downloadOnce(context: Context, job: Job, processId: String, canceled: () -> Boolean, progress: (TransferUpdate) -> Unit): Uri {""")
replace(engine, """            val result = YoutubeDL.execute(req, processId, false) { p, eta, line ->
                if (canceled()) YoutubeDL.destroyProcessById(processId)
                TransferProgress.fromCallback(p, eta.toLong(), line)?.let(progress)
            }
            if (canceled()) throw InterruptedException("Canceled")
            check(result.exitCode == 0) { result.err.ifBlank { "Download failed" } }""", """            val result = command {
                YoutubeDL.execute(req, processId, false) { p, eta, line ->
                    if (canceled()) YoutubeDL.destroyProcessById(processId)
                    TransferProgress.fromCallback(p, eta.toLong(), line)?.let(progress)
                }
            }
            if (canceled()) throw InterruptedException("Canceled")
            if (result.exitCode != 0) throw EngineCommandFailure(result.err.ifBlank { "Download failed" })""")
main = java / "MainActivity.kt"
replace(main, """        task("Updating yt-dlp…", { Engine.update(applicationContext) }) { message(it) }""", """        task("Checking yt-dlp…", { Engine.version(applicationContext) }) { current ->
            AlertDialog.Builder(this).setTitle("Engine update")
                .setMessage("Installed yt-dlp: " + current + "\\nAutomatic recovery retries compatible engine failures once.")
                .setNegativeButton("Close", null)
                .setPositiveButton("Update now") { _, _ ->
                    if (QueueStore.running || activeMetadata.isNotEmpty()) message("Finish current downloads or quality loading first.")
                    else task("Updating yt-dlp…", { Engine.update(applicationContext) }) { result ->
                        AlertDialog.Builder(this).setTitle("Engine update").setMessage(result).setPositiveButton("OK", null).show()
                    }
                }.show()
        }""")
replace(main, "label(state.message, 12, color).apply", "label(if (state.phase == Phase.FAILED) EngineRecoveryPolicy.summary(state.message) else state.message, 12, color).apply")
replace(main, """            if (state.phase == Phase.FAILED) dynamic.addView(button("Error details") { errorDialog(state.message) }, lp(-1, -2, 4))""", """            if (state.phase == Phase.FAILED) {
                dynamic.addView(button("Error details") { errorDialog(state.message) }, lp(-1, -2, 4))
                if (!QueueStore.running) dynamic.addView(button("Update yt-dlp") { updateEngine() }, lp(-1, -2, 4))
            }""")
service = java / "DownloadService.kt"
replace(service, 'val changed = update.phase != lastPhase || indeterminate != lastIndeterminate', 'val changed = update.phase != lastPhase || indeterminate != lastIndeterminate || update.message != lastMessage')
replace(service, '                var lastPhase: Phase? = null', '                var lastPhase: Phase? = null\n                var lastMessage: String? = null')
replace(service, 'lastProgress = now; lastPhase = update.phase; lastIndeterminate = indeterminate', 'lastProgress = now; lastPhase = update.phase; lastIndeterminate = indeterminate; lastMessage = update.message')
replace(service, '(e.message ?: "Download failed").takeLast(1800)', '(e.message ?: "Download failed").takeLast(4000)')
test = project / "app/src/test/java/com/clipshelf/app"
test.mkdir(parents=True, exist_ok=True)
(test / "EngineRecoveryPolicyTest.kt").write_text((here / "tests/EngineRecoveryPolicyTest.kt").read_text(), encoding="utf-8")
gradle = project / "app/build.gradle.kts"
replace(gradle, "dependencies {", 'dependencies {\n    testImplementation("junit:junit:4.13.2")')
print("Installed engine updater and one-retry recovery")
