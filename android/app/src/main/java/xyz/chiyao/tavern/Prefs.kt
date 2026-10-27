package xyz.chiyao.tavern

import android.content.Context

/** Settings the user changes in the shell's settings page. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("tavern", Context.MODE_PRIVATE)

    /** Prefix put in front of github.com download URLs, e.g. https://gh.example.com/ ; empty = direct */
    var githubPrefix: String
        get() = sp.getString("githubPrefix", "") ?: ""
        set(v) = sp.edit().putString("githubPrefix", v.trim()).apply()

    /** npm registry; empty = the official one */
    var npmRegistry: String
        get() = sp.getString("npmRegistry", "") ?: ""
        set(v) = sp.edit().putString("npmRegistry", v.trim()).apply()

    /** Clone GitHub repositories (extensions, plugins) through [githubPrefix] as well */
    var gitMirror: Boolean
        get() = sp.getBoolean("gitMirror", false)
        set(v) = sp.edit().putBoolean("gitMirror", v).apply()

    var port: Int
        get() = sp.getInt("port", DEFAULT_PORT)
        set(v) = sp.edit().putInt("port", v).apply()

    /** Build of the SillyTavern package that last started successfully; a new build that fails gets rolled back */
    var lastGoodBuild: Int
        get() = sp.getInt("lastGoodBuild", 0)
        set(v) = sp.edit().putInt("lastGoodBuild", v).apply()

    /** The first-run choice (fresh start or import) has been made */
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    /** Shell versionCode the user said "later" to; not offered again until a newer one appears */
    var shellDeclined: Int
        get() = sp.getInt("shellDeclined", 0)
        set(v) = sp.edit().putInt("shellDeclined", v).apply()

    companion object {
        const val DEFAULT_PORT = 8000
    }
}

object Mirrors {
    private val githubHosts = listOf("https://github.com/", "https://raw.githubusercontent.com/", "https://objects.githubusercontent.com/")

    /** Route a GitHub URL through a prefix-style accelerator (prefix + full original URL). Other URLs pass through. */
    fun github(url: String, prefix: String): String {
        val p = prefix.trim()
        if (p.isEmpty() || githubHosts.none { url.startsWith(it) }) return url
        return p.trimEnd('/') + "/" + url
    }
}
