package app.clearsave

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Wraps the yt-dlp engine (Python + QuickJS + ffmpeg, bundled as native libraries).
 * The first start unpacks everything and tries to update yt-dlp, because YouTube changes often.
 */
object Engine {
    private const val TAG = "ClearSaveEngine"
    private const val UPDATE_EVERY_MS = 12 * 3600_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ready = CompletableDeferred<Unit>()
    @Volatile private var failure: String? = null

    fun start(app: Context) {
        scope.launch {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
            } catch (e: Throwable) {
                Log.e(TAG, "engine init failed", e)
                failure = "The downloader engine couldn't start (${e.message ?: e.javaClass.simpleName})."
                ready.complete(Unit)
                return@launch
            }

            val prefs = app.getSharedPreferences("engine", Context.MODE_PRIVATE)
            if (System.currentTimeMillis() - prefs.getLong("lastUpdate", 0L) > UPDATE_EVERY_MS) {
                // Run the update on its own coroutine so a slow network can't block the app for more than a minute.
                val update = scope.launch {
                    try {
                        val status = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
                        Log.i(TAG, "yt-dlp update: $status")
                        prefs.edit().putLong("lastUpdate", System.currentTimeMillis()).apply()
                    } catch (e: Throwable) {
                        Log.w(TAG, "yt-dlp update failed, using the installed version", e)
                    }
                }
                withTimeoutOrNull(60_000) { update.join() }
            }
            ready.complete(Unit)
        }
    }

    /** Suspends until the engine is ready; throws if it failed to start. */
    suspend fun await() {
        ready.await()
        failure?.let { throw IllegalStateException(it) }
    }

    /**
     * Builds a request from a flat list like ["-f", "bestaudio/best", "-x", "--audio-format", "mp3"]:
     * a token starting with "-" is an option, and the token after it (if it doesn't start with "-") is its value.
     */
    private fun build(url: String, tokens: List<String>): YoutubeDLRequest {
        val req = YoutubeDLRequest(url)
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val next = tokens.getOrNull(i + 1)
            if (t.startsWith("-") && next != null && !next.startsWith("-")) {
                req.addOption(t, next)
                i += 2
            } else {
                req.addOption(t)
                i += 1
            }
        }
        return req
    }

    /** Runs yt-dlp and parses the single JSON document it prints (used for link lookups). */
    fun json(url: String, tokens: List<String>): JSONObject {
        val res: YoutubeDLResponse = YoutubeDL.getInstance().execute(
            build(url, listOf("--dump-single-json", "--no-warnings") + tokens)
        )
        val line = res.out.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") }
            ?: throw IllegalStateException("The site returned nothing readable.")
        return JSONObject(line)
    }

    /** Runs a download. [onLine] receives every output line from yt-dlp so progress can be tracked. */
    fun run(url: String, tokens: List<String>, processId: String, onLine: (String, Long) -> Unit): YoutubeDLResponse =
        YoutubeDL.getInstance().execute(build(url, tokens), processId) { _, eta, line -> onLine(line, eta) }

    fun cancel(processId: String) {
        YoutubeDL.getInstance().destroyProcessById(processId)
    }
}
