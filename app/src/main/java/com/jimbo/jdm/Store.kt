package com.jimbo.jdm

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

class Seg(val start: Long, @Volatile var end: Long, @Volatile var done: Long = 0)
class Expired : RuntimeException()

class Dl(val id: Long, var src: String) {
    var name by mutableStateOf("در حال تحلیل لینک…")
    var progress by mutableFloatStateOf(0f)
    var bytes by mutableLongStateOf(0L)
    var total by mutableLongStateOf(0L)
    var speed by mutableLongStateOf(0L)
    var status by mutableStateOf("resolving") // resolving | downloading | paused | done | error
    var msg by mutableStateOf("")
    var job: Job? = null
    var url = ""
    var mime = "application/octet-stream"
    var ranges = false
    var etag: String? = null
    var fixedName: String? = null
    var uri = ""
    var segs: List<Seg> = emptyList()
}

object Store {
    val items = mutableStateListOf<Dl>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var n = 1L
    private var loaded = false
    private const val BUF = 128 * 1024

    private fun dir(ctx: Context) = File(ctx.filesDir, "jdm").apply { mkdirs() }
    private fun part(ctx: Context, d: Dl) = File(dir(ctx), "${d.id}.part")

    /** Loads unfinished downloads from disk so they can be resumed days later. */
    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val f = File(dir(ctx), "index.json")
            if (!f.exists()) return
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val d = Dl(o.getLong("id"), o.getString("src"))
                d.url = o.optString("url"); d.name = o.optString("name", d.name)
                d.mime = o.optString("mime", d.mime); d.total = o.optLong("total")
                d.ranges = o.optBoolean("ranges")
                d.etag = o.optString("etag").ifEmpty { null }
                d.fixedName = o.optString("fixed").ifEmpty { null }; d.uri = o.optString("uri")
                val s = o.optJSONArray("segs") ?: JSONArray()
                d.segs = (0 until s.length()).map { k -> val x = s.getJSONArray(k); Seg(x.getLong(0), x.getLong(1), x.getLong(2)) }
                d.bytes = d.segs.sumOf { it.done }
                if (d.total > 0) d.progress = (d.bytes.toFloat() / d.total).coerceAtMost(1f)
                d.status = if (o.optString("status") == "done") "done" else "paused"
                if (d.status == "done") d.progress = 1f
                items.add(d); n = maxOf(n, d.id + 1)
            }
        }
    }

    @Synchronized
    fun save(ctx: Context) {
        runCatching {
            val a = JSONArray()
            items.toList().forEach { d ->
                a.put(JSONObject().apply {
                    put("id", d.id); put("src", d.src); put("url", d.url); put("name", d.name)
                    put("mime", d.mime); put("total", d.total); put("ranges", d.ranges)
                    put("etag", d.etag ?: ""); put("fixed", d.fixedName ?: ""); put("uri", d.uri); put("status", d.status)
                    put("segs", JSONArray().apply { d.segs.forEach { put(JSONArray(listOf(it.start, it.end, it.done))) } })
                })
            }
            File(dir(ctx), "index.json").writeText(a.toString())
        }
    }

    fun add(ctx: Context, link: String) {
        val c = ctx.applicationContext
        val d = Dl(n++, link); items.add(0, d); start(c, d)
    }

    /** Start or resume (continues from the saved offset of every segment). */
    fun start(ctx: Context, d: Dl) {
        val c = ctx.applicationContext
        if (d.job?.isActive == true) return
        d.msg = ""; d.status = if (d.url.isEmpty()) "resolving" else "downloading"
        c.startForegroundService(Intent(c, DlService::class.java))
        d.job = scope.launch {
            try {
                transfer(c, d)
            } catch (e: CancellationException) {
                if (d.status != "done") d.status = "paused"
                d.speed = 0; save(c); throw e
            } catch (e: Exception) {
                d.status = "error"; d.speed = 0; d.msg = e.message ?: "خطا"; save(c)
            } finally {
                if (items.none { it.status == "resolving" || it.status == "downloading" })
                    c.stopService(Intent(c, DlService::class.java))
            }
        }
    }

    fun pause(d: Dl) { d.job?.cancel() }

    fun remove(ctx: Context, d: Dl) {
        d.job?.cancel(); items.remove(d); part(ctx, d).delete(); save(ctx)
    }

    private fun connect(url: String, range: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", Resolver.UA)
            setRequestProperty("Accept-Encoding", "identity")
            range?.let { setRequestProperty("Range", it) }
            connectTimeout = 15000; readTimeout = 30000
        }

    private fun nameFrom(c: HttpURLConnection, url: String, mime: String): String {
        val cd = c.getHeaderField("Content-Disposition")
        var n = cd?.let { Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)").find(it)?.groupValues?.get(1) }
        if (n == null) n = Uri.parse(url).lastPathSegment ?: "file"
        n = URLDecoder.decode(n, "UTF-8").replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (!n.contains('.')) MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { n += ".$it" }
        return n
    }

    /** Probe size + range support, then split the file into parallel segments. */
    private fun prepare(d: Dl) {
        val c = connect(d.url, "bytes=0-0")
        try {
            val code = c.responseCode
            val mime = (c.contentType ?: "application/octet-stream").substringBefore(';').trim()
            if (code == 206) {
                d.total = c.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: 0L
                d.ranges = d.total > 0
            } else if (code in 200..299) {
                d.total = c.contentLengthLong; d.ranges = false
            } else error("HTTP $code")
            d.mime = mime
            d.etag = c.getHeaderField("ETag")?.takeIf { !it.startsWith("W/") }
            d.name = d.fixedName ?: nameFrom(c, d.url, mime)
        } finally { c.disconnect() }
        val t = d.total
        d.segs = if (d.ranges && t >= 2L * 1024 * 1024) {
            val k = if (t < 64L * 1024 * 1024) 8 else 16
            val sz = (t + k - 1) / k
            (0 until k).map { i -> val s = i * sz; Seg(s, minOf(t - 1, s + sz - 1)) }.filter { it.start <= it.end }
        } else listOf(Seg(0, if (t > 0) t - 1 else Long.MAX_VALUE - 1))
    }

    private suspend fun transfer(ctx: Context, d: Dl) {
        if (d.url.isEmpty()) {
            val list = Resolver.resolve(d.src)
            list.drop(1).forEach { m ->
                val x = Dl(n++, m.url); x.url = m.url; x.fixedName = m.name
                items.add(0, x); start(ctx, x)
            }
            d.url = list.first().url; d.fixedName = list.first().name
        }
        if (d.segs.isEmpty()) prepare(d)
        d.status = "downloading"
        val f = part(ctx, d)
        if (!f.exists() || !d.ranges) d.segs.forEach { it.done = 0 }
        RandomAccessFile(f, "rw").use { if (d.total > 0 && it.length() != d.total) it.setLength(d.total) }
        save(ctx)

        coroutineScope {
            val tk = launch {
                var last = d.segs.sumOf { it.done }; var lt = System.nanoTime(); var i = 0
                while (true) {
                    delay(500)
                    val b = d.segs.sumOf { it.done }; val now = System.nanoTime()
                    d.speed = ((b - last) * 1_000_000_000L / (now - lt)).coerceAtLeast(0)
                    last = b; lt = now; d.bytes = b
                    if (d.total > 0) d.progress = (b.toFloat() / d.total).coerceAtMost(1f)
                    if (++i % 4 == 0) save(ctx)
                }
            }
            d.segs.map { s -> launch { worker(d, s, f) } }.joinAll()
            tk.cancel()
        }
        d.bytes = d.segs.sumOf { it.done }
        finish(ctx, d, f)
    }

    private suspend fun <T> guarded(c: HttpURLConnection, block: () -> T): T = coroutineScope {
        // closes the socket the moment the job is cancelled (pause), so reads don't block
        val w = launch { try { awaitCancellation() } finally { c.disconnect() } }
        try { block() } finally { w.cancel() }
    }

    private suspend fun worker(d: Dl, s: Seg, f: File) {
        var retries = 0; var refreshed = false
        while (true) {
            val len = s.end - s.start + 1
            if (d.ranges && s.done >= len) return
            try {
                if (!d.ranges) s.done = 0
                val from = s.start + s.done
                val c = connect(d.url, if (d.ranges) "bytes=$from-${s.end}" else null)
                if (d.ranges) d.etag?.let { c.setRequestProperty("If-Range", it) }
                guarded(c) {
                    val code = c.responseCode
                    if (code == 403 || code == 404 || code == 410) throw Expired()
                    if (d.ranges && code != 206) throw IOException("سرور ادامه‌ی دانلود را قبول نکرد")
                    if (code !in 200..299) throw IOException("HTTP $code")
                    RandomAccessFile(f, "rw").use { raf ->
                        raf.seek(from)
                        c.inputStream.use { inp ->
                            val b = ByteArray(BUF)
                            while (true) {
                                val r = inp.read(b); if (r < 0) break
                                val w = if (d.ranges) minOf(r.toLong(), len - s.done).toInt() else r
                                raf.write(b, 0, w); s.done += w
                                if (d.ranges && s.done >= len) break
                            }
                        }
                    }
                }
                if (!d.ranges) { s.end = s.start + s.done - 1; d.total = s.done; return }
                if (s.done >= len) return
                if (++retries > 8) throw IOException("اتصال قطع شد")
            } catch (e: Expired) {
                if (refreshed) throw IOException("لینک منقضی شده است")
                refreshed = true
                d.url = Resolver.resolve(d.src).first().url
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (++retries > 8) throw e
                delay(1000)
            }
        }
    }

    private fun finish(ctx: Context, d: Dl, f: File) {
        d.msg = "در حال ذخیره در Download/JDM…"
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(d.name.substringAfterLast('.', "").lowercase())?.let { d.mime = it }
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, d.name)
            put(MediaStore.Downloads.MIME_TYPE, d.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/JDM")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: error("ذخیره‌سازی ممکن نیست")
        try {
            ctx.contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out, 256 * 1024) } }
            cv.clear(); cv.put(MediaStore.Downloads.IS_PENDING, 0)
            ctx.contentResolver.update(uri, cv, null, null)
        } catch (e: Exception) { ctx.contentResolver.delete(uri, null, null); throw e }
        f.delete()
        d.uri = uri.toString()
        d.progress = 1f; d.speed = 0; d.msg = ""; d.status = "done"
        save(ctx)
    }
}
