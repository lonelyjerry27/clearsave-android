package app.clearsave

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min

class ApiError(val status: Int, message: String) : Exception(message)

private data class AudioFmt(val codec: String, val lossy: Boolean, val cover: Boolean, val ext: String? = null)

class Job(val id: String) {
    @Volatile var phase = "starting"
    @Volatile var pct = 0.0
    @Volatile var speed: Double? = null
    @Volatile var eta: Long? = null
    @Volatile var error: String? = null
    @Volatile var name: String? = null
    @Volatile var size: Long? = null
    @Volatile var items: Int? = null
    @Volatile var item: Int? = null
    @Volatile var indet = false
    @Volatile var count = 1
    @Volatile var note: String? = null
    @Volatile var location: String? = null
    @Volatile var coverNote: String? = null

    // the last lines yt-dlp printed, so "Copy details" can hand them over when something fails
    private val lines = ArrayDeque<String>()
    fun appendLog(s: String) = synchronized(lines) {
        lines.addLast(s.take(600))
        while (lines.size > 80) lines.removeFirst()
    }
    fun logText(): String = synchronized(lines) { lines.joinToString("\n") }

    fun toJson(): JSONObject = JSONObject().apply {
        put("note", note ?: JSONObject.NULL)
        put("location", location ?: JSONObject.NULL)
        put("coverNote", coverNote ?: JSONObject.NULL)
        put("phase", phase)
        put("pct", pct)
        put("speed", speed ?: JSONObject.NULL)
        put("eta", eta ?: JSONObject.NULL)
        put("error", error ?: JSONObject.NULL)
        put("name", name ?: JSONObject.NULL)
        put("size", size ?: JSONObject.NULL)
        put("items", items ?: JSONObject.NULL)
        put("item", item ?: JSONObject.NULL)
        put("indet", indet)
        put("count", count)
    }
}

/**
 * The same JSON "API" the web version gets from its Python server, answered here on the phone.
 * The web page calls it through window.CSNative.call(...), so the screens are identical.
 */
object Api {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val lock = Any()
    @Volatile private var lastJob: Job? = null

    /** Device, versions and the last yt-dlp output: what's needed to work out why a download failed. */
    fun diagnostics(app: Context): String {
        val ytdlp = try { YoutubeDL.getInstance().version(app) ?: "?" } catch (e: Throwable) { "?" }
        val appVersion = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch (e: Throwable) { "?" }
        return buildString {
            appendLine("ClearSave $appVersion | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("yt-dlp $ytdlp")
            val j = lastJob
            if (j == null) append("No download has run yet.") else {
                appendLine("phase=${j.phase} error=${j.error}")
                append(j.logText())
            }
        }
    }

    private fun hasEmbeddedCover(f: File): Boolean = try {
        val r = MediaMetadataRetriever()
        try { r.setDataSource(f.absolutePath); r.embeddedPicture != null } finally { r.release() }
    } catch (e: Throwable) { false }

    private fun isRetryable(e: Throwable): Boolean {
        val m = (e.message ?: "").lowercase()
        return listOf("403", "forbidden", "timed out", "timeout", "connection", "reset by peer", "unable to connect",
            "unable to download", "network", "proxy", "http error 5", "ssl", "unreachable").any { it in m }
    }

    private val hosts = mapOf(
        "youtube" to setOf("youtube.com", "youtu.be", "youtube-nocookie.com"),
        "x" to setOf("x.com", "twitter.com"),
        "instagram" to setOf("instagram.com", "instagr.am"),
    )
    private val audioFormats = mapOf(
        "mp3" to AudioFmt("mp3", lossy = true, cover = true),
        "m4a" to AudioFmt("m4a", lossy = true, cover = true),
        "aac" to AudioFmt("aac", lossy = true, cover = false),
        "opus" to AudioFmt("opus", lossy = true, cover = false),
        "ogg" to AudioFmt("vorbis", lossy = true, cover = false, ext = "ogg"),
        "flac" to AudioFmt("flac", lossy = false, cover = false),
        "wav" to AudioFmt("wav", lossy = false, cover = false),
        "alac" to AudioFmt("alac", lossy = false, cover = true, ext = "m4a"),
    )
    private val videoContainers = setOf("mp4", "mkv", "webm")
    private val junkExt = setOf("part", "ytdl", "temp", "jpg", "jpeg", "png", "webp")

    // ------------------------------------------------------------------ entry point
    suspend fun handle(app: Context, path: String, body: String): Pair<Int, String> = try {
        when {
            path == "/api/config" ->
                200 to JSONObject().put("locked", false).put("authed", true).put("maxPlaylist", 50).put("maxMinutes", 0).toString()
            path == "/api/info" -> 200 to info(app, JSONObject(body).optString("url")).toString()
            path == "/api/download" -> 200 to download(app, JSONObject(body)).toString()
            path.startsWith("/api/progress/") -> 200 to progress(path.removePrefix("/api/progress/")).toString()
            else -> throw ApiError(404, "Not found.")
        }
    } catch (e: ApiError) {
        e.status to JSONObject().put("error", e.message).toString()
    } catch (e: Throwable) {
        500 to JSONObject().put("error", friendly(e, null, "Something went wrong.")).toString()
    }

    // ------------------------------------------------------------------ helpers
    private fun JSONObject.dbl(k: String): Double? =
        if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null

    private fun JSONObject.str(k: String): String? =
        if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotEmpty() } else null

    private fun detect(raw: String?): Pair<String, String>? {
        var url = (raw ?: "").trim()
        if (url.isEmpty() || url.length > 2000) return null
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) url = "https://$url"
        val host = try { URI(url).host?.lowercase() } catch (e: Exception) { null } ?: return null
        val h = host.replace(Regex("^(www|m|mobile|music)\\."), "")
        for ((name, domains) in hosts) if (h in domains) return url to name
        return null
    }

    private fun friendly(e: Throwable, platform: String?, fallback: String): String {
        val raw = (e.message ?: "").replace(Regex("\u001B\\[[0-9;]*m"), "")
        val line = raw.lines().lastOrNull { it.contains("ERROR:") }?.substringAfter("ERROR:")?.trim()
            ?: raw.lines().firstOrNull { it.isNotBlank() }?.trim()
            ?: fallback
        val low = line.lowercase()
        return when {
            "not a bot" in low || "sign in to confirm" in low ->
                "YouTube is asking to confirm you're not a bot. Try again in a few minutes."
            platform == "instagram" && ("login" in low || "empty media" in low || "cookies" in low) ->
                "Instagram wouldn't share that post. It may be private, or it needs a logged-in session."
            else -> line.take(240)
        }
    }

    private fun parseTime(s: String?): Double? {
        val t = (s ?: "").trim()
        if (t.isEmpty()) return null
        val parts = t.split(":").map { it.toDoubleOrNull() ?: throw ApiError(400, "'$t' isn't a valid time. Use mm:ss.") }
        if (parts.size > 3 || parts.any { it < 0 }) throw ApiError(400, "'$t' isn't a valid time. Use mm:ss.")
        return parts.fold(0.0) { acc, p -> acc * 60 + p }
    }

    private fun queryParam(url: String, key: String): String? {
        val q = try { URI(url).rawQuery } catch (e: Exception) { null } ?: return null
        return q.split("&").map { it.split("=", limit = 2) }
            .firstOrNull { it[0] == key }?.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") }
    }

    private fun approxSize(f: JSONObject, dur: Double?, rateKey: String): Long? {
        var n = f.dbl("filesize")?.takeIf { it > 0 } ?: f.dbl("filesize_approx")?.takeIf { it > 0 }
        if (n == null) {
            val rate = f.dbl(rateKey)
            if (rate != null && rate > 0 && dur != null && dur > 0) n = rate * 125 * dur
        }
        return n?.toLong()
    }

    // ------------------------------------------------------------------ link lookup
    private fun singlePayload(data: JSONObject, url: String, platform: String): JSONObject {
        val formats = data.optJSONArray("formats") ?: JSONArray()
        val dur = data.dbl("duration")

        var audioBest: JSONObject? = null
        for (i in 0 until formats.length()) {
            val f = formats.getJSONObject(i)
            if (f.str("vcodec") == "none" && f.str("acodec") != "none" &&
                (audioBest == null || (f.dbl("abr") ?: 0.0) > (audioBest.dbl("abr") ?: 0.0))
            ) audioBest = f
        }
        val audioSize = audioBest?.let { approxSize(it, dur, "abr") }

        class H(val fps: Double?, val size: Long?, val hdr: Boolean, val s1: Double, val s2: Double)
        val byHeight = sortedMapOf<Int, H>(compareByDescending { it })
        for (i in 0 until formats.length()) {
            val f = formats.getJSONObject(i)
            val h = f.dbl("height")?.toInt() ?: continue
            val vc = f.str("vcodec")
            if (vc == null || vc == "none" || h <= 0) continue
            val s1 = f.dbl("fps") ?: 0.0
            val s2 = f.dbl("tbr") ?: 0.0
            val cur = byHeight[h]
            if (cur == null || s1 > cur.s1 || (s1 == cur.s1 && s2 > cur.s2)) {
                byHeight[h] = H(f.dbl("fps"), approxSize(f, dur, "tbr"), (f.str("dynamic_range") ?: "SDR") != "SDR", s1, s2)
            }
        }
        val videos = JSONArray()
        for ((h, v) in byHeight) {
            val total = v.size?.let { it + (audioSize ?: 0L) }
            videos.put(JSONObject().put("height", h).put("fps", v.fps ?: JSONObject.NULL)
                .put("size", total ?: JSONObject.NULL).put("hdr", v.hdr))
        }
        return JSONObject()
            .put("kind", "video").put("platform", platform).put("url", url)
            .put("id", data.str("id") ?: JSONObject.NULL)
            .put("title", data.str("title") ?: data.str("description")?.take(80) ?: "Untitled")
            .put("channel", data.str("uploader") ?: data.str("channel") ?: JSONObject.NULL)
            .put("duration", dur ?: JSONObject.NULL)
            .put("thumbnail", data.str("thumbnail") ?: JSONObject.NULL)
            .put("videos", videos)
            .put("audio_bitrate", audioBest?.dbl("abr")?.toInt() ?: JSONObject.NULL)
            .put("is_live", data.optBoolean("is_live", false))
    }

    private fun playlistPayload(data: JSONObject, url: String, platform: String): JSONObject {
        val entries = JSONArray()
        val raw = data.optJSONArray("entries") ?: JSONArray()
        for (i in 0 until raw.length()) {            // keep gaps so indices match yt-dlp's numbering
            val e = raw.optJSONObject(i) ?: continue
            var thumb = e.str("thumbnail")
            if (thumb == null) {
                val ts = e.optJSONArray("thumbnails")
                if (ts != null && ts.length() > 0) thumb = ts.optJSONObject(ts.length() - 1)?.str("url")
            }
            if (thumb == null && platform == "youtube" && e.str("id") != null) {
                thumb = "https://i.ytimg.com/vi/${e.str("id")}/mqdefault.jpg"
            }
            entries.put(JSONObject().put("index", i + 1).put("title", e.str("title") ?: "Item ${i + 1}")
                .put("thumbnail", thumb ?: JSONObject.NULL).put("duration", e.dbl("duration") ?: JSONObject.NULL))
        }
        return JSONObject().put("title", data.str("title") ?: "Playlist")
            .put("channel", data.str("uploader") ?: data.str("channel") ?: JSONObject.NULL)
            .put("url", url).put("count", entries.length()).put("entries", entries)
    }

    private fun playlistOnly(pl: JSONObject, platform: String, url: String): JSONObject {
        val first = pl.getJSONArray("entries").optJSONObject(0)
        return JSONObject().put("kind", "playlist").put("platform", platform).put("url", url)
            .put("title", pl.getString("title")).put("channel", pl.opt("channel") ?: JSONObject.NULL)
            .put("thumbnail", first?.opt("thumbnail") ?: JSONObject.NULL)
            .put("videos", JSONArray()).put("playlist", pl)
    }

    // What the link lookup learned about a YouTube video, kept so the download doesn't have to redo it.
    // Rebuilding that (Python start-up, YouTube requests, solving YouTube's JavaScript puzzle) costs 10+ seconds on a phone.
    private val infoCache = ConcurrentHashMap<String, Pair<File, Long>>()
    private const val INFO_MAX_AGE_MS = 20 * 60_000L

    /** First of YouTube's cover-image sizes that exists. yt-dlp would otherwise try ~40 variants one by one. */
    private fun pickThumbnail(id: String?): String? {
        if (id == null) return null
        for (name in listOf("maxresdefault", "hq720", "sddefault", "hqdefault")) {
            val u = "https://i.ytimg.com/vi/$id/$name.jpg"
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.requestMethod = "HEAD"; c.connectTimeout = 4000; c.readTimeout = 4000
                val ok = c.responseCode == 200
                c.disconnect()
                if (ok) return u
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun cacheInfo(app: Context, url: String, data: JSONObject) {
        try {
            val thumb = pickThumbnail(data.str("id"))
            if (thumb != null) {   // leave only the verified cover, so embedding it can't miss
                data.put("thumbnails", JSONArray().put(JSONObject().put("url", thumb).put("id", "cover").put("preference", 0)))
                data.put("thumbnail", thumb)
            }
            val dir = File(app.cacheDir, "info").apply { mkdirs() }
            val now = System.currentTimeMillis()
            dir.listFiles()?.filter { now - it.lastModified() > 3600_000L }?.forEach { it.delete() }
            val f = File(dir, "${UUID.randomUUID()}.json")
            f.writeText(data.toString())
            infoCache[url] = f to now
        } catch (_: Exception) {   // caching is only an optimisation
        }
    }

    private suspend fun info(app: Context, raw: String): JSONObject {
        val (url, platform) = detect(raw) ?: throw ApiError(400, "That doesn't look like a YouTube, X or Instagram link.")
        Engine.await()
        val path = try { URI(url).path ?: "" } catch (e: Exception) { "" }
        val plId = queryParam(url, "list")
        val flat = listOf("--flat-playlist", "--playlist-end", "500")
        try {
            if (platform == "youtube" && path.startsWith("/playlist")) {
                return playlistOnly(playlistPayload(Engine.json(url, flat), url, platform), platform, url)
            }
            if (platform == "youtube") {
                val data = Engine.json(url, listOf("--no-playlist"))
                val out = singlePayload(data, url, platform)
                if (out.optBoolean("is_live")) throw ApiError(400, "Live streams can't be downloaded.")
                cacheInfo(app, url, data)
                data.str("thumbnail")?.let { out.put("thumbnail", it) }
                if (plId != null && !plId.startsWith("RD") && !plId.startsWith("LL") && !plId.startsWith("WL")) {
                    try {   // skip auto-mixes and private lists
                        val plUrl = "https://www.youtube.com/playlist?list=$plId"
                        out.put("playlist", playlistPayload(Engine.json(plUrl, flat), plUrl, platform))
                    } catch (_: Exception) {
                    }
                }
                return out
            }
            // X / Instagram: one post can hold several videos
            var data = Engine.json(url, emptyList())
            val entries = data.optJSONArray("entries")
            if (entries != null && entries.length() > 0) {
                val pl = playlistPayload(data, url, platform)
                if (pl.getInt("count") > 1) return playlistOnly(pl, platform, url)
                data = (0 until entries.length()).firstNotNullOf { entries.optJSONObject(it) }
            }
            return singlePayload(data, url, platform)
        } catch (e: ApiError) {
            throw e
        } catch (e: Throwable) {
            throw ApiError(400, friendly(e, platform, "Couldn't read that link."))
        }
    }

    // ------------------------------------------------------------------ downloads
    private fun progress(id: String): JSONObject =
        jobs[id]?.toJson() ?: throw ApiError(404, "That download expired. Please start again.")

    private fun activeCount() = jobs.values.count { it.phase in setOf("starting", "downloading", "converting") }

    private fun download(app: Context, spec: JSONObject): JSONObject {
        val (url, platform) = detect(spec.optString("url")) ?: throw ApiError(400, "Invalid link.")
        val kind = spec.optString("kind")
        val format = spec.optString("format")
        if (kind != "audio" && kind != "video") throw ApiError(400, "Unknown type.")
        if (kind == "audio" && format !in audioFormats) throw ApiError(400, "Unknown audio format.")
        if (kind == "video" && format !in videoContainers) throw ApiError(400, "Unknown video format.")

        var plUrl: String? = null
        var items: List<Int> = emptyList()
        var plTitle = "ClearSave"
        val pl = spec.optJSONObject("playlist")
        if (pl != null) {
            plUrl = detect(pl.optString("url"))?.first ?: throw ApiError(400, "Invalid playlist link.")
            val arr = pl.optJSONArray("items") ?: JSONArray()
            items = (0 until arr.length()).map { arr.optInt(it) }.filter { it > 0 }.distinct().sorted()
            if (items.isEmpty()) throw ApiError(400, "Pick at least one item.")
            if (items.size > 50) throw ApiError(400, "Please pick at most 50 items at a time.")
            plTitle = pl.optString("title").ifBlank { "ClearSave" }
        } else {
            val s = parseTime(spec.optString("start"))
            val e = parseTime(spec.optString("end"))
            if (s != null && e != null && e <= s) throw ApiError(400, "Trim end must be after the start.")
        }

        val job: Job
        synchronized(lock) {
            if (activeCount() >= 1) throw ApiError(503, "A download is already running. Please wait for it to finish.")
            job = Job(UUID.randomUUID().toString().replace("-", ""))
            jobs[job.id] = job
            lastJob = job
        }
        ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        scope.launch {
            try {
                runJob(app, job, spec, url, platform, kind, format, plUrl, items, plTitle)
            } finally {
                if (activeCount() == 0) app.stopService(Intent(app, DownloadService::class.java))
            }
        }
        return JSONObject().put("id", job.id)
    }

    private suspend fun runJob(
        app: Context, job: Job, spec: JSONObject, url: String, platform: String,
        kind: String, format: String, plUrl: String?, items: List<Int>, plTitle: String,
    ) {
        val isPlaylist = plUrl != null
        val outDir = File(app.externalCacheDir ?: app.cacheDir, "jobs/${job.id}").apply { mkdirs() }
        try {
            Engine.await()
            val nItems = max(items.size, 1)
            if (isPlaylist) { job.items = nItems; job.item = 1 }

            val t = mutableListOf<String>()
            t += listOf("--no-mtime", "-o", File(outDir, (if (isPlaylist) "%(playlist_index)02d - " else "") + "%(title).150B.%(ext)s").absolutePath)
            if (isPlaylist) t += listOf("--playlist-items", items.joinToString(","), "--ignore-errors") else t += "--no-playlist"

            val start = spec.optString("start").trim()
            val end = spec.optString("end").trim()
            val trimmed = !isPlaylist && (start.isNotEmpty() || end.isNotEmpty())
            if (trimmed) {
                t += listOf("--download-sections", "*${start.ifEmpty { "0" }}-${end.ifEmpty { "inf" }}", "--force-keyframes-at-cuts")
                job.indet = true   // ffmpeg does the cutting and reports no percentage
            }

            if (kind == "audio") {
                val fmt = audioFormats.getValue(format)
                t += listOf("-f", "bestaudio/best", "-x", "--audio-format", fmt.codec)
                if (fmt.lossy) {
                    val br = spec.optInt("bitrate", 0)
                    t += listOf("--audio-quality", if (br > 0) "${br}K" else "0")
                }
                // each file gets its own album (otherwise Android names it after the folder and music apps
                // show one shared cover for everything saved there)
                t += listOf("--embed-metadata", "--parse-metadata", "%(title)s:%(meta_album)s")
                if (spec.optBoolean("cover") && fmt.cover) t += listOf("--embed-thumbnail", "--convert-thumbnails", "jpg")
            } else {
                val h = if (spec.isNull("height")) 0 else spec.optInt("height", 0)
                // Above 1080p YouTube only has VP9 and AV1. Phones decode AV1 poorly or not at all (black picture,
                // sound only), and HDR looks wrong without an HDR screen, so prefer VP9 and SDR, and fall back if absent.
                val cap = if (h > 0) "[height<=$h]" else ""
                val sel = "bv*$cap[vcodec!^=av01][dynamic_range=SDR]+ba/bv*$cap[vcodec!^=av01]+ba/bv*$cap+ba/b$cap/bv*+ba/b"
                // Instagram and X video is H.264 + AAC, which a WebM file can't hold, so WebM becomes MKV there
                val cont = if (platform != "youtube" && format == "webm") "mkv" else format
                val sort = mutableListOf("res", "fps", "hdr:12")
                when (cont) {
                    "mp4" -> sort += listOf("vcodec:h264", "acodec:aac", "ext:mp4:m4a")
                    "webm" -> sort += listOf("vcodec:vp9", "acodec:opus", "ext:webm")
                }
                t += listOf("-f", sel, "-S", sort.joinToString(","), "--merge-output-format", cont, "--embed-metadata")
            }

            // --- progress tracking from yt-dlp's output lines
            val progressRe = Regex("\\[download]\\s+([0-9.]+)%")
            val speedRe = Regex("at\\s+([0-9.]+)\\s*([KMG]?)i?B/s")
            val itemRe = Regex("Downloading item (\\d+) of (\\d+)")
            val convRe = Regex("^\\[(Merger|ExtractAudio|VideoConvertor|VideoRemuxer|Fixup\\w*|EmbedThumbnail|Metadata|ThumbnailsConvertor|FFmpeg\\w*)]")
            val nStreams = if (kind == "video") 2 else 1
            var streamIdx = 0
            var itemPos = 1

            job.phase = "downloading"
            val onLine: (String, Long) -> Unit = { line, eta ->
                if (!line.startsWith("[download]") || !line.contains("%")) job.appendLog(line)
                itemRe.find(line)?.let { itemPos = it.groupValues[1].toInt(); streamIdx = 0; job.item = itemPos }
                if (line.contains("[download] Destination:")) streamIdx++
                if (convRe.containsMatchIn(line)) {
                    job.phase = "converting"
                    if (!isPlaylist) { job.indet = true; job.pct = 1.0 }
                } else {
                    progressRe.find(line)?.let { m ->
                        val p = m.groupValues[1].toDoubleOrNull() ?: return@let
                        val frac = min(1.0, (max(streamIdx - 1, 0) + p / 100.0) / nStreams)
                        job.phase = "downloading"
                        job.indet = trimmed
                        job.pct = ((itemPos - 1) + frac) / nItems
                        job.eta = eta.takeIf { it >= 0 }
                        speedRe.find(line)?.let { s ->
                            val mult = when (s.groupValues[2]) { "K" -> 1e3; "M" -> 1e6; "G" -> 1e9; else -> 1.0 }
                            job.speed = (s.groupValues[1].toDoubleOrNull() ?: 0.0) * mult
                        }
                    }
                }
            }

            // YouTube's video links are tied to your connection and can start returning 403 mid-download
            // (Wi-Fi <-> mobile data switch, link expiry, throttling). On such errors, ask for fresh links and
            // resume: yt-dlp keeps the .part files, so a retry continues where the last attempt stopped.
            val common = listOf("--retries", "10", "--fragment-retries", "10")
            val attempts = listOf(
                emptyList(),
                common + "--force-ipv4",
                common + listOf("--force-ipv4", "--extractor-args", "youtube:player_client=tv,web_safari,mweb"),
            )
            val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val cachedInfo: File? = if (isPlaylist || platform != "youtube") null else infoCache[url]
                ?.takeIf { System.currentTimeMillis() - it.second < INFO_MAX_AGE_MS && it.first.exists() }?.first
            var failure: Throwable? = null
            for ((n, extra) in attempts.withIndex()) {
                try {
                    streamIdx = 0; itemPos = 1
                    job.note = if (n == 0) null else "Connection hiccup, retrying ($n of ${attempts.size - 1})"
                    // test hook (debug builds only): make the first attempt fail so the retry path can be exercised
                    val testBreak = if (n == 0 && debuggable && spec.optBoolean("_testFailFirst")) listOf("--proxy", "http://127.0.0.1:9") else emptyList()
                    // First attempt reuses what the link lookup already learned; retries ask YouTube afresh.
                    val reuse = if (n == 0) cachedInfo else null
                    val args = t + extra + testBreak + (if (reuse != null) listOf("--load-info-json", reuse.absolutePath) else emptyList())
                    job.appendLog("attempt ${n + 1}: ${args.joinToString(" ")}")
                    Engine.run(if (reuse != null) emptyList() else listOf(plUrl ?: url), args, job.id, onLine)
                    failure = null
                    break
                } catch (e: Throwable) {
                    failure = e
                    job.appendLog("attempt ${n + 1} failed: ${e.message?.take(600)}")
                    if (n == attempts.lastIndex || !isRetryable(e)) break
                }
            }
            job.note = null
            failure?.let { throw it }

            // --- collect results
            val files = (outDir.listFiles() ?: emptyArray())
                .filter { it.isFile && it.extension.lowercase() !in junkExt }
                .sortedBy { it.name }
                .toMutableList()
            if (files.isEmpty()) throw IllegalStateException("Nothing was produced. The item may be private, or image-only.")

            if (kind == "audio") {   // some codecs produce an extension players don't expect
                val want = audioFormats.getValue(format).ext
                if (want != null) for (i in files.indices) {
                    if (files[i].extension.lowercase() != want) {
                        val renamed = File(outDir, files[i].nameWithoutExtension + "." + want)
                        if (files[i].renameTo(renamed)) files[i] = renamed
                    }
                }
            }

            val final: File
            if (files.size > 1) {
                job.phase = "converting"; job.indet = true
                val safe = plTitle.replace(Regex("[\\\\/:*?\"<>|]+"), "").trim().take(80).ifEmpty { "ClearSave" }
                final = File(outDir, "$safe.zip")
                ZipOutputStream(final.outputStream().buffered()).use { z ->
                    z.setLevel(Deflater.NO_COMPRESSION)   // media is already compressed
                    for (f in files) { z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
                }
            } else final = files[0]

            // did the cover art really make it into the file? say so, rather than leaving the user guessing
            if (kind == "audio" && files.size == 1 && spec.optBoolean("cover") && audioFormats.getValue(format).cover && !hasEmbeddedCover(final)) {
                job.coverNote = "No cover art (the thumbnail wasn't available)"
            }

            job.phase = "converting"; job.indet = true
            job.location = Saver.save(app, final, final.name)
            job.name = final.name; job.size = final.length(); job.count = files.size
            job.pct = 1.0; job.indet = false; job.phase = "done"
        } catch (e: Throwable) {
            job.error = friendly(e, platform, "Something went wrong.")
            job.phase = "error"
        } finally {
            outDir.deleteRecursively()
        }
    }
}
