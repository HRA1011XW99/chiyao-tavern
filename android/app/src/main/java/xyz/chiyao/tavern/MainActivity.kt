package xyz.chiyao.tavern

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject

/**
 * Two WebViews stacked:
 *   tavern  SillyTavern itself, from the local server. Gets only the download bridge (Saver), keyed.
 *   shell   the app's own pages (assets/shell/): start-up progress, first run, settings, plugins.
 *           Covers the tavern while it is not ready, and on demand (back key, notification "设置").
 */
class MainActivity : Activity() {
    private lateinit var tavern: WebView
    private lateinit var shell: WebView
    private lateinit var saver: Saver
    private lateinit var bridge: ShellBridge
    private var tavernLoaded = false
    private var shellShown = true
    private var pendingFiles: ValueCallback<Array<Uri>>? = null
    private var pendingMedia: PermissionRequest? = null
    private val onTavern: () -> Unit = { runOnUiThread { stateChanged() } }
    private val bars = BarsDrawable(SHELL_BG, SHELL_BG)
    private var tavernTop = SHELL_BG
    private var tavernBottom = SHELL_BG
    private lateinit var sampler: EdgeSampler
    private var tavernRows: Pair<android.graphics.Bitmap, android.graphics.Bitmap>? = null
    private var resumed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        tavern = WebView(this).apply {
            setBackgroundColor(0xFF16171B.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.textZoom = 100
        }
        saver = Saver(this)
        tavern.addJavascriptInterface(saver, "TavernSave")
        // SillyTavern's edge colors from last time, so the margins match its theme before the first sample
        val saved = getSharedPreferences("bars", MODE_PRIVATE)
        tavernTop = saved.getInt("top", SHELL_BG)
        tavernBottom = saved.getInt("bottom", SHELL_BG)
        sampler = EdgeSampler(window, tavern) { top, bottom ->
            if (shellShown) return@EdgeSampler
            tavernRows = top to bottom
            bars.rows(top, bottom)
            val t = BarsDrawable.average(top)
            val b = BarsDrawable.average(bottom)
            BarsDrawable.applyIcons(this, t, b)
            if (t != tavernTop || b != tavernBottom) {
                tavernTop = t
                tavernBottom = b
                saved.edit().putInt("top", t).putInt("bottom", b).apply()
            }
        }
        tavern.webViewClient = TavernClient()
        tavern.webChromeClient = TavernChrome()
        tavern.setDownloadListener { url, _, disposition, mime, _ -> download(url, disposition, mime) }

        shell = WebView(this).apply {
            setBackgroundColor(0xFF16171B.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.textZoom = 100
        }
        bridge = ShellBridge(this)
        shell.addJavascriptInterface(bridge, "Shell")
        shell.webViewClient = object : WebViewClient() {
            // the shell pages never navigate away; links to the web go to the browser
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                if (req.url.scheme == "https") runCatching { startActivity(Intent(Intent.ACTION_VIEW, req.url)) }
                return true
            }
        }
        shell.loadUrl("file:///android_asset/shell/index.html" + if (intent?.action == ACTION_SETTINGS) "#settings" else "")

        val full = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(tavern, full)
        root.addView(shell, FrameLayout.LayoutParams(full))
        setContentView(root)
        fitBars(root)

        Tavern.onChange(onTavern)
        askNotifications()
        if (Prefs(this).onboarded || Store(filesDir).current != null) Tavern.boot(this)
        stateChanged()
    }

    /**
     * Android 15 draws apps under the status bar, the navigation bar and the camera cutout (edge-to-edge), and
     * stops resizing the window for the keyboard. Do the same on every version that allows it and pad the
     * content by all of them, so neither page has anything under the bars or the keyboard.
     */
    private fun fitBars(root: View) {
        root.background = bars
        applyBars()
        if (Build.VERSION.SDK_INT < 30) return
        window.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsets.CONSUMED
        }
    }

    /**
     * The margins follow whichever page is in front: the shell's own background, or SillyTavern's edges as
     * sampled from the screen (EdgeSampler; until its first sample, the averages saved last time).
     */
    private fun applyBars() {
        if (shellShown) {
            sampler.stop()
            bars.solid(SHELL_BG, SHELL_BG)
            BarsDrawable.applyIcons(this, SHELL_BG, SHELL_BG)
            return
        }
        val rows = tavernRows
        if (rows != null) bars.rows(rows.first, rows.second) else bars.solid(tavernTop, tavernBottom)
        BarsDrawable.applyIcons(this, tavernTop, tavernBottom)
        // without edge-to-edge (before Android 11) the system draws its own bars and there are no margins
        if (resumed && Build.VERSION.SDK_INT >= 30) sampler.start()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (!shellShown && Build.VERSION.SDK_INT >= 30) sampler.start()
        ShellUpdate.resume(this)
    }

    override fun onPause() {
        resumed = false
        sampler.stop()
        super.onPause()
    }

    override fun onDestroy() {
        Tavern.offChange(onTavern)
        sampler.stop()
        tavern.destroy()
        shell.destroy()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_SETTINGS) {
            showShell(true)
            shell.evaluateJavascript("window.go && go('settings')", null)
        }
    }

    private fun stateChanged() {
        if (Tavern.quitting) { finishAndRemoveTask(); return }
        when (Tavern.phase) {
            Tavern.Phase.READY -> {
                if (!tavernLoaded) {
                    tavernLoaded = true
                    tavern.loadUrl(Tavern.url())
                    showShell(false)
                    // the shell page that would show it is hidden now (skipped update, rollback, needs a newer app)
                    if (Tavern.notice.isNotEmpty()) {
                        Toast.makeText(this, Tavern.notice, Toast.LENGTH_LONG).show()
                        Tavern.notice = ""
                    }
                    // a newer app is out: bring the shell back so its update dialog is seen
                    if (ShellUpdate.offer(this) != null) showShell(true)
                }
            }
            Tavern.Phase.IDLE -> {}
            else -> {
                // the server went away: the tavern page is dead; reload it once the server is back
                if (Tavern.phase != Tavern.Phase.CHECKING) tavernLoaded = false
                if (Tavern.phase in setOf(Tavern.Phase.FAILED, Tavern.Phase.CRASHED, Tavern.Phase.STARTING)) showShell(true)
            }
        }
        emit("status", JSONObject(bridge.status()))
    }

    fun showShell(on: Boolean) {
        if (on == shellShown) return
        shellShown = on
        shell.visibility = if (on) View.VISIBLE else View.GONE
        applyBars()
        if (on) shell.evaluateJavascript("window.onShown && onShown()", null)
    }

    /** window.onShell(type, data) in the shell page */
    fun emit(type: String, data: JSONObject) = runOnUiThread {
        shell.evaluateJavascript("window.onShell && onShell(${JSONObject.quote(type)}, $data)", null)
    }

    fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    /**
     * Back: the shell page closes its own layers first, then hides itself if SillyTavern is up.
     * On SillyTavern, back goes back in its history; with nothing left it opens the shell menu.
     */
    @Deprecated("Activity API; the target SDK does not force predictive back yet")
    override fun onBackPressed() {
        if (shellShown) {
            shell.evaluateJavascript("(function(){try{return !!(window.back&&back())}catch(e){return false}})()") { handled ->
                if (handled == "true") return@evaluateJavascript
                if (Tavern.phase == Tavern.Phase.READY) showShell(false) else moveTaskToBack(true)
            }
            return
        }
        if (tavern.canGoBack()) { tavern.goBack(); return }
        showShell(true) // with nothing open, the shell page shows its menu while SillyTavern runs
    }

    private fun isOwn(uri: Uri) = uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == Tavern.port

    private fun injectSave(view: WebView, url: String) {
        if (!isOwn(Uri.parse(url))) return
        val js = assets.open("inject/save.js").bufferedReader().use { it.readText() }
            .replace("'__KEY__'", JSONObject.quote(saver.key))
        view.evaluateJavascript(js, null)
    }

    private fun download(url: String, disposition: String?, mime: String?) {
        val uri = Uri.parse(url)
        if (isOwn(uri)) {
            val name = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)""", RegexOption.IGNORE_CASE).find(disposition ?: "")
                ?.groupValues?.get(1)?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) } ?: ""
            tavern.evaluateJavascript("window.__tavernSaveUrl && __tavernSaveUrl(${JSONObject.quote(url)}, ${JSONObject.quote(name)})", null)
            return
        }
        // an external file: let the browser deal with it
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

    private inner class TavernClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            if (isOwn(req.url)) return false
            try { startActivity(Intent(Intent.ACTION_VIEW, req.url)) } catch (_: ActivityNotFoundException) {}
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            injectSave(view, url)
        }

        /** The renderer died (low memory): throw the view's state away and load the page again */
        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            recreate()
            return true
        }
    }

    private inner class TavernChrome : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            if (!isOwn(request.origin)) return request.deny()
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE !in request.resources) return request.deny()
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                return request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            }
            pendingMedia = request
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            pendingFiles?.onReceiveValue(null)
            pendingFiles = callback
            // createIntent takes the first accept entry as a MIME type; extensions (.png, .json) would block every file
            val intent = params.createIntent()
            if (intent.type?.contains('/') != true) intent.type = "*/*"
            if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            return try {
                @Suppress("DEPRECATION") startActivityForResult(intent, REQ_FILES)
                true
            } catch (_: ActivityNotFoundException) {
                pendingFiles = null
                false
            }
        }
    }

    fun pickZip(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        try {
            @Suppress("DEPRECATION") startActivityForResult(i, req)
        } catch (_: ActivityNotFoundException) {
            toast("没有可用的文件选择器")
        }
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: "plugin.zip"

    @Deprecated("Activity API; enough for picking files")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        when (code) {
            REQ_FILES -> {
                val clip = data?.clipData
                val picked = when {
                    result != RESULT_OK -> null
                    clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                    else -> WebChromeClient.FileChooserParams.parseResult(result, data)
                }
                pendingFiles?.onReceiveValue(picked)
                pendingFiles = null
            }
            REQ_IMPORT -> {
                val uri = data?.data
                if (result == RESULT_OK && uri != null) bridge.imported(uri) else emit("import", JSONObject().put("ok", false).put("cancelled", true))
            }
            REQ_PLUGIN_ZIP -> {
                val uri = data?.data
                if (result == RESULT_OK && uri != null) bridge.pluginZip(uri, displayName(uri))
                else emit("plugin", JSONObject().put("ok", false).put("cancelled", true))
            }
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        val ok = results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }
        if (code == REQ_MIC) pendingMedia?.let { if (ok) it.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else it.deny() }
            .also { pendingMedia = null }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
    }

    companion object {
        const val ACTION_SETTINGS = "xyz.chiyao.tavern.SETTINGS"
        /** background of the app's own pages (assets/shell/shell.css --bg) */
        const val SHELL_BG = 0xFF16171B.toInt()
        const val REQ_FILES = 1
        const val REQ_IMPORT = 2
        const val REQ_PLUGIN_ZIP = 3
        private const val REQ_MIC = 4
        private const val REQ_NOTIFY = 5
    }
}
