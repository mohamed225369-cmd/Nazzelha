package com.clipshelf.app

import java.net.URI
import java.util.Locale

internal enum class MediaPlatform(val label: String) {
    YOUTUBE("YouTube"), INSTAGRAM("Instagram"), TIKTOK("TikTok"), FACEBOOK("Facebook")
}
internal data class MediaLink(val platform: MediaPlatform, val url: String)

/** Accept only known public media routes. No arbitrary web URLs, user-info, or custom ports. */
internal object PlatformLinks {
    private val hosts = mapOf(
        MediaPlatform.YOUTUBE to setOf("youtube.com","www.youtube.com","m.youtube.com","music.youtube.com","youtu.be"),
        MediaPlatform.INSTAGRAM to setOf("instagram.com","www.instagram.com","m.instagram.com"),
        MediaPlatform.TIKTOK to setOf("tiktok.com","www.tiktok.com","m.tiktok.com","vm.tiktok.com","vt.tiktok.com"),
        MediaPlatform.FACEBOOK to setOf("facebook.com","www.facebook.com","m.facebook.com","web.facebook.com","fb.watch","fb.com","www.fb.com")
    )
    private val token = Regex("[A-Za-z0-9_-]{1,128}")
    private val numeric = Regex("[0-9]{1,32}")
    private val ytId = Regex("[A-Za-z0-9_-]{11}")
    fun resolve(text: String): MediaLink? {
        val candidates = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).findAll(text)
        return candidates.take(20).mapNotNull { match -> parse(match.value.trimEnd('.',',',')',']','"','\'','،','؛')) }.firstOrNull()
    }
    private fun parse(raw: String): MediaLink? {
        if (raw.length > 4096) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme !in setOf("http","https") || uri.userInfo != null || uri.port !in listOf(-1,443,80)) return null
        val platform = hosts.entries.firstOrNull { host in it.value }?.key ?: return null
        val path = uri.rawPath.orEmpty().trim('/').split('/').filter(String::isNotEmpty)
        val q = uri.rawQuery.orEmpty().split('&').mapNotNull {
            val p=it.split('=',limit=2); if (p.size==2) p[0] to p[1] else null
        }.toMap()
        val valid = when(platform) {
            MediaPlatform.YOUTUBE -> when {
                host=="youtu.be" -> path.size==1 && ytId.matches(path[0])
                path==listOf("watch") -> q["v"]?.let(ytId::matches)==true
                path==listOf("playlist") -> q["list"]?.let(token::matches)==true
                path.size==2 && path[0] in setOf("shorts","live","embed","v") -> ytId.matches(path[1])
                else -> false
            }
            MediaPlatform.INSTAGRAM ->
                (path.size==2 && path[0] in setOf("p","reel","reels","tv") && token.matches(path[1])) ||
                (path.size==3 && path[0]=="share" && path[1] in setOf("p","reel") && token.matches(path[2]))
            MediaPlatform.TIKTOK ->
                (host in setOf("vm.tiktok.com","vt.tiktok.com") && path.size==1 && token.matches(path[0])) ||
                (path.size==2 && path[0]=="t" && token.matches(path[1])) ||
                (path.size==3 && path[0].startsWith("@") && path[1]=="video" && numeric.matches(path[2]))
            MediaPlatform.FACEBOOK ->
                (host=="fb.watch" && path.size==1 && token.matches(path[0])) ||
                (path.size==2 && path[0] in setOf("reel","reels","videos") && numeric.matches(path[1])) ||
                (path.size==3 && path[1]=="videos" && numeric.matches(path[2])) ||
                (path==listOf("watch") && q["v"]?.let(numeric::matches)==true) ||
                (path==listOf("video.php") && q["v"]?.let(numeric::matches)==true) ||
                (path.size in 2..3 && path[0]=="share" && path.drop(1).all(token::matches))
        }
        if (!valid) return null
        // Preserve only query keys needed for routing; do not store tracking or login tokens.
        val query = q.filter { (key,value) ->
            key in when(platform) {
                MediaPlatform.YOUTUBE -> setOf("v","list","index","t")
                MediaPlatform.FACEBOOK -> setOf("v")
                else -> emptySet()
            } && token.matches(value)
        }.entries.joinToString("&"){it.key+"="+it.value}
        val clean="https://"+host+"/"+path.joinToString("/")+(if(query.isEmpty()) "" else "?"+query)
        return MediaLink(platform,clean)
    }
    fun key(clip: Clip): String = (resolve(clip.url)?.platform?.name ?: "UNKNOWN")+":"+clip.id.ifBlank { clip.url }
    fun publicThumbnail(platform: MediaPlatform?, raw: String): Boolean {
        val u=runCatching{URI(raw)}.getOrNull()?:return false
        if(u.scheme!="https" || u.userInfo!=null || u.port !in listOf(-1,443)) return false
        val host=u.host?.lowercase(Locale.ROOT)?:return false
        val domains=when(platform) {
            MediaPlatform.YOUTUBE -> listOf("ytimg.com","ggpht.com")
            MediaPlatform.INSTAGRAM -> listOf("cdninstagram.com","fbcdn.net")
            MediaPlatform.FACEBOOK -> listOf("fbcdn.net","fbsbx.com")
            MediaPlatform.TIKTOK -> listOf("tiktokcdn.com","tiktokcdn-us.com","tiktokcdn-eu.com","tiktokv.com","tiktokcdn-in.com","byteoversea.com","ibytedtos.com")
            null -> emptyList()
        }
        return domains.any{host==it || host.endsWith("."+it)}
    }
    fun error(text: String): String {
        val m=text.lowercase(Locale.ROOT)
        return when {
            listOf("login required","log in","sign in","private video","private account","requires authentication","cookies","not logged in","login_required").any{it in m} ->
                "المحتوى يتطلب تسجيل الدخول أو غير متاح للعامة."
            listOf("429","too many requests","rate limit").any{it in m} ->
                "طلبات كثيرة للمنصة. حاول مرة أخرى لاحقًا."
            listOf("timed out","timeout","network is unreachable","connection reset","temporary failure").any{it in m} ->
                "تعذر الاتصال. تحقق من الشبكة وحاول مرة أخرى."
            "nazzelha_multiple_items" in m -> "المنشورات متعددة العناصر غير مدعومة حاليًا."
            "nazzelha_no_video" in m -> "لا يوجد فيديو متاح للتحميل في هذا الرابط."
            "unsupported url" in m -> "هذا النوع من الروابط غير مدعوم حاليًا."
            else -> "تعذر إكمال العملية. يمكنك إعادة المحاولة أو نسخ التفاصيل الفنية."
        }
    }
}
