package xyz.chiyao.tavern

import android.content.Context
import java.io.File
import java.nio.file.Files

/**
 * Node, git and npm as SillyTavern (and the plugin manager) see them.
 *
 * Node and git are executables shipped as lib*.so in nativeLibraryDir, the only place an app may execute
 * from. Programs call them by name ("git", "node"), so bin/ and git-core/ hold links with the real names.
 * nativeLibraryDir moves on every app update, so the links are rebuilt on every start.
 *
 *   bin/node, bin/git        → nativeLibraryDir
 *   LD_LIBRARY_PATH          nativeLibraryDir too: Node needs libc++_shared.so, which the linker of an
 *                            executable does not look for among the app's libraries by itself
 *   npm/                     npm, unpacked from the APK; run as `node npm-cli.js` (files under filesDir
 *                            cannot be executed, so there is no npm wrapper script on PATH)
 *   git-core/git, git-remote-http(s)   GIT_EXEC_PATH: git runs its helpers and itself from here
 *   cacert.pem               CA bundle for git's https (Android has no file git would find on its own)
 *
 * The build toolchain for npm native addons ([prepareToolchain], only when a plugin is installed):
 *   bin/clang, clang++, cc, c++, ld.lld, llvm-ar, ar   → libllvm.so (one executable that acts by its name)
 *   bin/python3, python → libpython.so; bin/make → libmake.so    (node-gyp runs gyp with python, then make)
 *   toolchain/             unpacked from the APK: sysroot/, clang/ (resource dir), python/, node/ (headers)
 *   home/.config/clang/    clang.cfg and clang++.cfg point clang at the sysroot and resource dir
 *   npm_config_script_shell → libtavernsh.so: runs install scripts with node_modules/.bin commands as shell
 *                            functions, since those files cannot be executed (tools/tavern-sh/tavern-sh.c)
 */
class Tools(private val ctx: Context, private val store: Store, private val prefs: Prefs) {
    private val root = store.root
    private val native = File(ctx.applicationInfo.nativeLibraryDir)
    val bin = File(root, "bin")
    val gitCore = File(root, "git-core")
    val npmDir = File(root, "npm")
    val cacert = File(root, "cacert.pem")
    val nodeCompat = File(root, "node-compat.cjs")
    val templates = File(root, "git-templates")
    val node = File(native, "libnode.so")
    val npmCli = File(npmDir, "npm/bin/npm-cli.js")
    val toolchain = File(root, "toolchain")

    /** The APK carries a toolchain for this ABI (release builds may leave it out) */
    val hasToolchain get() = File(native, "libllvm.so").isFile

    fun prepare() {
        bin.mkdirs(); gitCore.mkdirs(); templates.mkdirs(); store.home.mkdirs()
        link(File(bin, "node"), File(native, "libnode.so"))
        link(File(bin, "git"), File(native, "libgit.so"))
        link(File(gitCore, "git"), File(native, "libgit.so"))
        link(File(gitCore, "git-remote-http"), File(native, "libgit-remote-http.so"))
        link(File(gitCore, "git-remote-https"), File(native, "libgit-remote-http.so"))
        unpackAssets()
        File(store.home, ".gitconfig").writeText(gitConfig(prefs))
        File(store.home, ".npmrc").writeText(npmrc(prefs))
    }

    fun env(): Map<String, String> = vars(root, bin, gitCore, templates, cacert, store.home, ctx.cacheDir, native) +
        if (hasToolchain) toolchainVars(bin, toolchain, native) else emptyMap()

    /** Links, files and clang config for building native addons; unpacks again only when the APK changed */
    fun prepareToolchain() {
        if (!hasToolchain) return
        for (name in listOf("clang", "clang++", "cc", "c++", "gcc", "g++", "ld.lld", "ld", "llvm-ar", "ar")) {
            link(File(bin, name), File(native, "libllvm.so"))
        }
        link(File(bin, "python3"), File(native, "libpython.so"))
        link(File(bin, "python"), File(native, "libpython.so"))
        link(File(bin, "make"), File(native, "libmake.so"))
        // for programs that spawn node-gyp directly rather than through the script shell (node-gyp-build)
        link(File(bin, "node-gyp"), File(native, "libtavernsh.so"))
        val stamp = File(toolchain, ".stamp")
        val want = stampValue()
        if (!(stamp.isFile && stamp.readText() == want)) {
            deleteTree(toolchain)
            ctx.assets.open("toolchain-${abi()}.zip").use { Zips.extract(it, toolchain) }
            stamp.writeText(want)
        }
        val cfg = File(store.home, ".config/clang").apply { mkdirs() }
        val text = clangConfig(toolchain)
        for (name in listOf("clang", "clang++", "cc", "c++", "gcc", "g++")) File(cfg, "$name.cfg").writeText(text)
    }

    /** "arm64-v8a" or "x86_64": the ABI whose libraries were installed (nativeLibraryDir ends in lib/arm64 or lib/x86_64) */
    private fun abi() = if (native.name == "arm64") "arm64-v8a" else native.name

    private fun stampValue() = BuildConfig.VERSION_CODE.toString() + ":" + ctx.packageManager
        .getPackageInfo(ctx.packageName, 0).lastUpdateTime

    private fun link(at: File, target: File) {
        val p = at.toPath()
        if (Files.isSymbolicLink(p) && Files.readSymbolicLink(p) == target.toPath()) return
        Files.deleteIfExists(p)
        Files.createSymbolicLink(p, target.toPath())
    }

    /** npm and the CA bundle come with the APK; unpack again only when the APK changed */
    private fun unpackAssets() {
        val stamp = File(root, "assets.stamp")
        val want = stampValue()
        if (stamp.isFile && stamp.readText() == want && File(npmDir, "npm/bin/npm-cli.js").isFile && cacert.isFile && nodeCompat.isFile) return
        deleteTree(npmDir)
        ctx.assets.open("npm.zip").use { Zips.extract(it, npmDir) }
        ctx.assets.open("cacert.pem").use { input -> cacert.outputStream().use { input.copyTo(it) } }
        ctx.assets.open("node-compat.cjs").use { input -> nodeCompat.outputStream().use { input.copyTo(it) } }
        stamp.writeText(want)
    }

    companion object {
        fun vars(root: File, bin: File, gitCore: File, templates: File, cacert: File, home: File, tmp: File, libs: File): Map<String, String> {
            val system = System.getenv("PATH") ?: "/system/bin:/system/xbin"
            return mapOf(
                "PATH" to "${bin.path}:${gitCore.path}:$system",
                "LD_LIBRARY_PATH" to libs.path,
                // fills in what Node lacks on Android (assets/node-compat.cjs)
                "NODE_OPTIONS" to "--require=${File(root, "node-compat.cjs").path}",
                "HOME" to home.path,
                "TMPDIR" to tmp.path,
                "GIT_EXEC_PATH" to gitCore.path,
                "GIT_TEMPLATE_DIR" to templates.path,
                "GIT_SSL_CAINFO" to cacert.path,
                "GIT_CONFIG_NOSYSTEM" to "1",
                "SSL_CERT_FILE" to cacert.path,
                "npm_config_update_notifier" to "false",
                "npm_config_fund" to "false",
                "npm_config_audit" to "false",
                "TAVERN_ROOT" to root.path,
            )
        }

        /** node-gyp's view: compilers, python, Node headers, and the script shell for install scripts */
        fun toolchainVars(bin: File, toolchain: File, libs: File): Map<String, String> = mapOf(
            "npm_config_script_shell" to File(libs, "libtavernsh.so").path,
            "CC" to "clang", "CXX" to "clang++", "AR" to "llvm-ar", "LINK" to "clang++",
            "PYTHONHOME" to File(toolchain, "python").path,
            "PYTHONDONTWRITEBYTECODE" to "1",
            "npm_config_python" to File(bin, "python3").path,
            "npm_config_nodedir" to File(toolchain, "node").path,
            // gyp's Android support otherwise expects a full NDK next to Node
            "GYP_DEFINES" to "android_ndk_path=''",
            // Termux's make looks for a shell under Termux's prefix
            "MAKEFLAGS" to "SHELL=/system/bin/sh",
        )

        fun clangConfig(toolchain: File): String =
            "--sysroot=${File(toolchain, "sysroot").path}\n" +
                "-resource-dir=${File(toolchain, "clang").path}\n" +
                "-fuse-ld=lld\n"

        fun gitConfig(p: Prefs): String = buildString {
            append("[user]\n\tname = Tavern\n\temail = tavern@localhost\n")
            append("[init]\n\tdefaultBranch = main\n")
            if (p.gitMirror && p.githubPrefix.isNotBlank()) {
                append("[url \"${p.githubPrefix.trim().trimEnd('/')}/https://github.com/\"]\n")
                append("\tinsteadOf = https://github.com/\n")
            }
        }

        fun npmrc(p: Prefs): String =
            if (p.npmRegistry.isBlank()) "" else "registry=${p.npmRegistry.trim()}\n"
    }
}
