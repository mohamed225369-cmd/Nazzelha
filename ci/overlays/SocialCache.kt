package com.clipshelf.app

/** In-memory format descriptions only, isolated from YouTube's video-ID cache. */
internal class SocialCache(private val now: () -> Long = { System.nanoTime()/1_000_000 }) {
    private data class Entry(val clip: Clip,val at: Long)
    private val entries=LinkedHashMap<String,Entry>(16,0.75f,true)
    private fun key(link: MediaLink)=link.platform.name+":"+link.url
    @Synchronized fun get(link: MediaLink): Clip? {
        val k=key(link)
        val e=entries[k]?:return null
        if(now()-e.at !in 0..600_000L){entries.remove(k);return null}
        return e.clip
    }
    @Synchronized fun put(link: MediaLink,clip: Clip){
        if(link.platform==MediaPlatform.YOUTUBE || !clip.available || clip.streams.isEmpty())return
        entries[key(link)]=Entry(clip,now())
        while(entries.size>128)entries.remove(entries.keys.first())
    }
    @Synchronized fun clear(){entries.clear()}
}
