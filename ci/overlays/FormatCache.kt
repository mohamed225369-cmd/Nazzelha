package com.clipshelf.app

/** Bounded, short-lived cache of format descriptions; never stores media URLs or cookies. */
internal class FormatCache(private val ttl: Long = 600_000L, private val limit: Int = 128,
                           private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Entry(val clip: Clip, val at: Long)
    private val entries = LinkedHashMap<String,Entry>(16,0.75f,true)
    @Synchronized fun get(id: String): Clip? {
        val item=entries[id] ?: return null
        if(now()-item.at !in 0..ttl) { entries.remove(id); return null }
        return item.clip
    }
    @Synchronized fun put(clip: Clip) {
        if(!validId(clip.id) || !clip.available || clip.streams.isEmpty()) return
        entries[clip.id]=Entry(clip.copy(url="https://www.youtube.com/watch?v="+clip.id),now())
        while(entries.size>limit) entries.remove(entries.keys.first())
    }
    @Synchronized fun clear() { entries.clear() }
    companion object {
        fun validId(id:String)=id.matches(Regex("[A-Za-z0-9_-]{11}"))
        fun directId(url:String, playlist:Boolean):String? {
            val clean=Rules.youtubeLink(url) ?: return null
            return runCatching {
                val uri=java.net.URI(clean)
                val q=uri.rawQuery.orEmpty().split('&').mapNotNull {
                    val p=it.split('=',limit=2)
                    if(p.size==2) p[0] to p[1] else null
                }.toMap()
                if(playlist && "list" in q) return null
                val path=uri.path.orEmpty().trim('/').split('/')
                val id=if(uri.host.equals("youtu.be",true)) path.singleOrNull()
                    else if(path==listOf("watch")) q["v"]
                    else if(path.size==2 && path[0] in setOf("shorts","live","embed")) path[1] else null
                id?.takeIf(::validId)
            }.getOrNull()
        }
        fun workers(cores:Int, lowRam:Boolean, metered:Boolean):Int =
            if(lowRam || metered || cores<4) 2 else if(cores>=8) 4 else 3
        fun canDefer(clip:Clip, choice:Choice, failed:Boolean):Boolean =
            clip.available && clip.streams.isEmpty() && !failed &&
            choice.videoId==null && choice.audioId==null
    }
}
