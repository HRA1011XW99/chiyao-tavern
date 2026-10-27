package xyz.chiyao.tavern

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * The start sequence and the state the shell UI shows. One per process.
 *
 *   check     fetch latest.json (5 s at most; offline just means "use what is installed")
 *   download  a newer package, with progress; the user may skip it and start the installed one
 *   onboard   first run only: the user picks a fresh start or importing a backup
 *   start     link folders, write tool config, run server.js, wait until it answers
 *   ready     the UI swaps to SillyTavern
 *
 * A build that never started successfully and fails now is marked bad and the previous one is started instead.
 */
object Tavern {
    private const val TAG = "Tavern"
    private const val MANIFEST_TIMEOUT_MS = 5000
    /** Waiting keeps going while the server keeps printing; this long without output counts as stuck */
    private const val QUIET_LIMIT_MS = 180_000L
    private const val HARD_LIMIT_MS = 15 * 60_000L

    enum class Phase { IDLE, CHECKING, DOWNLOADING, INSTALLING, ONBOARD, STARTING, READY, FAILED, CRASHED }

    @Volatile var phase = Phase.IDLE
        private set
    @Volatile private var message = ""
    @Volatile private var detail = ""
    @Volatile private var done = 0L
    @Volatile private var total = 0L
    /** One-off news for the user, e.g. a rollback; cleared once the UI has shown it */
    @Volatile var notice = ""
    @Volatile var manifest: Manifest? = null
        private set
    @Volatile private var skip = false
    @Volatile private var busy = false
    @Volatile private var server: Server? = null
    @Volatile var port = Prefs.DEFAULT_PORT
        private set

    private val listeners = mutableListOf<() -> Unit>()
    fun onChange(fn: () -> Unit) { synchronized(listeners) { listeners += fn } }
    fun offChange(fn: () -> Unit) { synchronized(listeners) { listeners -= fn } }
    private fun changed() = synchronized(listeners) { listeners.toList() }.forEach { runCatching { it() } }

    private fun set(p: Phase, msg: String, det: String = "") {
        phase = p; message = msg; detail = det; done = 0; total = 0
        changed()
    }

    fun url() = "http://127.0.0.1:$port/"

    fun status(ctx: Context): JSONObject {
        val store = Store(ctx.filesDir)
        val o = JSONObject()
            .put("phase", phase.name)
            .put("message", message)
            .put("detail", detail)
            .put("done", done)
            .put("total", total)
            .put("canSkip", phase == Phase.DOWNLOADING && store.current != null)
            .put("installed", store.current?.id ?: "")
            .put("previous", store.previous?.id ?: "")
            .put("notice", notice)
            .put("log", JSONArray(server?.tail(60) ?: lastTail))
        manifest?.st?.let { o.put("latest", it.id).put("size", it.size) }
        return o
    }

    fun skipDownload() { skip = true }

    fun setManifest(m: Manifest) { manifest = m }

    /**
     * Run the whole sequence in the background; does nothing if it is already running or the server is up.
     * [check] = false skips the update check (restarts after a plugin change).
     */
    fun boot(ctx: Context, check: Boolean = true) {
        val app = ctx.applicationContext
        synchronized(this) {
            if (busy || phase == Phase.READY) return
            busy = true
        }
        startService(app)
        thread(name = "tavern-boot") {
            try {
                run(app, check)
            } catch (e: Exception) {
                Log.e(TAG, "boot failed", e)
                set(Phase.FAILED, "启动失败", e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    private fun run(ctx: Context, check: Boolean) {
        val store = Store(ctx.filesDir)
        val prefs = Prefs(ctx)
        skip = false
        quitting = false

        if (check || store.current == null) checkAndInstall(ctx, store, prefs)

        if (store.current == null) {
            val why = if (manifest == null) "无法连接更新服务器。请检查网络，或在设置中填写 GitHub 加速地址后重试。"
            else "SillyTavern 尚未下载完成，请重试。"
            set(Phase.FAILED, "SillyTavern 不可用", why)
            return
        }
        if (!prefs.onboarded) {
            set(Phase.ONBOARD, "SillyTavern 已就绪")
            return
        }
        start(ctx, store, prefs)
    }

    private fun checkAndInstall(ctx: Context, store: Store, prefs: Prefs) {
        set(Phase.CHECKING, "正在检查更新")
        val m = runCatching { Manifest.parse(Net.text(Mirrors.github(BuildConfig.MANIFEST_URL, prefs.githubPrefix), MANIFEST_TIMEOUT_MS)) }
            .onFailure { Log.w(TAG, "manifest: ${it.message}") }
            .getOrNull()
        if (m != null) manifest = m

        when (val d = StDecision.of(m?.st, store.current?.build, store.bad, BuildConfig.VERSION_CODE)) {
            is StDecision.Install -> install(ctx, store, prefs, d.pkg)
            is StDecision.NeedsShell -> notice = "SillyTavern ${d.pkg.version} 需要更新版本的吃药酒馆，请先更新应用"
            StDecision.UpToDate -> {}
        }
    }

    private fun install(ctx: Context, store: Store, prefs: Prefs, pkg: Manifest.StPackage) {
        val zip = File(ctx.cacheDir, "st-${pkg.build}.zip")
        try {
            set(Phase.DOWNLOADING, if (store.current == null) "正在下载 SillyTavern ${pkg.version}" else "正在下载 SillyTavern ${pkg.version}")
            var shown = 0L
            Net.download(Mirrors.github(pkg.url, prefs.githubPrefix), zip, pkg.sha256, { d, t ->
                val now = System.currentTimeMillis()
                if (now - shown < 250) return@download
                shown = now
                done = d; total = if (t > 0) t else pkg.size
                changed()
            }, { skip })
            set(Phase.INSTALLING, "正在安装 ${pkg.version}")
            var n = 0
            zip.inputStream().use { input ->
                store.install(input, pkg) {
                    if (++n % 500 == 0) { detail = "已解压 $n 个文件"; changed() }
                }
            }
        } catch (e: Cancelled) {
            notice = "已跳过本次更新，下次启动时重试"
        } catch (e: Exception) {
            Log.w(TAG, "install ${pkg.id}: ${e.message}")
            if (store.current == null) throw e
            notice = "新版本安装失败（${e.message}），已使用当前版本启动"
        } finally {
            zip.delete()
        }
    }

    private fun start(ctx: Context, store: Store, prefs: Prefs) {
        val cur = store.current ?: throw IllegalStateException("SillyTavern 不可用")
        port = prefs.port
        set(Phase.STARTING, "正在启动 SillyTavern ${cur.id.substringBeforeLast('-')}", "首次启动新版本需要一到两分钟")
        if (!Server.portFree(port)) {
            set(Phase.FAILED, "端口 $port 被占用", "该端口正被其他程序（例如 Termux 中的 SillyTavern）使用。请关闭该程序，或在设置中更换端口。")
            return
        }
        store.link(cur.dir)
        store.data.mkdirs()
        val tools = Tools(ctx, store, prefs).also { it.prepare() }
        val log = store.newLog()
        val args = listOf(
            "--listen=false", "--port=$port", "--browserLaunchEnabled=false",
            "--dataRoot=${store.data.path}", "--configPath=${store.config.path}",
        )
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        lateinit var srv: Server
        srv = Server(tools.node, cur.dir, args, tools.env(), log) { code ->
            lastTail = srv.tail(60)
            if (server === srv) server = null
            if (ready.get()) set(Phase.CRASHED, "SillyTavern 意外退出（代码 $code）", "")
        }
        server = srv
        srv.start()

        val began = System.currentTimeMillis()
        while (true) {
            if (Server.answers(port)) break
            val now = System.currentTimeMillis()
            val stuck = now - srv.lastOutputAt > QUIET_LIMIT_MS || now - began > HARD_LIMIT_MS
            if (!srv.alive || stuck) {
                lastTail = srv.tail(60)
                srv.stop()
                server = null
                startFailed(ctx, store, prefs, cur, if (stuck) "SillyTavern 启动超时" else "SillyTavern 启动失败")
                return
            }
            if (phase == Phase.STARTING) { detail = srv.tail(1).firstOrNull() ?: detail; changed() }
            Thread.sleep(400)
        }
        ready.set(true)
        prefs.lastGoodBuild = cur.build
        set(Phase.READY, "")
    }

    private fun startFailed(ctx: Context, store: Store, prefs: Prefs, cur: Store.Installed, why: String) {
        if (cur.build != prefs.lastGoodBuild) {
            val back = store.rollback()
            if (back != null) {
                notice = "SillyTavern ${cur.id.substringBeforeLast('-')} 启动失败，已回退到 ${back.id.substringBeforeLast('-')}"
                start(ctx, store, prefs)
                return
            }
        }
        set(Phase.FAILED, why, "")
    }

    @Volatile private var lastTail: List<String> = emptyList()

    fun stop() {
        server?.stop()
        server = null
        set(Phase.IDLE, "")
    }

    fun restart(ctx: Context) {
        val app = ctx.applicationContext
        thread(name = "tavern-restart") {
            stop()
            boot(app, check = false)
        }
    }

    /** "退出" in the notification: stop the server; the activity sees [quitting] and closes */
    @Volatile var quitting = false
        private set

    fun quit() {
        quitting = true
        stop()
    }

    /** Roll back by hand from the failure screen */
    fun rollbackNow(ctx: Context): Boolean {
        val store = Store(ctx.filesDir)
        if (store.previous == null) return false
        server?.stop()
        server = null
        store.rollback()
        set(Phase.IDLE, "")
        boot(ctx)
        return true
    }

    private fun startService(ctx: Context) {
        val i = Intent(ctx, TavernService::class.java)
        runCatching { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) }
            .onFailure { Log.w(TAG, "service: ${it.message}") }
    }
}
