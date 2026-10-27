package xyz.chiyao.tavern

import org.json.JSONObject

/**
 * latest.json, published by CI next to the releases (tools/manifest.mjs):
 *
 *   st     the newest SillyTavern package: build number (monotonic, from CI), upstream version and commit,
 *          download url, sha256, size, and the oldest shell versionCode that can run it
 *   shell  the newest APK: versionCode, versionName, url, sha256, release notes
 *
 * Either section may be missing while the first build of the other has not been published yet.
 */
data class Manifest(val st: StPackage?, val shell: ShellRelease?) {

    data class StPackage(
        val build: Int,
        val version: String,
        val commit: String,
        val url: String,
        val sha256: String,
        val size: Long,
        val minShell: Int,
    ) {
        /** Directory name under files/st/, also what the UI shows */
        val id get() = "$version-$build"
    }

    data class ShellRelease(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val sha256: String,
        val notes: String,
    )

    companion object {
        fun parse(text: String): Manifest {
            val o = JSONObject(text)
            val st = o.optJSONObject("st")?.let {
                StPackage(
                    build = it.getInt("build"),
                    version = it.getString("version"),
                    commit = it.optString("commit"),
                    url = it.getString("url"),
                    sha256 = it.getString("sha256").lowercase(),
                    size = it.optLong("size"),
                    minShell = it.optInt("minShell", 1),
                )
            }
            val shell = o.optJSONObject("shell")?.let {
                ShellRelease(
                    versionCode = it.getInt("versionCode"),
                    versionName = it.getString("versionName"),
                    url = it.getString("url"),
                    sha256 = it.getString("sha256").lowercase(),
                    notes = it.optString("notes"),
                )
            }
            return Manifest(st, shell)
        }
    }
}

/** What to do with the SillyTavern package on this start, given what is installed. */
sealed class StDecision {
    object UpToDate : StDecision()
    data class Install(val pkg: Manifest.StPackage) : StDecision()
    /** A newer package exists but needs a newer shell; keep running what we have */
    data class NeedsShell(val pkg: Manifest.StPackage) : StDecision()

    companion object {
        /**
         * @param installed build number of the current package, or null when nothing is installed
         * @param bad builds that failed to start before; never tried again automatically
         */
        fun of(m: Manifest.StPackage?, installed: Int?, bad: Set<Int>, shellCode: Int): StDecision {
            if (m == null) return UpToDate
            if (installed != null && m.build <= installed) return UpToDate
            if (m.build in bad) return UpToDate
            if (m.minShell > shellCode) return NeedsShell(m)
            return Install(m)
        }
    }
}
