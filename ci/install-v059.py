#!/usr/bin/env python3
from pathlib import Path
import sys
project=Path(sys.argv[1])
here=Path(__file__).resolve().parent
java=project/"app/src/main/java/com/clipshelf/app"
test=project/"app/src/test/java/com/clipshelf/app"
for f in ("PlatformLinks.kt","SocialCache.kt"):
    (java/f).write_text((here/"overlays"/f).read_text())
(test/"PlatformLinksTest.kt").write_text((here/"tests/PlatformLinksTest.kt").read_text())
def edit(s,a,b):
    if s.count(a)!=1: raise SystemExit("Expected one multi-platform anchor: "+a[:120])
    return s.replace(a,b,1)
p=java/"Engine.kt"
s=p.read_text()
s=edit(s,"    private val formatCache = FormatCache()",
    "    private val formatCache = FormatCache()\n    private val socialCache = SocialCache()")
s=s.replace("generation++; formatCache.clear()","generation++; formatCache.clear(); socialCache.clear()")
s=edit(s,'        addOption("--remote-components", "ejs:github")',
    '        if (PlatformLinks.resolve(url)?.platform==MediaPlatform.YOUTUBE) addOption("--remote-components", "ejs:github")')
s=edit(s,'    fun fetch(context: Context, url: String, playlist: Boolean): Collection {\n        FormatCache.directId',
    '''    fun fetch(context: Context, url: String, playlist: Boolean): Collection {
        val link=PlatformLinks.resolve(url) ?: error("Unsupported URL")
        if(link.platform!=MediaPlatform.YOUTUBE) {
            socialCache.get(link)?.let { return Collection(it.title,false,listOf(it)) }
            init(context)
            val root=json(context){request(link.url).addOption("--no-playlist")}
            val entries=root.optJSONArray("entries")
            val video=if(entries==null)root else {
                require(entries.length()==1){"NAZZELHA_MULTIPLE_ITEMS"}
                entries.optJSONObject(0)?:error("NAZZELHA_NO_VIDEO")
            }
            val clip=parseClip(video,1,link.url)
            require(clip.available && clip.streams.any{it.video}){"NAZZELHA_NO_VIDEO"}
            socialCache.put(link,clip)
            return Collection(clip.title,false,listOf(clip))
        }
        FormatCache.directId''')
s=edit(s,'        val url = Rules.youtubeLink(o.string("webpage_url", "")) ?: Rules.youtubeLink(o.string("url", "")) ?: Rules.youtubeLink(originalUrl)\n            ?: if (id.matches(Regex("[A-Za-z0-9_-]{11}"))) "https://www.youtube.com/watch?v=$id" else ""',
    '''        val original=PlatformLinks.resolve(originalUrl)
        val linkCandidates=listOf(o.string("webpage_url",""),o.string("url","")).mapNotNull(PlatformLinks::resolve)
        val platform=original?.platform ?: MediaPlatform.YOUTUBE
        // Preserve the public post URL for social media, never use signed media URLs.
        val url=if(platform!=MediaPlatform.YOUTUBE)original?.url.orEmpty()
            else linkCandidates.firstOrNull{it.platform==MediaPlatform.YOUTUBE}?.url
                ?: original?.url ?: if(FormatCache.validId(id))"https://www.youtube.com/watch?v=$id" else ""''')
s=edit(s,'        val thumb = ThumbnailRules.pick(id, candidates, o.string("thumbnail", ""))',
    '''        val thumb = if(platform==MediaPlatform.YOUTUBE) ThumbnailRules.pick(id, candidates, o.string("thumbnail",""))
            else candidates.filter{PlatformLinks.publicThumbnail(platform,it.first)}.minByOrNull{kotlin.math.abs(it.second-320)}?.first
                ?: o.string("thumbnail","").takeIf{PlatformLinks.publicThumbnail(platform,it)}.orEmpty()''')
# Existing cache must not turn a social ID into a YouTube watch URL.
s=edit(s,'        formatCache.get(clip.id)?.let { cached ->',
    '        formatCache.get(clip.id).takeIf{PlatformLinks.resolve(clip.url)?.platform==MediaPlatform.YOUTUBE}?.let { cached ->')
s=edit(s,'thumbnail=clip.thumbnail.ifBlank{info.thumbnail}).also{formatCache.put(it)}',
    'thumbnail=clip.thumbnail.ifBlank{info.thumbnail}).also{if(PlatformLinks.resolve(it.url)?.platform==MediaPlatform.YOUTUBE)formatCache.put(it)}')
p.write_text(s)
p=java/"MainActivity.kt"
s=p.read_text()
s=edit(s,'fun key(clip: Clip) = clip.id.ifBlank { clip.url }','fun key(clip: Clip) = PlatformLinks.key(clip)')
s=edit(s,'intent.getStringExtra(Intent.EXTRA_TEXT)?.let { Rules.youtubeLink(it) }',
    'intent.getStringExtra(Intent.EXTRA_TEXT)?.let { PlatformLinks.resolve(it)?.url }')
s=edit(s,'val url = Rules.youtubeLink(linkInput?.text.toString()) ?: return message("ألصق رابط فيديو أو قائمة تشغيل صحيحة.")',
    '''val link=PlatformLinks.resolve(linkInput?.text.toString()) ?: return message("ألصق رابط فيديو من منصة مدعومة.")
        val url=link.url''')
s=edit(s,'        val playlist = Workspace.playlist\n        task(',
    '        val playlist = Workspace.playlist && link.platform==MediaPlatform.YOUTUBE\n        task(')
s=edit(s,'val url = clip.thumbnail.takeIf(ThumbnailRules::valid) ?: ThumbnailRules.fallback(clip.id)',
    '''val platform=PlatformLinks.resolve(clip.url)?.platform
            val url=clip.thumbnail.takeIf{PlatformLinks.publicThumbnail(platform,it)}
                ?: if(platform==MediaPlatform.YOUTUBE)ThumbnailRules.fallback(clip.id) else ""''')
s=edit(s,'            content.addView(linkRow,lp(-1,56,10))',
    '''            content.addView(linkRow,lp(-1,56,10))
            val platformRow=row()
            val platformLabels=mutableMapOf<MediaPlatform,TextView>()
            for(p in MediaPlatform.entries){
                val tile=label(p.label,11,soft,true).apply{
                    gravity=Gravity.CENTER; maxLines=1
                    background=shape(panel,12,line)
                }
                platformLabels[p]=tile
                platformRow.addView(tile,LinearLayout.LayoutParams(0,dp(38),1f).apply{marginEnd=dp(4)})
            }
            content.addView(platformRow,lp(-1,38,10))
            val platformStatus=label("",12,soft).apply{gravity=Gravity.RIGHT}
            content.addView(platformStatus,lp(-1,-2,8))
            fun updatePlatform(){
                val detected=PlatformLinks.resolve(edit.text.toString())?.platform
                platformLabels.forEach{(platform,tile)->
                    tile.setTextColor(if(platform==detected)accent else soft)
                    tile.background=shape(panel,12,if(platform==detected)accent else line)
                }
                platformStatus.text=detected?.let{"تم التعرف على "+it.label}?: "YouTube · Instagram · TikTok · Facebook"
            }''')
s=edit(s,'            content.addView(playlist,lp(-1,-2,5))',
    '''            content.addView(playlist,lp(-1,-2,5))
            fun syncPlatform(){
                updatePlatform()
                val detected=PlatformLinks.resolve(edit.text.toString())?.platform
                playlist.visibility=if(detected==null || detected==MediaPlatform.YOUTUBE)View.VISIBLE else View.GONE
            }
            edit.addTextChangedListener(object:android.text.TextWatcher{
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){syncPlatform()}
                override fun afterTextChanged(s:android.text.Editable?){}
            })
            syncPlatform()''')
s=edit(s,'.setMessage("تعذر إكمال العملية. يمكنك إعادة المحاولة أو نسخ التفاصيل الفنية.")',
    '.setMessage(PlatformLinks.error(text))')
p.write_text(s)
print("Installed multi-platform public video routes, detection, separate cache and user-facing errors")
