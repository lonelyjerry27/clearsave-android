package app.clearsave

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Hosts the ClearSave web screens in a WebView and answers their requests with the on-phone engine. */
class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pageReady = false
    private var pendingShared: String? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    // "Change…" on the start screen opens Android's own folder chooser. The permission is kept for later launches.
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                Saver.setCustomTree(applicationContext, uri)
            } catch (_: Exception) {   // a few providers refuse lasting access; the old choice stays
            }
        }
        web.evaluateJavascript("window.__csSaveLocation && window.__csSaveLocation()", null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)

        // The pages are bundled in the app and served from a private https origin, so nothing external is loaded as code.
        val loader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
        }
        web.addJavascriptInterface(Bridge(), "CSNative")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            // never navigate away from the app's own pages
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != HOST

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                deliverShared()
            }
        }
        web.loadUrl("https://$HOST/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.__csBack && window.__csBack())") { handled ->
                    if (handled != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        askForPermissions()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        web.removeJavascriptInterface("CSNative")
        super.onDestroy()
    }

    private fun askForPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray())
    }

    /** A link shared into the app from YouTube / X / Instagram's Share menu. */
    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND && i.type == "text/plain") {
            pendingShared = i.getStringExtra(Intent.EXTRA_TEXT)
            deliverShared()
        }
    }

    private fun deliverShared() {
        val text = pendingShared ?: return
        if (!pageReady) return
        pendingShared = null
        web.evaluateJavascript("window.__csShared && window.__csShared(${JSONObject.quote(text)})", null)
    }

    private fun resolve(id: Int, status: Int, text: String) {
        web.post { web.evaluateJavascript("window.__csResolve($id,$status,${JSONObject.quote(text)})", null) }
    }

    /** Called from the page's JavaScript (window.CSNative). Runs on a WebView thread, so work is moved off it. */
    private inner class Bridge {
        @JavascriptInterface
        fun call(id: Int, path: String, body: String) {
            scope.launch {
                val (status, text) = Api.handle(applicationContext, path, body)
                resolve(id, status, text)
            }
        }

        /** What the "Saving to …" line shows. */
        @JavascriptInterface
        fun saveLocation(): String = Saver.label(applicationContext)

        @JavascriptInterface
        fun hasCustomFolder(): Boolean = Saver.customTree(applicationContext) != null

        /** Opens Android's folder chooser; the page is told when it closes. */
        @JavascriptInterface
        fun chooseFolder() {
            runOnUiThread { pickFolder.launch(Saver.customTree(this@MainActivity)) }
        }

        @JavascriptInterface
        fun resetFolder() {
            Saver.setCustomTree(applicationContext, null)
        }

        /** Puts device info and the last downloader output on the clipboard so it can be pasted into a bug report. */
        @JavascriptInterface
        fun copyDiagnostics() {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ClearSave details", Api.diagnostics(applicationContext)))
        }

        @JavascriptInterface
        fun paste(): String {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
        }
    }

    companion object {
        private const val HOST = "appassets.androidplatform.net"
    }
}
