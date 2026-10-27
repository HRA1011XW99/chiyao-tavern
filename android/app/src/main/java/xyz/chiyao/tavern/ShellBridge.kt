package xyz.chiyao.tavern

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * window.Shell in the app's own pages (assets/shell/). Only that WebView gets it: SillyTavern's page and its
 * extensions never see it. Slow work runs on a background thread and answers with an event:
 * window.onShell(type, data).
 */
class ShellBridge(private val act: MainActivity) {
    private val ctx: Context get() = act

    private fun bg(type: String, work: () -> JSONObject) {
        thread(name = "shell-$type") {
            val r = try { work() } catch (e: Plugins.Failed) {
                JSONObject().put("ok", false).put("error", e.message).put("log", e.log)
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
            }
            act.emit(type, r)
        }
    }

    private fun ok() = JSONObject().put("ok", true)

    @JavascriptInterface fun status(): String {
        val prefs = Prefs(ctx)
        val o = Tavern.status(ctx)
            .put("onboarded", prefs.onboarded)
            .put("shellVersion", BuildConfig.VERSION_NAME)
            .put("port", prefs.port)
        ShellUpdate.offer(ctx)?.let { o.put("shellOffer", JSONObject().put("version", it.versionName).put("notes", it.notes)) }
        return o.toString()
    }

    @JavascriptInterface fun boot() = Tavern.boot(ctx)
    @JavascriptInterface fun skip() = Tavern.skipDownload()
    @JavascriptInterface fun restart() = Tavern.restart(ctx)
    @JavascriptInterface fun rollback(): Boolean = Tavern.rollbackNow(ctx)
    @JavascriptInterface fun clearNotice() { Tavern.notice = "" }
    @JavascriptInterface fun openTavern() = act.runOnUiThread { act.showShell(false) }
    @JavascriptInterface fun background() = act.runOnUiThread { act.moveTaskToBack(true) }
    @JavascriptInterface fun quit() = act.runOnUiThread { act.startService(Intent(act, TavernService::class.java).setAction(TavernService.ACTION_QUIT)) }

    @JavascriptInterface fun openUrl(url: String) = act.runOnUiThread {
        if (url.startsWith("https://")) runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    /* —— first run —— */
    @JavascriptInterface fun onboardFresh() {
        Prefs(ctx).onboarded = true
        Tavern.boot(ctx, check = false)
    }

    /** Peek at latest.json for the welcome screen (download size) */
    @JavascriptInterface fun peek() = bg("peek") {
        val m = Manifest.parse(Net.text(Mirrors.github(BuildConfig.MANIFEST_URL, Prefs(ctx).githubPrefix), 8000))
        JSONObject().put("ok", true).put("version", m.st?.version ?: "").put("size", m.st?.size ?: 0)
    }

    /* —— logs —— */
    @JavascriptInterface fun logText(): String {
        val f = Store(ctx.filesDir).latestLogs().firstOrNull() ?: return ""
        return f.readLines().takeLast(400).joinToString("\n")
    }

    @JavascriptInterface fun copyLog() = act.runOnUiThread {
        val text = "吃药酒馆 ${BuildConfig.VERSION_NAME}，酒馆 ${Store(ctx.filesDir).current?.id ?: "未安装"}\n\n" + logText()
        (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("日志", text))
        act.toast("日志已复制")
    }

    /* —— settings —— */
    @JavascriptInterface fun settings(): String {
        val p = Prefs(ctx)
        return JSONObject()
            .put("githubPrefix", p.githubPrefix)
            .put("npmRegistry", p.npmRegistry)
            .put("gitMirror", p.gitMirror)
            .put("port", p.port)
            .toString()
    }

    /** Returns "" or an error message */
    @JavascriptInterface fun saveSettings(json: String): String {
        val o = JSONObject(json)
        val p = Prefs(ctx)
        val prefix = o.optString("githubPrefix").trim()
        if (prefix.isNotEmpty() && !prefix.startsWith("https://")) return "加速地址需要以 https:// 开头"
        val reg = o.optString("npmRegistry").trim()
        if (reg.isNotEmpty() && !reg.startsWith("https://")) return "npm 源需要以 https:// 开头"
        val port = o.optInt("port", p.port)
        if (port !in 1024..65535) return "端口需要在 1024 到 65535 之间"
        p.githubPrefix = prefix
        p.npmRegistry = reg
        p.gitMirror = o.optBoolean("gitMirror")
        p.port = port
        return ""
    }

    /** Check for updates by hand; the answer says whether a newer package is waiting for a restart */
    @JavascriptInterface fun checkUpdate() = bg("checkUpdate") {
        val m = Manifest.parse(Net.text(Mirrors.github(BuildConfig.MANIFEST_URL, Prefs(ctx).githubPrefix), 10_000))
        Tavern.setManifest(m)
        val store = Store(ctx.filesDir)
        val d = StDecision.of(m.st, store.current?.build, store.bad, BuildConfig.VERSION_CODE)
        val r = ok().put("installed", store.current?.id ?: "")
        when (d) {
            is StDecision.Install -> r.put("st", d.pkg.version)
            is StDecision.NeedsShell -> r.put("needsShell", d.pkg.version)
            StDecision.UpToDate -> {}
        }
        ShellUpdate.offer(ctx)?.let { r.put("shell", it.versionName).put("notes", it.notes) }
        r
    }

    /** Restart with the update check on, so a waiting package gets installed */
    @JavascriptInterface fun updateNow() {
        thread { Tavern.stop(); Tavern.boot(ctx) }
    }

    @JavascriptInterface fun shellUpdate() {
        val s = Tavern.manifest?.shell ?: return
        bg("shellUpdate") {
            var shown = 0L
            ShellUpdate.downloadAndInstall(act, s) { d, t ->
                val now = System.currentTimeMillis()
                if (now - shown > 300) { shown = now; act.emit("shellProgress", JSONObject().put("done", d).put("total", t)) }
            }
            ok()
        }
    }

    @JavascriptInterface fun shellDecline() { Tavern.manifest?.shell?.let { ShellUpdate.decline(ctx, it) } }

    /* —— backup —— */
    @JavascriptInterface fun exportBackup() = bg("export") { ok().put("file", Backup(ctx).export()) }

    /** Opens the system file picker; the result arrives as an "import" event */
    @JavascriptInterface fun importBackup() = act.runOnUiThread { act.pickZip(MainActivity.REQ_IMPORT) }

    fun imported(uri: Uri) = bg("import") {
        Tavern.stop()
        val r = Backup(ctx).import(uri) { msg -> act.emit("importProgress", JSONObject().put("message", msg)) }
        Prefs(ctx).onboarded = true
        Tavern.boot(ctx, check = false)
        ok().put("files", r.files).put("backup", r.backup ?: "")
    }

    /* —— server plugins —— */
    @JavascriptInterface fun plugins(): String = Plugins(ctx).list().toString()

    @JavascriptInterface fun pluginAdd(url: String) = bg("plugin") { installed(Plugins(ctx).addGit(url)) }
    @JavascriptInterface fun pluginAddZip() = act.runOnUiThread { act.pickZip(MainActivity.REQ_PLUGIN_ZIP) }
    fun pluginZip(uri: Uri, name: String) = bg("plugin") {
        val n = ctx.contentResolver.openInputStream(uri)?.use { Plugins(ctx).addZip(it, name) }
            ?: throw IllegalStateException("无法打开该文件")
        installed(n)
    }
    @JavascriptInterface fun pluginUpdate(name: String) = bg("plugin") { installed(Plugins(ctx).update(name)) }
    @JavascriptInterface fun pluginToggle(name: String, on: Boolean) = bg("plugin") { Plugins(ctx).setEnabled(name, on); ok().put("name", name) }
    @JavascriptInterface fun pluginRemove(name: String) = bg("plugin") { Plugins(ctx).remove(name); ok().put("name", name) }

    private fun installed(r: Plugins.Installed) = ok().put("name", r.name).put("native", org.json.JSONArray(r.native))
}
