package xyz.chiyao.tavern

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Server plugins (the plugins/ folder). The app has no terminal, so this is where they are added,
 * switched on and off, updated and removed. Changes take effect after SillyTavern restarts.
 */
class Plugins(private val ctx: Context) {
    private val store = Store(ctx.filesDir)
    private val prefs = Prefs(ctx)

    class Failed(message: String, val log: String) : Exception(message)

    /** @param native dependencies that are native addons: installed, but they cannot load on the phone */
    data class Installed(val name: String, val native: List<String>)

    fun list(): JSONArray {
        val out = JSONArray()
        fun add(dir: File, enabled: Boolean) {
            dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }?.forEach { d ->
                val pkg = runCatching { JSONObject(File(d, "package.json").readText()) }.getOrNull()
                out.put(JSONObject()
                    .put("name", d.name)
                    .put("enabled", enabled)
                    .put("version", pkg?.optString("version") ?: "")
                    .put("description", pkg?.optString("description") ?: "")
                    .put("remote", remote(d) ?: ""))
            }
        }
        add(store.plugins, true)
        add(store.pluginsDisabled, false)
        return out
    }

    /** Clone a plugin repository, install its dependencies and switch server plugins on. */
    fun addGit(url: String): Installed {
        val clean = url.trim()
        if (!Regex("^(https?://|git@)\\S+$").matches(clean)) throw Failed("请填写插件仓库地址", "")
        val name = nameOf(clean)
        val dest = File(store.plugins, name)
        if (dest.exists() || File(store.pluginsDisabled, name).exists()) throw Failed("已存在名为 $name 的插件", "")
        val tools = tools()
        val log = StringBuilder()
        val native = try {
            run(tools, store.plugins, listOf("git", "clone", "--depth", "1", clean, name), log, 10)
            installDeps(tools, dest, log)
        } catch (e: Failed) {
            deleteTree(dest)
            throw e
        }
        enableServerPlugins()
        return Installed(name, native)
    }

    /** A plugin packed as a zip: either the plugin folder itself or its contents. */
    fun addZip(input: InputStream, fileName: String): Installed {
        val tmp = File(ctx.cacheDir, "plugin-import").also { deleteTree(it) }
        try {
            Zips.extract(input, tmp)
            val top = tmp.listFiles()?.filter { it.name != "__MACOSX" } ?: emptyList()
            val src = if (top.size == 1 && top[0].isDirectory) top[0] else tmp
            val name = (if (src === tmp) fileName.substringBeforeLast('.') else src.name).let(::sanitize)
            val dest = File(store.plugins, name)
            if (dest.exists() || File(store.pluginsDisabled, name).exists()) throw Failed("已存在名为 $name 的插件", "")
            store.plugins.mkdirs()
            if (!src.renameTo(dest)) { src.copyRecursively(dest); deleteTree(src) }
            val log = StringBuilder()
            val native = if (File(dest, "node_modules").isDirectory) nativePackages(File(dest, "node_modules"))
            else try { installDeps(tools(), dest, log) } catch (e: Failed) { deleteTree(dest); throw e }
            enableServerPlugins()
            return Installed(name, native)
        } finally {
            deleteTree(tmp)
        }
    }

    fun update(name: String): Installed {
        val dir = find(name) ?: throw Failed("找不到插件 $name", "")
        if (!File(dir, ".git").exists()) throw Failed("该插件不是从仓库安装的，无法更新。请重新导入新版本", "")
        val tools = tools()
        val log = StringBuilder()
        run(tools, dir, listOf("git", "pull", "--ff-only"), log, 5)
        return Installed(name, installDeps(tools, dir, log))
    }

    fun setEnabled(name: String, on: Boolean) {
        val from = File(if (on) store.pluginsDisabled else store.plugins, name)
        val to = File(if (on) store.plugins else store.pluginsDisabled, name)
        if (!from.isDirectory) return
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) throw Failed("操作失败", "")
        if (on) enableServerPlugins()
    }

    fun remove(name: String) {
        find(name)?.let(::deleteTree)
    }

    private fun find(name: String): File? =
        listOf(store.plugins, store.pluginsDisabled).map { File(it, name) }.firstOrNull { it.isDirectory && name == sanitize(name) }

    private fun tools() = Tools(ctx, store, prefs).also { it.prepare() }

    private fun enableServerPlugins() = ConfigYaml.setFile(store.config, "enableServerPlugins", "true")

    /**
     * npm install --omit=dev, with known native packages swapped for pure-JS ones ([REPLACE]).
     * package.json is patched only for the duration of the install, so git pull keeps working.
     *
     * With the build toolchain in the APK, install scripts run and native addons are compiled on the phone
     * (see Tools.prepareToolchain). If that fails, or there is no toolchain, the install is repeated without
     * install scripts: many native addons are optional speed-ups (ws: bufferutil, utf-8-validate) that the
     * code falls back from, so a plugin often works without them. The ones left unbuilt are reported.
     * @return names of the native dependencies that have no compiled addon
     */
    private fun installDeps(tools: Tools, dir: File, log: StringBuilder): List<String> {
        val pj = File(dir, "package.json")
        if (!pj.isFile) return emptyList()
        val original = pj.readText()
        val json = runCatching { JSONObject(original) }.getOrNull() ?: return emptyList()
        val deps = json.optJSONObject("dependencies")
        if (deps == null || deps.length() == 0) return emptyList()
        val install = listOf("npm", "install", "--omit=dev", "--no-package-lock", "--no-audit", "--no-fund")
        try {
            pj.writeText(replaceNative(json))
            if (tools.hasToolchain) {
                tools.prepareToolchain()
                try {
                    run(tools, dir, install, log, 30)
                    return nativePackages(File(dir, "node_modules"))
                } catch (e: Failed) {
                    if (!BUILD_FAILED.containsMatchIn(e.log)) throw e
                    log.append("\n# 原生模块编译失败，跳过安装脚本重新安装\n")
                }
            }
            run(tools, dir, install + "--ignore-scripts", log, 15)
        } finally {
            pj.writeText(original)
        }
        return nativePackages(File(dir, "node_modules"))
    }

    private fun run(tools: Tools, dir: File, cmd: List<String>, log: StringBuilder, minutes: Long) {
        val exe = if (cmd[0] == "npm") listOf(tools.node.path, tools.npmCli.path) else listOf(File(tools.bin, cmd[0]).path)
        val pb = ProcessBuilder(exe + cmd.drop(1)).directory(dir).redirectErrorStream(true)
        pb.environment().putAll(tools.env())
        log.append("$ ").append(cmd.joinToString(" ")).append('\n')
        val p = pb.start()
        // a timeout kills the process and closes the pipe under the reader; that must not crash the app
        val reader = Thread {
            try {
                p.inputStream.bufferedReader().forEachLine { synchronized(log) { log.append(it).append('\n') } }
            } catch (_: java.io.IOException) {
            }
        }
        reader.start()
        if (!p.waitFor(minutes, TimeUnit.MINUTES)) {
            p.destroyForcibly()
            throw Failed("操作超时", log.toString())
        }
        reader.join(2000)
        if (p.exitValue() != 0) throw Failed(explain(cmd[0], log.toString()), log.toString())
    }

    companion object {
        /** Native packages with a pure-JS stand-in that keeps the same API (closely enough for plugins) */
        val REPLACE = mapOf(
            "bcrypt" to "npm:bcryptjs@^2.4.3",
            "canvas" to "npm:@napi-rs/canvas@^0.1.0",
        )

        /** package.json text with direct native deps rewritten and transitive ones overridden */
        fun replaceNative(json: JSONObject): String {
            val o = JSONObject(json.toString())
            val deps = o.optJSONObject("dependencies") ?: JSONObject()
            val overrides = o.optJSONObject("overrides") ?: JSONObject()
            for ((native, alias) in REPLACE) {
                // npm refuses an override for a package you depend on directly; rewrite the dependency instead
                if (deps.has(native)) deps.put(native, alias) else overrides.put(native, alias)
            }
            o.put("dependencies", deps)
            o.put("overrides", overrides)
            return o.toString(2)
        }

        /** npm output of an install script that failed to build a native addon */
        val BUILD_FAILED = Regex("gyp ERR|node-gyp|prebuild-install|cmake-js|npm error command failed", RegexOption.IGNORE_CASE)

        fun explain(tool: String, log: String): String = when {
            BUILD_FAILED.containsMatchIn(log) ->
                "该插件需要编译原生模块，手机上不支持"
            log.contains("EBADPLATFORM") -> "该插件的依赖不支持安卓系统"
            Regex("ETIMEDOUT|ECONNRESET|ENOTFOUND|EAI_AGAIN|Could not resolve host|Failed to connect|timed out", RegexOption.IGNORE_CASE)
                .containsMatchIn(log) -> "网络连接失败。请在设置中更换 npm 源或开启 GitHub 加速后重试"
            Regex("Repository not found|could not read Username|HTTP 404", RegexOption.IGNORE_CASE).containsMatchIn(log) ->
                "找不到该仓库，请检查地址"
            else -> "安装失败，详情见日志"
        }

        /**
         * Packages under node_modules (nested ones too) that are built with node-gyp but have no compiled addon
         * (no .node file under build/); links are not followed
         */
        fun nativePackages(nodeModules: File): List<String> {
            val found = sortedSetOf<String>()
            fun scan(dir: File) {
                val entries = dir.listFiles()?.filter { !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.isDirectory } ?: return
                for (e in entries) {
                    if (e.name.startsWith(".")) continue
                    val pkgs = if (e.name.startsWith("@")) e.listFiles()?.filter { it.isDirectory } ?: emptyList() else listOf(e)
                    for (pkg in pkgs) {
                        if (File(pkg, "binding.gyp").isFile && !built(pkg)) found += pkg.relativeTo(dir).invariantSeparatorsPath
                        File(pkg, "node_modules").takeIf { it.isDirectory }?.let(::scan)
                    }
                }
            }
            scan(nodeModules)
            return found.toList()
        }

        private fun built(pkg: File) =
            File(pkg, "build").walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }.any { it.isFile && it.name.endsWith(".node") }

        fun nameOf(url: String): String =
            sanitize(url.trimEnd('/').substringAfterLast('/').substringAfterLast(':').removeSuffix(".git"))

        fun sanitize(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_').ifEmpty { "plugin" }

        /** origin URL from .git/config, without running git */
        fun remote(dir: File): String? {
            val cfg = File(dir, ".git/config").takeIf { it.isFile }?.readText() ?: return null
            val sec = cfg.substringAfter("[remote \"origin\"]", "").takeIf { it.isNotEmpty() } ?: return null
            return Regex("^\\s*url\\s*=\\s*(\\S+)", RegexOption.MULTILINE).find(sec)?.groupValues?.get(1)
        }
    }
}
