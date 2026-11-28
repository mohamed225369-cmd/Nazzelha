package com.clipshelf.app
import org.junit.Assert.*
import org.junit.Test
class FormatCacheTest {
    private val clip=Clip(1,"abcdefghijk","Video","https://www.youtube.com/watch?v=abcdefghijk",
        streams=listOf(Stream("137","mp4",1920,1080,30,"avc1","none",100.0,1000,"")))
    @Test fun expiryAndLimit() {
        var t=0L
        val cache=FormatCache(100,1){t}
        cache.put(clip)
        assertEquals(clip,cache.get(clip.id))
        t=101
        assertNull(cache.get(clip.id))
        cache.put(clip)
        cache.put(clip.copy(id="12345678901"))
        assertNull(cache.get(clip.id))
        assertNotNull(cache.get("12345678901"))
        cache.clear()
        assertNull(cache.get("12345678901"))
    }
    @Test fun avoidPlaylistCacheAndUntrustedHosts() {
        assertEquals(clip.id,FormatCache.directId(clip.url,true))
        assertNull(FormatCache.directId(clip.url+"&list=PL123",true))
        assertEquals(clip.id,FormatCache.directId(clip.url+"&list=PL123",false))
        assertNull(FormatCache.directId("https://evil.example/watch?v=abcdefghijk",false))
        assertEquals(clip.id,FormatCache.directId("https://youtu.be/abcdefghijk",false))
    }
    @Test fun workerLimitsAndDeferredAutomaticChoice() {
        assertEquals(2,FormatCache.workers(8,false,true))
        assertEquals(2,FormatCache.workers(8,true,false))
        assertEquals(4,FormatCache.workers(8,false,false))
        val pending=clip.copy(streams=emptyList())
        assertTrue(FormatCache.canDefer(pending,Choice(),false))
        assertFalse(FormatCache.canDefer(pending,Choice(videoId="137"),false))
        assertFalse(FormatCache.canDefer(pending,Choice(audioId="140"),false))
        assertFalse(FormatCache.canDefer(pending,Choice(),true))
    }
}
