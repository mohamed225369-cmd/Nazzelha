package com.clipshelf.app
import org.junit.Assert.*
import org.junit.Test

class PlatformLinksTest {
    @Test fun publicVideoRoutes() {
        val cases=mapOf(
            "https://youtu.be/abcdefghijk" to MediaPlatform.YOUTUBE,
            "https://www.youtube.com/watch?v=abcdefghijk&list=PL123" to MediaPlatform.YOUTUBE,
            "https://www.instagram.com/reel/Abc_123/?igsh=tracking" to MediaPlatform.INSTAGRAM,
            "https://www.instagram.com/p/Abc123/" to MediaPlatform.INSTAGRAM,
            "https://www.instagram.com/share/reel/abc123/" to MediaPlatform.INSTAGRAM,
            "https://www.tiktok.com/@creator/video/123456789" to MediaPlatform.TIKTOK,
            "https://vm.tiktok.com/Ab123/" to MediaPlatform.TIKTOK,
            "https://vt.tiktok.com/Ab123/" to MediaPlatform.TIKTOK,
            "https://www.facebook.com/reel/12345678" to MediaPlatform.FACEBOOK,
            "https://www.facebook.com/watch/?v=12345678" to MediaPlatform.FACEBOOK,
            "https://fb.watch/abc123/" to MediaPlatform.FACEBOOK,
            "https://www.facebook.com/share/v/abc123/" to MediaPlatform.FACEBOOK
        )
        cases.forEach{(url,expected)->assertEquals(url,expected,PlatformLinks.resolve(url)?.platform)}
        assertEquals("https://www.instagram.com/reel/Abc_123",PlatformLinks.resolve(cases.keys.elementAt(2))?.url)
        assertEquals(MediaPlatform.TIKTOK,PlatformLinks.resolve("Watch this: https://vm.tiktok.com/Ab123/،")?.platform)
    }
    @Test fun invalidAndNonMediaRoutes() {
        listOf("https://instagram.com.evil.test/reel/abc","https://instagram.com@evil.test/reel/abc",
            "https://www.instagram.com:8080/reel/abc","https://www.instagram.com/accounts/login/",
            "https://www.tiktok.com/@someone","https://www.facebook.com/settings",
            "http://127.0.0.1/","file:///etc/passwd","https://example.com/video/123").forEach{
            assertNull(it,PlatformLinks.resolve(it))
        }
    }
    @Test fun thumbnailsArePlatformSpecific() {
        assertTrue(PlatformLinks.publicThumbnail(MediaPlatform.INSTAGRAM,"https://scontent.cdninstagram.com/a.jpg"))
        assertTrue(PlatformLinks.publicThumbnail(MediaPlatform.TIKTOK,"https://p16.tiktokcdn.com/a.jpg"))
        assertFalse(PlatformLinks.publicThumbnail(MediaPlatform.FACEBOOK,"https://fbcdn.net.evil.test/a.jpg"))
        assertFalse(PlatformLinks.publicThumbnail(MediaPlatform.YOUTUBE,"https://scontent.fbcdn.net/a.jpg"))
        assertFalse(PlatformLinks.publicThumbnail(MediaPlatform.INSTAGRAM,"http://scontent.cdninstagram.com/a.jpg"))
    }
    @Test fun cacheIsIsolatedAndExpires() {
        var time=0L
        val cache=SocialCache{time}
        val insta=PlatformLinks.resolve("https://www.instagram.com/reel/abcdef12345")!!
        val yt=PlatformLinks.resolve("https://youtu.be/abcdef12345")!!
        val clip=Clip(1,"abcdef12345","Test",insta.url,streams=listOf(Stream("0","mp4",720,1280,30,"h264","aac",100.0,1000,"")))
        cache.put(insta,clip)
        assertEquals(clip,cache.get(insta))
        assertNull(cache.get(yt))
        assertNotEquals(PlatformLinks.key(clip),PlatformLinks.key(clip.copy(url=yt.url)))
        time=600_001L
        assertNull(cache.get(insta))
    }
    @Test fun translatedErrors() {
        assertTrue(PlatformLinks.error("login required").contains("تسجيل الدخول"))
        assertTrue(PlatformLinks.error("HTTP Error 429").contains("طلبات كثيرة"))
        assertTrue(PlatformLinks.error("NAZZELHA_MULTIPLE_ITEMS").contains("متعددة العناصر"))
    }
}
