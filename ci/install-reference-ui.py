#!/usr/bin/env python3
"""Implement the approved Arabic Ink & Coral reference as native Android views."""
from pathlib import Path
import sys
project=Path(sys.argv[1])
here=Path(__file__).resolve().parent
java=project/"app/src/main/java/com/clipshelf/app"
(java/"ReferenceUi.kt").write_text((here/"overlays/ReferenceUi.kt").read_text(),encoding="utf-8")
def one(s,a,b):
    if s.count(a)!=1: raise SystemExit("Expected one reference anchor: "+a[:120])
    return s.replace(a,b,1)
def section(s,a,b,replacement):
    i=s.index(a); j=s.index(b,i)
    return s[:i]+replacement+s[j:]
m=java/"MainActivity.kt"
s=m.read_text()
s=one(s,"    private lateinit var root: LinearLayout","""    private lateinit var topBar: LinearLayout
    private var homeTransfer: LinearLayout? = null
    private var queueFilter = 0
    private lateinit var root: LinearLayout""")
s=one(s,"        if (Workspace.tab == 1) renderQueue() else refreshSelection(changed)","""        if (Workspace.tab == 1) renderQueue() else if (Workspace.tab == 0) {
            refreshSelection(changed); refreshHomeTransfer()
        } else if (Workspace.tab == 2 && changed) showTab(2)""")
s=section(s,"        val header = row()","        body = column(0);", """        topBar = row()
        root.addView(topBar, lp(-1, 64))
""")
s=one(s,"        setContentView(root)","        root.layoutDirection = View.LAYOUT_DIRECTION_LTR\n        setContentView(root)")
s=one(s,"addState(intArrayOf(), shape(if (primary) accent else panel2, 16))","addState(intArrayOf(), if(primary) Brand.primary(this@MainActivity,palette) else shape(panel2,16,line))")
s=section(s,"    private fun showSettings()","    private fun chooseAppearance()", """    private fun showSettings() { showTab(3) }
""")
s=section(s,"    private fun refreshTabs()","    private fun showTab(tab: Int)", """    private fun refreshHeader() {
        topBar.removeAllViews()
        if (Workspace.tab == 0) {
            topBar.addView(ImageView(this).apply { setImageResource(R.drawable.ic_logo); contentDescription=null },lp(44,44))
            topBar.addView(space(10))
            topBar.addView(label("Nazzelha",26,ink,true),LinearLayout.LayoutParams(0,-2,1f))
            val menu=button("") { showSettings() }.apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(Brand.icon(this@MainActivity,"more",ink,23),null,null,null)
                contentDescription="الإعدادات"; background=shape(Color.TRANSPARENT)
            }
            topBar.addView(menu,lp(44,44))
        } else {
            val back=button("") { showTab(0) }.apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(Brand.icon(this@MainActivity,"home",ink,22),null,null,null)
                contentDescription="العودة للرئيسية"
            }
            topBar.addView(back,lp(44,44)); topBar.addView(space(12))
            topBar.addView(label(when(Workspace.tab){1->"التحميلات";2->"الملفات";else->"الإعدادات"},24,ink,true).apply { gravity=Gravity.RIGHT },LinearLayout.LayoutParams(0,-2,1f))
        }
    }
    private fun refreshTabs() {
        val signature=Workspace.tab.toString()
        if(signature==tabSignature)return
        tabSignature=signature; tabs.removeAllViews()
        val labels=listOf(Triple(3,"الإعدادات","settings"),Triple(2,"الملفات","folder"),Triple(1,"التحميلات","download"),Triple(0,"الرئيسية","home"))
        labels.forEach { (index,text,icon) ->
            val selected=Workspace.tab==index
            val item=column(0).apply {
                gravity=Gravity.CENTER; isClickable=true; isFocusable=true
                background=RippleDrawable(ColorStateList.valueOf(ripple),shape(if(selected) (if(darkMode)0xFF392B2D.toInt() else 0xFFFFE5E0.toInt()) else Color.TRANSPARENT,14,if(selected)accent else null),null)
                setOnClickListener { showTab(index) }
                addView(ImageView(this@MainActivity).apply {setImageDrawable(Brand.icon(this@MainActivity,icon,if(selected)accent else soft,22)); contentDescription=null},lp(24,24))
                addView(label(text,11,if(selected)accent else soft,selected).apply { gravity=Gravity.CENTER; maxLines=1 },lp(-1,-2,3))
            }
            tabs.addView(item,LinearLayout.LayoutParams(0,-1,1f).apply { marginStart=dp(2); marginEnd=dp(2) })
        }
    }
""")
s=one(s,"        Workspace.tab = tab; body.removeAllViews(); footer.removeAllViews()","        Workspace.tab = tab; homeTransfer=null; body.removeAllViews(); footer.removeAllViews()")
s=one(s,"        refreshTabs()\n        if (tab == 0) renderLibrary() else buildQueue()","""        refreshHeader(); refreshTabs()
        when(tab){0->renderLibrary();1->buildQueue();2->buildFiles();3->buildSettings()}
""")
s=section(s,"            content.addView(label(\"Add media\"","        val top = row()", """            content.addView(CoralWave(this,darkMode),lp(-1,86,0))
            val linkRow=row().apply { background=shape(panel,18,line); setPadding(dp(12),0,dp(4),0) }
            linkRow.addView(ImageView(this).apply { setImageDrawable(Brand.icon(this@MainActivity,"link",soft,22)) },lp(24,24))
            linkRow.addView(space(10))
            val edit=EditText(this).apply {
                hint="رابط الفيديو"; setText(Workspace.link); setTextColor(ink); setHintTextColor(soft)
                textSize=15f; maxLines=1; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
                setPadding(0,dp(10),0,dp(10)); background=null; minHeight=dp(54)
                gravity=Gravity.RIGHT or Gravity.CENTER_VERTICAL; contentDescription="رابط الفيديو"
            }
            linkInput=edit; linkRow.addView(edit,LinearLayout.LayoutParams(0,-2,1f))
            linkRow.addView(button("") {
                val clipboard=getSystemService(ClipboardManager::class.java)!!
                edit.setText(clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?:"")
            }.apply {setCompoundDrawablesRelativeWithIntrinsicBounds(Brand.icon(this@MainActivity,"paste",ink,23),null,null,null); contentDescription="لصق"; background=shape(Color.TRANSPARENT)},lp(46,48))
            content.addView(linkRow,lp(-1,56,10))
            fetchButton=button(if(busy)"جاري التجهيز…" else "تحميل",true){fetch()}.apply {isEnabled=!busy&&!QueueStore.running; textSize=19f}
            content.addView(fetchButton!!,lp(-1,54,15))
            val modes=row()
            fun tile(text:String,icon:String,mode:Mode,only:Boolean):View {
                val selected=Workspace.defaultChoice.mode==mode && Workspace.defaultChoice.videoOnly==only
                return column(0).apply {
                    gravity=Gravity.CENTER; isClickable=true; isFocusable=true
                    background=RippleDrawable(ColorStateList.valueOf(ripple),shape(if(selected)(if(darkMode)0xFF30292C.toInt() else 0xFFFFE8E5.toInt()) else panel,18,if(selected)accent else line),null)
                    addView(ImageView(this@MainActivity).apply { setImageDrawable(Brand.icon(this@MainActivity,icon,if(selected)accent else ink,27)) },lp(30,30))
                    addView(label(text,13,ink,true).apply{gravity=Gravity.CENTER},lp(-1,-2,8))
                    setOnClickListener {
                        Workspace.defaultChoice=Workspace.defaultChoice.copy(mode=mode,videoOnly=only,videoId=null,audioId=null,combined=false)
                        showTab(0)
                    }
                }
            }
            modes.addView(tile("صوت","audio",Mode.AUDIO,false),LinearLayout.LayoutParams(0,dp(82),1f))
            modes.addView(space(8)); modes.addView(tile("فيديو","video",Mode.VIDEO,true),LinearLayout.LayoutParams(0,dp(82),1f))
            modes.addView(space(8)); modes.addView(tile("فيديو + صوت","layers",Mode.VIDEO,false),LinearLayout.LayoutParams(0,dp(82),1f))
            content.addView(modes,lp(-1,82,18))
            val playlist=checkbox("قائمة تشغيل").apply {isChecked=Workspace.playlist; setOnCheckedChangeListener{_,checked->Workspace.playlist=checked}}
            content.addView(playlist,lp(-1,-2,5))
            homeTransfer=column(0); content.addView(homeTransfer,lp(-1,-2,8)); refreshHomeTransfer()
            if(collection!=null) content.addView(quiet("العودة للاختيار الحالي"){Workspace.editingLink=false;showTab(0)},lp(-1,48,10))
            return
        }
""")
s=one(s,"    private fun refreshSelection(redraw: Boolean = true) {","""    private fun refreshHomeTransfer() {
        val container=homeTransfer?:return
        container.removeAllViews()
        val state=QueueStore.states.firstOrNull {it.phase.active} ?: QueueStore.states.lastOrNull {it.phase==Phase.DONE} ?: return
        container.addView(QueueRow().apply {bind(state)},lp(-1,-2))
    }
    private fun settingsRow(title:String,icon:String,value:String="",action:()->Unit):View {
        return row().apply {
            background=RippleDrawable(ColorStateList.valueOf(ripple),shape(panel,14,line),null)
            setPadding(dp(12),0,dp(12),0); isClickable=true; isFocusable=true; setOnClickListener { action() }
            addView(ImageView(this@MainActivity).apply{setImageDrawable(Brand.icon(this@MainActivity,icon,soft,21))},lp(25,25))
            addView(space(10))
            addView(label(title,15,ink,true).apply {gravity=Gravity.RIGHT},LinearLayout.LayoutParams(0,-2,1f))
            if(value.isNotEmpty()){addView(space(10));addView(label(value,12,soft))}
        }
    }
    private fun buildSettings() {
        val content=column(0); val sc=ScrollView(this).apply {isVerticalScrollBarEnabled=false;addView(content)}
        body.addView(sc,LinearLayout.LayoutParams(-1,0,1f))
        val theme=when(appearance){"light"->"فاتح";"dark"->"داكن";else->"النظام"}
        content.addView(settingsRow("مكان الحفظ","folder","Download/Nazzelha") {
            AlertDialog.Builder(this).setTitle("مكان الحفظ").setMessage("Download/Nazzelha").setPositiveButton("تم",null).showBranded()
        },lp(-1,56,12))
        content.addView(settingsRow("المظهر","settings",theme){chooseAppearance()},lp(-1,56,8))
        content.addView(settingsRow("الإشعارات","bell") {
            runCatching {startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,packageName))}
        },lp(-1,56,8))
        content.addView(settingsRow("تحديثات التطبيق","refresh"){appUpdates.settings()},lp(-1,56,8))
        content.addView(settingsRow("محرك التحميل","refresh"){updateEngine()},lp(-1,56,8))
        content.addView(settingsRow("عن التطبيق","info",appUpdates.versionLabel()){
            AlertDialog.Builder(this).setTitle("Nazzelha").setMessage("الإصدار "+appUpdates.versionLabel()+"\\nاحفظ المحتوى المسموح لك بتنزيله فقط.").setPositiveButton("تم",null).showBranded()
        },lp(-1,56,8))
    }
    private fun buildFiles() {
        val content=column(0); val sc=ScrollView(this).apply {isVerticalScrollBarEnabled=false;addView(content)}
        body.addView(sc,LinearLayout.LayoutParams(-1,0,1f))
        val media=android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        var count=0
        runCatching {
            contentResolver.query(media,arrayOf("_id","_display_name","_size","mime_type"),"relative_path LIKE ? AND is_pending = 0",arrayOf("Download/Nazzelha/%"),"date_added DESC")?.use {cursor->
                while(cursor.moveToNext()&&count<200){
                    count++
                    val uri=android.content.ContentUris.withAppendedId(media,cursor.getLong(0)).toString()
                    val name=cursor.getString(1)?:""
                    val audio=cursor.getString(3)?.startsWith("audio/")==true
                    val card=column(12).apply{background=shape(panel,18,line)}
                    val head=row()
                    head.addView(ImageView(this).apply{setImageDrawable(Brand.icon(this@MainActivity,if(audio)"audio" else "video",accent,28))},lp(40,40));head.addView(space(12))
                    val info=column(0)
                    info.addView(label(name,14,ink,true).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;gravity=Gravity.RIGHT})
                    info.addView(label(MediaSizes.bytes(cursor.getLong(2)),12,soft),lp(-1,-2,5));head.addView(info,LinearLayout.LayoutParams(0,-2,1f))
                    card.addView(head)
                    val actions=row();weightButton(actions,button("مشاركة"){shareFile(uri)});actions.addView(space(8));weightButton(actions,button("فتح الملف",true){openFile(uri)})
                    card.addView(actions,lp(-1,-2,10));content.addView(card,lp(-1,-2,10))
                }
            }
        }.onFailure {content.addView(label("تعذر قراءة الملفات",14,soft),lp(-1,-2,18))}
        if(count==0)content.addView(label("لا توجد ملفات محفوظة",15,soft).apply{gravity=Gravity.CENTER},lp(-1,-2,36))
    }
    private fun shareFile(uriString:String){
        val uri=Uri.parse(uriString)
        runCatching {startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(contentResolver.getType(uri)?:"*/*").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),null))}
            .onFailure {message("تعذر مشاركة الملف")}
    }
    private fun refreshSelection(redraw: Boolean = true) {""")
# Quality sheet uses real radio rows and real video-only mode.
s=one(s,'val modes = spinner(listOf("Video + audio", "Audio only"), if (current.mode == Mode.VIDEO) 0 else 1); field("Format", modes)','val modes = spinner(listOf("فيديو + صوت", "فيديو", "صوت"), if(current.mode == Mode.AUDIO) 2 else if(current.videoOnly) 1 else 0); field("النوع", modes)')
s=one(s,'val caps = spinner(heights.map { height ->','val caps = QualityRows(this,heights.map { height ->')
s=one(s,'        }, initial)\n        val videoContainer','        }, initial,palette)\n        val videoContainer')
s=one(s,'val mode = if (modes.selectedItemPosition == 0) Mode.VIDEO else Mode.AUDIO','val mode = if (modes.selectedItemPosition == 2) Mode.AUDIO else Mode.VIDEO')
s=one(s,'val audioId = if (stream?.audio == true || (mode == Mode.VIDEO && !showExact))','val audioId = if (modes.selectedItemPosition==1 || stream?.audio == true || (mode == Mode.VIDEO && !showExact))')
s=one(s,'stream?.id, audioId, stream?.audio == true)','stream?.id, audioId, stream?.audio == true, videoOnly = modes.selectedItemPosition==1)')
s=one(s,'val videoMode = modes.selectedItemPosition == 0','val videoMode = modes.selectedItemPosition != 2')
s=one(s,'(!videoMode || (showExact && stream?.audio != true))','(!videoMode || (modes.selectedItemPosition==0 && showExact && stream?.audio != true))')
s=one(s,'val current = clip?.let { Workspace.choices[it.index] } ?: Workspace.defaultChoice','val current = clip?.let { Workspace.choices[it.index] } ?: Workspace.defaultChoice')
s=one(s,'val sheet = dialog.showBranded()','val sheet = dialog.showBranded()')
s=one(s,'setTitle(if (clip == null) "Batch quality" else "Video ${clip.index} · quality")','setTitle("اختر الجودة")')
# Styled radio option labels, compact queue, live data only.
s=one(s,'        body.addView(label("Downloads", 28, ink, true), lp(-1, -2, 18))',"""        val filters=row()
        listOf("الكل","قيد التحميل","مكتمل").forEachIndexed {i,name->
            weightButton(filters,button(name,queueFilter==i){queueFilter=i;showTab(1)})
            if(i<2)filters.addView(space(6))
        }
        body.addView(filters,lp(-1,48,8))""")
s=one(s,'private var snapshot: List<JobState> = QueueStore.states','private var snapshot: List<JobState> = filteredQueue()')
s=one(s,'snapshot = QueueStore.states; super.notifyDataSetChanged()','snapshot = filteredQueue(); super.notifyDataSetChanged()')
s=one(s,'    private inner class QueueAdapter : BaseAdapter() {','''    private fun filteredQueue()=QueueStore.states.filter {when(queueFilter){1->it.phase.active||it.phase==Phase.WAITING;2->it.phase==Phase.DONE;else->true}}
    private inner class QueueAdapter : BaseAdapter() {''')
s=one(s,'if (state.uri != null) dynamic.addView(button("Open file") { openFile(state.uri) }, lp(-1, -2, 4))','''if (state.uri != null) {
                val actions=row();weightButton(actions,button("مشاركة"){shareFile(state.uri)});actions.addView(space(8));weightButton(actions,button("فتح الملف",true){openFile(state.uri)})
                dynamic.addView(actions,lp(-1,-2,10))
            }''')
s=one(s,'val info = row(); info.addView(label(if (state.phase == Phase.FAILED) "Download failed" else DisplayText.clean(state.message), 13, color)','val info = row(); info.addView(label(when(state.phase){Phase.DONE->"اكتمل التحميل";Phase.FAILED->"فشل التحميل";Phase.CANCELED->"تم الإلغاء";Phase.WAITING->"في الانتظار";Phase.SAVING->"جاري الحفظ";Phase.PROCESSING->if(state.message.contains("retry",true))"إعادة المحاولة" else if(state.message.contains("updat",true))"جاري التحديث" else "جاري المعالجة";else->"جاري التحميل"}, 13, color)')
s=one(s,'if (percentLabel.isNotEmpty()) info.addView(label(percentLabel, 12, color, true))','if (percentLabel.isNotEmpty()) info.addView(label(percentLabel, 20, color, true))')
s=one(s,'metrics.speed ?: "Speed unavailable"','metrics.speed.orEmpty()')
s=one(s,'metrics.etaLabel()?.let { "$it left" } ?: "Estimating time…"','metrics.etaLabel().orEmpty()')
s=one(s,'dynamic.addView(live, lp(-1, -2, 8)); metrics.total?.let { dynamic.addView(label("Stream size · $it", 12, soft), lp(-1, -2, 4)) }','dynamic.addView(live, lp(-1, -2, 8))')
# Localized loading card instead of the native rectangular progress popup.
s=one(s,'            content.addView(label(description, 15, ink, true))\n            content.addView(progressTrack(null, description), lp(-1, 6, 16))','''            content.gravity=Gravity.CENTER
            content.addView(CoralWave(this,darkMode),lp(-1,44))
            content.addView(ProgressBar(this).apply {indeterminateTintList=ColorStateList.valueOf(accent)},lp(58,58,12))
            content.addView(label("جاري التجهيز…",18,ink,true).apply{gravity=Gravity.CENTER},lp(-1,-2,16))''')
# Keep package names and stack traces out of UI; copy raw details explicitly for diagnostics.
s=one(s,'.setMessage(text.takeLast(4000))','.setMessage("تعذر إكمال العملية. يمكنك إعادة المحاولة أو نسخ التفاصيل الفنية.")')
# Existing runtime behavior remains; strings are presentation only.
translations={
    "Load link":"تحميل","Working…":"جاري التجهيز…","Reading link…":"جاري التجهيز…",
    "Settings":"الإعدادات","Appearance":"المظهر","Follow system":"النظام","Light":"فاتح","Dark":"داكن",
    "Cancel":"إلغاء","Apply":"تأكيد","Done":"تم","OK":"تم","Close":"إغلاق",
    "Change link":"تغيير الرابط","Default quality":"الجودة","Edit":"تعديل","All":"الكل","None":"إلغاء الكل",
    "Download engine":"محرك التحميل","Checking…":"جاري الفحص…","Updating…":"جاري التحديث…","Update now":"تحديث الآن",
    "Version: ":"الإصدار: ","Error details":"تفاصيل الخطأ","Retry":"إعادة المحاولة","Download selected":"تحميل المحدد",
    "Back to library":"الرئيسية","Cancel batch":"إلغاء التحميل","Nothing queued yet.":"لا توجد تحميلات",
    "Best available":"أفضل جودة","Video quality":"اختر الجودة","Video format":"صيغة الفيديو","Audio quality":"جودة الصوت",
    "Audio format":"صيغة الصوت","MP3 bitrate":"جودة MP3","Format":"النوع","Save to":"مكان الحفظ",
    "Copy":"نسخ التفاصيل","Could not complete operation":"تعذر إكمال العملية",
    "Start":"بدء التحميل","Exact formats":"خيارات متقدمة","Load formats":"تحميل الجودات"
}
for a,b in translations.items(): s=s.replace('"'+a+'"','"'+b+'"')
s=s.replace('label(if (collection.playlist) "PLAYLIST" else "VIDEO"','label(if (collection.playlist) "قائمة تشغيل" else "فيديو"')
s=s.replace('else -> "Download ${selected.size} ${if (selected.size == 1) "item" else "items"}"','else -> "تحميل ${selected.size}"')
s=s.replace('summaryLabel?.text = "${selected.size} of ${collection.items.size} selected"','summaryLabel?.text = "${selected.size} / ${collection.items.size} محدد"')
s=s.replace('queueHeader?.text = if (states.isEmpty()) "لا توجد تحميلات" else "$processed of ${states.size} processed · $done saved" + (if (failed > 0) " · $failed failed" else "") + (if (canceled > 0) " · $canceled canceled" else "") + "\\n" + (if (QueueStore.running) "Working in background" else "Batch finished")','queueHeader?.text = if(states.isEmpty()) "لا توجد تحميلات" else "$done / ${states.size} مكتمل" + if(failed>0) " · $failed فشل" else ""')
s=s.replace('setTitle("Download ${jobs.size} item(s)?").setMessage("Save to ${jobs.first().folder}\\n\\nEstimated output: ${output.label()}\\nData transfer: ${transfer.label()}\\n\\nOnly save content you own or are authorized to download. Keep enough free storage for temporary streams and the finished file.")','setTitle("بدء التحميل").setMessage("${jobs.size} ملف · ${output.label()}\\nالبيانات: ${transfer.label()}")')
m.write_text(s)
# Add functional video-only selection, not a mock button.
p=java/"Model.kt";s=p.read_text()
s=one(s,'val combined: Boolean = false, val audioFromVideo: Boolean = false)','val combined: Boolean = false, val audioFromVideo: Boolean = false, val videoOnly: Boolean = false)')
s=one(s,'if (c.mode == Mode.AUDIO) return c.audioId ?: "bestaudio/best"','''if (c.mode == Mode.AUDIO) return c.audioId ?: "bestaudio/best"
        if (c.videoOnly) return c.videoId ?: "bestvideo" + (c.maxHeight?.let { "[height<=$it]" } ?: "")''')
s=one(s,'if (c.mode == Mode.VIDEO) add("--merge-output-format" to "mp4/mkv")','if (c.mode == Mode.VIDEO) { if(!c.videoOnly) add("--merge-output-format" to "mp4/mkv") }')
p.write_text(s)
p=java/"MediaPlan.kt";s=p.read_text()
s=one(s,'(if (v.audio) "Included audio" else "${audioLabel(audio!!)} audio")','(if(choice.videoOnly) "فيديو" else if (v.audio) "صوت مدمج" else "${audioLabel(audio!!)}")')
s=one(s,'        // Combined formats can be chosen explicitly.','''        if(c.videoOnly){
            val pool=eligible.filter{!it.audio}
            val v=if(c.videoId!=null)pool.find{it.id==c.videoId} else pool.maxOfOrNull{it.height}?.let{height->pool.lastOrNull{it.height==height}}
            v?:return null
            val size=MediaSizes.stream(v,clip.duration)
            return PlannedMedia(c.copy(videoId=v.id,audioId=null,combined=false),v,null,size,size)
        }
        // Combined formats can be chosen explicitly.''')
p.write_text(s)
# Update the updater presentation without changing its verified download logic.
p=java/"AppUpdater.kt";s=p.read_text()
s=section(s,'                        .setMessage(if (release.delta == null)','                        .setNegativeButton("Later"', '''                        .setMessage(if(release.delta==null) "تحديث كامل · "+sizeLabel(release.bytes) else "تحديث جزئي · "+sizeLabel(release.delta.bytes))
''')
for a,b in {"App updates":"تحديثات التطبيق","Check for updates":"البحث عن تحديث","Automatic checks · ":"تحديث تلقائي · ","Open releases in browser":"الإصدارات","Close":"إغلاق","OK":"تم","Later":"لاحقًا","Download":"تحديث الآن","No newer published version is available.":"التطبيق محدّث","Checking for updates…":"جاري الفحص…","Update available · ":"تحديث متاح · ","Downloading update · ":"جاري التحديث · ","Applying update · ":"جاري التجهيز · ","Downloading ":"جاري التحديث ","Update verified":"تم تنزيل التحديث","Ready to install.":"جاهز للتثبيت","Install":"تثبيت","Cancel":"إلغاء","Full update needed":"تحديث كامل مطلوب","Download full APK":"تحميل التحديث الكامل","Allow app updates":"السماح بالتحديثات","Open settings":"فتح الإعدادات"}.items():
    s=s.replace('"'+a+'"','"'+b+'"')
s=s.replace('DisplayText.updateError()','"تعذر التحديث. حاول مرة أخرى."')
p.write_text(s)
# Keep actual engine version checks and logs; no package label in normal UI.
p=java/"EngineMaintenance.kt";s=p.read_text().replace('Previous version: $before\\nInstalled version: $after\\nUpdate verified. The engine is ready.','الإصدار السابق: $before\\nالإصدار الحالي: $after').replace('Installed version: $before\\nNo newer stable version available.','الإصدار الحالي: $before\\nمحدّث')
p.write_text(s)
p=java/"UnifiedProgress.kt";s=p.read_text().replace('!job.choice.combined)','!job.choice.combined && !job.choice.videoOnly)')
p.write_text(s)
print("Installed approved reference layout")
