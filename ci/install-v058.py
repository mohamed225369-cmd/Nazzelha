#!/usr/bin/env python3
from pathlib import Path
import sys
project=Path(sys.argv[1])
here=Path(__file__).resolve().parent
java=project/"app/src/main/java/com/clipshelf/app"
test=project/"app/src/test/java/com/clipshelf/app"
(java/"FormatCache.kt").write_text((here/"overlays/FormatCache.kt").read_text())
(test/"FormatCacheTest.kt").write_text((here/"tests/FormatCacheTest.kt").read_text())
def edit(s,a,b):
    if s.count(a)!=1: raise SystemExit("Expected one v0.5.8 source anchor: "+a[:130])
    return s.replace(a,b,1)
g=project/"app/build.gradle.kts"
s=g.read_text()
s=edit(s,'    compileOptions {','''    buildTypes {
        getByName("debug") {
            isMinifyEnabled = providers.gradleProperty("nazzelhaExperimentalShrink").orNull == "true"
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-nazzelha.pro")
        }
    }
    compileOptions {''')
g.write_text(s)
(project/"app/proguard-nazzelha.pro").write_text('''# Preserve the application's entry points and native media wrappers.
-keep class com.clipshelf.app.** { *; }
-keep class com.yausername.** { *; }
-keep class net.lingala.zip4j.** { *; }
-dontoptimize
-keepclasseswithmembers,includedescriptorclasses class * { native <methods>; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
''')
p=java/"Engine.kt"
s=p.read_text()
s=edit(s,'    @Volatile private var initialized = false','    @Volatile private var initialized = false\n    private val formatCache = FormatCache()')
s=s.replace('if (result.changed) generation++','if (result.changed) { generation++; formatCache.clear() }')
s=edit(s,'val request = build().addOption("--dump-single-json").addOption("--skip-download")',
    'val request = build().addOption("--dump-single-json").addOption("--skip-download").addOption("--cache-dir", File(context.cacheDir, "yt-cache").absolutePath)')
s=edit(s,'    fun fetch(context: Context, url: String, playlist: Boolean): Collection {\n        init(context)',
    '''    fun fetch(context: Context, url: String, playlist: Boolean): Collection {
        FormatCache.directId(url,playlist)?.let { id ->
            formatCache.get(id)?.let { return Collection(it.title,false,listOf(it.copy(index=1,url=url))) }
        }
        init(context)''')
s=edit(s,'        if (entries == null) return Collection(root.string("title", "Video"), false, listOf(parseClip(root, 1, url)))',
    '''        if (entries == null) {
            val clip=parseClip(root,1,url)
            formatCache.put(clip)
            return Collection(root.string("title","Video"),false,listOf(clip))
        }''')
s=edit(s,'        if (clip.streams.isNotEmpty()) return clip\n        init(context)',
    '''        if (clip.streams.isNotEmpty()) return clip
        formatCache.get(clip.id)?.let { cached ->
            return cached.copy(index=clip.index,url=clip.url,
                duration=cached.duration.takeIf{it>0}?:clip.duration,
                thumbnail=clip.thumbnail.ifBlank{cached.thumbnail})
        }
        init(context)''')
s=edit(s,'        return info.copy(index = clip.index, url = clip.url, duration = info.duration.takeIf { it > 0 } ?: clip.duration, thumbnail = clip.thumbnail.ifBlank { info.thumbnail })',
    '''        return info.copy(index=clip.index,url=clip.url,duration=info.duration.takeIf{it>0}?:clip.duration,
            thumbnail=clip.thumbnail.ifBlank{info.thumbnail}).also{formatCache.put(it)}''')
s=edit(s,'''    fun version(context: Context): String {
        init(context)
        return locked(false) { EngineMaintenance.version() }
    }''', '''    fun version(context: Context): String = try {
        init(context)
        locked(false) { EngineMaintenance.version() }
    } catch (e: Throwable) {
        android.util.Log.e("NazzelhaEngine","Engine initialization or version check failed",e)
        throw e
    }''')
s=edit(s,'''        EngineMaintenance.recover(context.applicationContext)
        YoutubeDL.init(context.applicationContext)
        FFmpeg.init(context.applicationContext)
        initialized = true''', '''        try {
            EngineMaintenance.recover(context.applicationContext)
            android.util.Log.i("NazzelhaEngine","Initializing extractor")
            YoutubeDL.init(context.applicationContext)
            android.util.Log.i("NazzelhaEngine","Initializing media processor")
            FFmpeg.init(context.applicationContext)
            initialized = true
            android.util.Log.i("NazzelhaEngine","Media engine initialized")
        } catch(e: Throwable) {
            android.util.Log.e("NazzelhaEngine","Media engine initialization failed",e)
            throw e
        }''')
p.write_text(s)
p=java/"MainActivity.kt"
s=p.read_text()
s=edit(s,'    private val metadataWorkers = Executors.newFixedThreadPool(2)',
    '''    private val metadataWorkers = Executors.newFixedThreadPool(4)
    private fun metadataLimit(): Int {
        val am=getSystemService(android.app.ActivityManager::class.java)
        val cm=getSystemService(android.net.ConnectivityManager::class.java)
        val metered=runCatching{cm?.isActiveNetworkMetered?:true}.getOrDefault(true)
        return FormatCache.workers(Runtime.getRuntime().availableProcessors(),am?.isLowRamDevice?:true,metered)
    }''')
s=edit(s,'while (activeMetadata.size < 2 && pending.isNotEmpty())','while (activeMetadata.size < metadataLimit() && pending.isNotEmpty())')
s=edit(s,'?: pending.keys.firstOrNull { it !in activeMetadata } ?: break',
    '?: pending.keys.firstOrNull { it !in activeMetadata && pending[it]?.index?.let { index -> index in Workspace.selection } == true }\n                ?: pending.keys.firstOrNull { it !in activeMetadata } ?: break')
s=edit(s,'}, 150)','}, 240)')
s=edit(s,'val pendingCount = selected.count { Workspace.resolved(it).streams.isEmpty() && Workspace.key(it) !in Workspace.failures }',
    'val pendingCount = selected.count { FormatCache.canDefer(Workspace.resolved(it),Workspace.choice(it),Workspace.key(it) in Workspace.failures) }')
s=edit(s,'pendingCount > 0 -> "Reading $pendingCount selected…"','pendingCount > 0 && invalid == 0 -> "تحميل "+selected.size')
s=edit(s,'selected.isNotEmpty() && pendingCount == 0 && invalid == 0','selected.isNotEmpty() && invalid == 0')
s=edit(s,'''        if (plans.any { it == null }) return message("Wait for formats or review unavailable selections.")
        val resolvedPlans = plans.filterNotNull()
        val jobs = items.zip(resolvedPlans).map { (clip, plan) -> Job(Workspace.resolved(clip), plan.choice, Rules.folder(collection.title, collection.playlist), collection.playlist) }
        val output = MediaSizes.total(resolvedPlans.map { it.output })
        val transfer = MediaSizes.total(resolvedPlans.map { it.transfer })''',
    '''        val jobs = items.zip(plans).map { (clip,plan) ->
            val resolved=Workspace.resolved(clip)
            val choice=plan?.choice ?: Workspace.choice(clip).takeIf {
                FormatCache.canDefer(resolved,it,Workspace.key(clip) in Workspace.failures)
            } ?: return message("راجع الجودة المحددة أو أعد المحاولة.")
            Job(resolved,choice,Rules.folder(collection.title,collection.playlist),collection.playlist)
        }
        val output=MediaSizes.total(plans.map{it?.output?:SizeEstimate()})
        val transfer=MediaSizes.total(plans.map{it?.transfer?:SizeEstimate()})''')
s=s.replace('ready == available.size -> "Qualities ready · sizes may be estimated"','ready == available.size -> "الجودات جاهزة"')
s=s.replace('ready + failures == available.size -> "$ready ready · $failures unavailable"','ready + failures == available.size -> "$ready جاهز · $failures غير متاح"')
s=s.replace('else -> "Reading qualities · $ready/\${available.size}" + if (failures > 0) " · $failures failed" else ""','else -> "جاري تجهيز الجودات · $ready/\${available.size}" + if (failures > 0) " · $failures غير متاح" else ""')
p.write_text(s)
p=java/"DownloadService.kt"
s=p.read_text()
s=edit(s,'                    val uri = Engine.download(this, job, job.key, { canceled }) { update ->',
    '''                    val prepared = if (job.clip.streams.isEmpty()) {
                        check(FormatCache.canDefer(job.clip,job.choice,false)) { "Exact quality needs formats." }
                        QueueStore.change(job.key) { it.copy(message="جاري تجهيز الجودة") }
                        val clip=Engine.inspect(this,job.clip,job.key)
                        if(canceled) throw InterruptedException("Canceled")
                        val plan=MediaPlanner.plan(clip,job.choice) ?: error("الجودة غير متاحة")
                        job.copy(clip=clip,choice=plan.choice)
                    } else job
                    val uri = Engine.download(this, prepared, job.key, { canceled }) { update ->''')
p.write_text(s)
# Detect metered networks instead of treating permission denial as a fast connection.
p=project/"app/src/main/AndroidManifest.xml"
s=p.read_text()
if 'android.permission.ACCESS_NETWORK_STATE' not in s:
    s=edit(s,'    <uses-permission android:name="android.permission.INTERNET" />',
        '    <uses-permission android:name="android.permission.INTERNET" />\n    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />')
p.write_text(s)
print("v0.5.8: code/resource shrink, approved emblem, bounded format cache, selected-first metadata and deferred automatic quality")
