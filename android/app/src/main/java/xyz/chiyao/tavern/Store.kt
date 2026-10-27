package xyz.chiyao.tavern

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/**
 * Everything the app keeps under filesDir. Updates only ever replace st/<id>/; the user's things live beside it:
 *
 *   st/<id>/            unpacked SillyTavern packages: the current one and the previous one (for rollback)
 *   st/state.json       which is current / previous, and builds that failed to start
 *   data/               --dataRoot
 *   config.yaml         --configPath
 *   plugins/            server plugins; st/<id>/plugins links here
 *   plugins-disabled/   plugins switched off in the app
 *   third-party/        extensions installed "for all users"; st/<id>/public/scripts/extensions/third-party links here
 *   home/               HOME for git and npm (.gitconfig, .npmrc, npm cache)
 *   logs/               one file per run, the last [LOG_KEEP] kept
 */
class Store(val root: File) {
    val stDir = File(root, "st")
    val data = File(root, "data")
    val config = File(root, "config.yaml")
    val plugins = File(root, "plugins")
    val pluginsDisabled = File(root, "plugins-disabled")
    val thirdParty = File(root, "third-party")
    val home = File(root, "home")
    val logs = File(root, "logs")
    private val stateFile = File(stDir, "state.json")

    data class Installed(val id: String, val build: Int, val dir: File)

    private fun state(): JSONObject =
        runCatching { JSONObject(stateFile.readText()) }.getOrElse { JSONObject() }

    private fun save(s: JSONObject) {
        stDir.mkdirs()
        val tmp = File(stDir, "state.json.tmp")
        tmp.writeText(s.toString())
        if (!tmp.renameTo(stateFile)) { stateFile.delete(); tmp.renameTo(stateFile) }
    }

    private fun installed(o: JSONObject?): Installed? {
        o ?: return null
        val id = o.optString("id").ifEmpty { return null }
        val dir = File(stDir, id)
        return if (File(dir, "server.js").isFile) Installed(id, o.optInt("build"), dir) else null
    }

    val current: Installed? get() = installed(state().optJSONObject("current"))
    val previous: Installed? get() = installed(state().optJSONObject("previous"))

    val bad: Set<Int>
        get() {
            val a = state().optJSONArray("bad") ?: return emptySet()
            return (0 until a.length()).map { a.getInt(it) }.toSet()
        }

    /**
     * Unpack a downloaded package and make it current; the old current becomes previous.
     * Unpacks into a temporary folder first so an interrupted install never looks like a good one.
     */
    @Synchronized
    fun install(zip: InputStream, pkg: Manifest.StPackage, onEntry: (String) -> Unit = {}) {
        stDir.mkdirs()
        val dest = File(stDir, pkg.id)
        val tmp = File(stDir, pkg.id + ".tmp")
        deleteTree(tmp)
        Zips.extract(zip, tmp, onEntry)
        if (!File(tmp, "server.js").isFile) {
            deleteTree(tmp)
            throw IllegalStateException("下载的安装包不完整")
        }
        deleteTree(dest)
        if (!tmp.renameTo(dest)) throw IllegalStateException("安装失败")
        val s = state()
        val cur = s.optJSONObject("current")
        if (cur != null && cur.optString("id") != pkg.id) s.put("previous", cur)
        s.put("current", JSONObject().put("id", pkg.id).put("build", pkg.build))
        save(s)
        prune()
    }

    /**
     * The current package would not start: remember its build as bad and go back to the previous one.
     * Returns the package now current, or null when there is nothing to go back to.
     */
    @Synchronized
    fun rollback(): Installed? {
        val s = state()
        val cur = installed(s.optJSONObject("current"))
        val prev = installed(s.optJSONObject("previous")) ?: return null
        if (cur != null) {
            val badList = s.optJSONArray("bad") ?: JSONArray()
            badList.put(cur.build)
            s.put("bad", badList)
        }
        s.put("current", s.getJSONObject("previous"))
        s.remove("previous")
        save(s)
        prune()
        return prev
    }

    /** Delete package folders that are neither current nor previous, and half-finished installs. */
    private fun prune() {
        val keep = setOfNotNull(current?.id, previous?.id)
        stDir.listFiles()?.filter { it.isDirectory && it.name !in keep }?.forEach(::deleteTree)
    }

    /**
     * Point the package's plugins/ and third-party/ at the persistent folders, so updates keep them.
     * The package's own plugins/package.json ({"type":"commonjs"}) is what lets CommonJS plugins load;
     * it is copied over the first time.
     */
    fun link(pkg: File) {
        plugins.mkdirs()
        thirdParty.mkdirs()
        linkDir(File(pkg, "plugins"), plugins, keep = listOf("package.json"))
        linkDir(File(pkg, "public/scripts/extensions/third-party"), thirdParty, keep = emptyList())
    }

    private fun linkDir(inPkg: File, target: File, keep: List<String>) {
        val p = inPkg.toPath()
        if (Files.isSymbolicLink(p)) {
            if (Files.readSymbolicLink(p) == target.toPath()) return
            Files.delete(p)
        } else if (inPkg.exists()) {
            for (name in keep) {
                val from = File(inPkg, name)
                val to = File(target, name)
                if (from.isFile && !to.exists()) from.copyTo(to)
            }
            deleteTree(inPkg)
        }
        inPkg.parentFile?.mkdirs()
        Files.createSymbolicLink(p, target.toPath())
    }

    fun newLog(): File {
        logs.mkdirs()
        val old = logs.listFiles()?.filter { it.name.endsWith(".log") }?.sortedBy { it.name } ?: emptyList()
        old.dropLast(LOG_KEEP - 1).forEach { it.delete() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())
        return File(logs, "$stamp.log")
    }

    fun latestLogs(): List<File> =
        logs.listFiles()?.filter { it.name.endsWith(".log") }?.sortedByDescending { it.name } ?: emptyList()

    companion object {
        const val LOG_KEEP = 5
    }
}
