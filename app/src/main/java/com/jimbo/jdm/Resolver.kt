package com.jimbo.jdm

import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Media(val url: String, val name: String? = null)

object Resolver {
    const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    private const val BOT = "TelegramBot (like TwitterBot)"

    private fun get(u: String, ua: String = UA): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", ua)
        c.connectTimeout = 15000; c.readTimeout = 20000
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().readText()
    }

    private fun unesc(s: String) = s.replace("&amp;", "&")

    fun resolve(link: String): List<Media> {
        val u = Uri.parse(link)
        val h = (u.host ?: "").removePrefix("www.").lowercase()
        return when {
            h == "t.me" || h == "telegram.me" -> telegram(u)
            h.endsWith("twitter.com") || h == "x.com" -> twitter(link)
            h.endsWith("instagram.com") -> insta(u)
            else -> listOf(Media(link))
        }
    }

    private fun telegram(u: Uri): List<Media> {
        val seg = u.pathSegments.filter { it != "s" }
        if (seg.firstOrNull() == "c") error("کانال خصوصی پشتیبانی نمی‌شود")
        if (seg.size < 2) error("لینک پست تلگرام معتبر نیست")
        val html = get("https://t.me/${seg[0]}/${seg[1]}?embed=1&mode=tme")
        val v = Regex("<video[^>]*\\ssrc=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        val p = Regex("tgme_widget_message_photo_wrap[^>]*url\\('([^']+)'\\)").find(html)?.groupValues?.get(1)
        val m = v ?: p ?: error("فایل عمومی در این پست پیدا نشد")
        return listOf(Media(unesc(m)))
    }

    private fun twitter(link: String): List<Media> {
        val id = Regex("status(?:es)?/(\\d+)").find(link)?.groupValues?.get(1) ?: error("لینک توییت معتبر نیست")
        val media = JSONObject(get("https://api.fxtwitter.com/status/$id"))
            .optJSONObject("tweet")?.optJSONObject("media") ?: error("این توییت رسانه ندارد")
        val all = media.optJSONArray("all") ?: error("این توییت رسانه ندارد")
        return (0 until all.length()).map {
            val o = all.getJSONObject(it)
            Media(o.getString("url"), "twitter_${id}_${it + 1}" + if (o.optString("type") == "photo") ".jpg" else ".mp4")
        }
    }

    private fun insta(u: Uri): List<Media> {
        val seg = u.pathSegments
        val i = seg.indexOfFirst { it in listOf("p", "reel", "reels", "tv") }
        val code = seg.getOrNull(i + 1) ?: error("لینک اینستاگرام معتبر نیست")
        val hosts = listOf("https://www.instagram.com/p/$code/", "https://www.ddinstagram.com/p/$code/")
        for (h in hosts) {
            val html = runCatching { get(h, BOT) }.getOrNull() ?: continue
            val a = Regex("property=\"og:video(?::secure_url)?\"\\s+content=\"([^\"]+)\"").find(html)
                ?: Regex("content=\"([^\"]+)\"\\s+property=\"og:video(?::secure_url)?\"").find(html)
            if (a != null) return listOf(Media(unesc(a.groupValues[1]), "instagram_$code.mp4"))
        }
        error("ویدیوی اینستاگرام پیدا نشد (ممکن است پست خصوصی باشد)")
    }
}
