#!/usr/bin/env python3
from pathlib import Path
import re
import sys
project=Path(sys.argv[1])
here=Path(__file__).resolve().parent
main=project/"app/src/main"
java=main/"java/com/clipshelf/app"
res=main/"res"
for file in ("ic_logo.xml","ic_launcher_foreground.xml","ic_launcher.xml"):
    directory=res/("drawable-v26" if file=="ic_launcher.xml" else "drawable")
    directory.mkdir(parents=True,exist_ok=True)
    (directory/file).write_text((here/"overlays"/file).read_text(),encoding="utf-8")
(res/"values"/"brand_colors.xml").write_text('<resources><color name="nazzelha_launcher_bg">#14191F</color></resources>\n')
manifest=main/"AndroidManifest.xml"
s=manifest.read_text()
assert s.count('android:icon="@drawable/ic_logo"')==1
manifest.write_text(s.replace('android:icon="@drawable/ic_logo"','android:icon="@drawable/ic_launcher" android:roundIcon="@drawable/ic_launcher"'))
styles=res/"values/styles.xml"
s=styles.read_text()
for name,color in (("AppTheme","#14191F"),("AppTheme.Light","#F7F5F2")):
    pattern=r'(<style name="'+re.escape(name)+r'"[^>]*>)(.*?)(</style>)'
    def splash(m):
        return m[1]+m[2]+'        <item name="android:windowSplashScreenAnimatedIcon">@drawable/ic_launcher_foreground</item>\n        <item name="android:windowSplashScreenBackground">'+color+'</item>\n    '+m[3]
    s,n=re.subn(pattern,splash,s,flags=re.S)
    assert n==1
styles.write_text(s)
def replace(s,a,b):
    if s.count(a)!=1: raise SystemExit("Expected one polish anchor: "+a[:100])
    return s.replace(a,b,1)
m=java/"MainActivity.kt"
s=m.read_text()
s=replace(s,'    private var homeTransfer: LinearLayout? = null','''    private var homeTransfer: LinearLayout? = null
    private var homeTransferRow: QueueRow? = null
    private var homeTransferState: JobState? = null
    private var queueEmpty: View? = null''')
s=replace(s,'Workspace.tab = tab; homeTransfer=null; body.removeAllViews(); footer.removeAllViews()', 'Workspace.tab = tab; homeTransfer=null; homeTransferRow=null; homeTransferState=null; queueEmpty=null; body.removeAllViews(); footer.removeAllViews()')
s=replace(s,'setImageResource(R.drawable.ic_logo); contentDescription=null },lp(44,44)', 'setImageResource(R.drawable.ic_logo); contentDescription=null },lp(54,54)')
s=replace(s,'topBar.addView(label("Nazzelha",26,ink,true)', 'topBar.addView(label("Nazzelha",28,ink,true)')
s=replace(s,'topBar.addView(space(10))','topBar.addView(space(6))')
s=replace(s,'Brand.icon(this@MainActivity,"home",ink,22)','Brand.icon(this@MainActivity,"back",ink,22)')
s=replace(s,'gravity=Gravity.CENTER; isClickable=true; isFocusable=true\n                background=RippleDrawable', 'gravity=Gravity.CENTER; isClickable=true; isFocusable=true; contentDescription=text; isSelected=selected\n                background=RippleDrawable')
s=replace(s,'gravity=Gravity.CENTER; isClickable=true; isFocusable=true\n                    background=RippleDrawable','gravity=Gravity.CENTER; isClickable=true; isFocusable=true; contentDescription=text; isSelected=selected\n                    background=RippleDrawable')
s=replace(s,'label(text,11,if(selected)accent else soft,selected)', 'label(text,12,if(selected)accent else soft,selected)')
s=replace(s,'label(text,13,ink,true).apply{gravity=Gravity.CENTER}', 'label(text,14,ink,true).apply{gravity=Gravity.CENTER}')
s=replace(s,'Workspace.defaultChoice.copy(mode=mode,videoOnly=only,videoId=null,audioId=null,combined=false)', 'Workspace.defaultChoice.copy(mode=mode,videoOnly=only,videoId=null,audioId=null,combined=false,audioFromVideo=false)')
s=replace(s,'''        container.removeAllViews()
        val state=QueueStore.states.firstOrNull {it.phase.active} ?: QueueStore.states.lastOrNull {it.phase==Phase.DONE} ?: return
        container.addView(QueueRow().apply {bind(state)},lp(-1,-2))''','''        val state=QueueStore.states.firstOrNull {it.phase.active || it.phase==Phase.WAITING}
            ?: QueueStore.states.lastOrNull()
        if(state==null) {container.removeAllViews();homeTransferRow=null;homeTransferState=null;return}
        if(state==homeTransferState)return
        val card=homeTransferRow?:QueueRow().also{container.addView(it,lp(-1,-2));homeTransferRow=it}
        homeTransferState=state; card.bind(state)''')
s=replace(s,'val quality = label("", 12, soft)','val quality = label("", 13, soft)')
s=replace(s,'if (percentLabel.isNotEmpty()) info.addView(label(percentLabel, 20, color, true))',
    'if (state.phase.active && percentLabel.isNotEmpty()) info.addView(label(percentLabel, 20, color, true))')
s=replace(s,'        body.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))\n        renderQueue()',
    '''        body.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        queueEmpty=Brand.emptyState(this,"download","لا توجد تحميلات").also{
            body.addView(it,LinearLayout.LayoutParams(-1,0,1f))
        }
        renderQueue()''')
s=replace(s,'        queueAdapter?.notifyDataSetChanged()\n        if (queueFooterState != QueueStore.running)',
    '''        val empty=filteredQueue().isEmpty()
        queueHeader?.visibility=if(empty)View.GONE else View.VISIBLE
        queueEmpty?.visibility=if(empty)View.VISIBLE else View.GONE
        val list=(0 until body.childCount).map{body.getChildAt(it)}.filterIsInstance<ListView>().firstOrNull()
        list?.visibility=if(empty)View.GONE else View.VISIBLE
        queueAdapter?.notifyDataSetChanged()
        if (queueFooterState != QueueStore.running)''')
s=replace(s,'            else footer.addView(button("الرئيسية", true) { showTab(0) }, lp(-1, 52))','')
s=replace(s,'if(count==0)content.addView(label("لا توجد ملفات محفوظة",15,soft).apply{gravity=Gravity.CENTER},lp(-1,-2,36))',
    'if(count==0)content.addView(Brand.emptyState(this,"folder","لا توجد ملفات محفوظة"),lp(-1,280,18))')
s=s.replace('message("Paste a valid YouTube video or playlist link.")','message("ألصق رابط فيديو أو قائمة تشغيل صحيحة.")')
s=s.replace('message("Wait for quality information to finish loading before updating yt-dlp.")','message("انتظر انتهاء تجهيز الجودة.")')
s=s.replace('message("Finish the current operation before updating.")','message("انتظر انتهاء العملية الحالية.")')
s=s.replace('message("Finish reading the link or formats before changing appearance.")','message("انتظر انتهاء التجهيز.")')
s=s.replace('message("Wait for the current operation to finish.")','message("انتظر انتهاء العملية الحالية.")')
s=s.replace('status?.text = "${result.items.count { it.available }} available · choose Quality for exact formats"','')
s=s.replace('val name = if (height == null) "أفضل جودة" else if (actual.isEmpty() || height !in actual) "Up to ${height}p" else "${height}p"','val name = when(height){null->"أفضل جودة";2160->"4K · 2160p";1440->"2K · 1440p";else->"${height}p"}')
s=s.replace('val stream = if (mode == Mode.VIDEO) videos.getOrNull(video.selectedItemPosition - 1) else null','val stream = if (mode == Mode.VIDEO) videos.getOrNull(video.selectedItemPosition - 1)?.takeUnless{modes.selectedItemPosition==1 && it.audio} else null')
s=s.replace('val stream = videos.getOrNull(video.selectedItemPosition - 1)\n            videoContainer.visibility','val stream = videos.getOrNull(video.selectedItemPosition - 1)?.takeUnless{modes.selectedItemPosition==1 && it.audio}\n            videoContainer.visibility')
m.write_text(s)
p=java/"Model.kt"
s=p.read_text().replace('if (combined) " · Combined audio" else " · " + (audioId?.let { "Audio #$it" } ?: "Best audio")','if (videoOnly) " · فيديو" else if (combined) " · صوت مدمج" else " · " + (audioId?.let { "Audio #$it" } ?: "أفضل صوت")')
p.write_text(s)
p=java/"AppUpdater.kt"
s=p.read_text()
s=replace(s,'.setView(ProgressBar(activity)).setCancelable(false)', '''.setView(LinearLayout(activity).apply {
                orientation=LinearLayout.VERTICAL;gravity=android.view.Gravity.CENTER
                setPadding(Brand.dp(activity,24),Brand.dp(activity,12),Brand.dp(activity,24),Brand.dp(activity,28))
                addView(ProgressBar(activity).apply {indeterminateTintList=android.content.res.ColorStateList.valueOf(Brand.palette(activity).accent)},
                    LinearLayout.LayoutParams(Brand.dp(activity,60),Brand.dp(activity,60)))
            }).setCancelable(false)''')
s=replace(s,'val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = false }',
    '''val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = false
            progressTintList=android.content.res.ColorStateList.valueOf(Brand.palette(activity).accent)
            progressBackgroundTintList=android.content.res.ColorStateList.valueOf(Brand.palette(activity).panel2)
        }''')
s=replace(s,'setPadding(p, p, p, p); addView(label); addView(progress)','''setPadding(p,p,p,p)
            gravity=android.view.Gravity.CENTER
            val colors=Brand.palette(activity)
            addView(ImageView(activity).apply {setImageDrawable(Brand.badge(activity,"refresh",colors.accent,62))},LinearLayout.LayoutParams(Brand.dp(activity,62),Brand.dp(activity,62)))
            label.apply {gravity=android.view.Gravity.CENTER;textSize=17f;setTextColor(colors.ink)}
            addView(label,LinearLayout.LayoutParams(-1,-2).apply{topMargin=Brand.dp(activity,16)})
            addView(progress,LinearLayout.LayoutParams(-1,Brand.dp(activity,8)).apply{topMargin=Brand.dp(activity,18)})''')
s=s.replace('import android.widget.LinearLayout','import android.widget.LinearLayout\nimport android.widget.ImageView')
s=s.replace('setMessage("The small update could not be applied. Download the full APK (" + sizeLabel(release.bytes) + ") instead?")','setMessage("تحميل التحديث الكامل · " + sizeLabel(release.bytes) + "؟")')
p.write_text(s)
print("Installed reviewed reference logo, launcher, splash and UI polish")
