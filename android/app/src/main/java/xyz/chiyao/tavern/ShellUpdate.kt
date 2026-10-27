package xyz.chiyao.tavern

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.io.File

/** A newer APK of this app: offered after SillyTavern is up, installed only when the user agrees. */
object ShellUpdate {
    const val APK_NAME = "update.apk"

    fun apkFile(ctx: Context) = File(File(ctx.cacheDir, "apk").apply { mkdirs() }, APK_NAME)

    /** The release to offer now, if any: newer than this build and not already declined */
    fun offer(ctx: Context): Manifest.ShellRelease? {
        val s = Tavern.manifest?.shell ?: return null
        if (s.versionCode <= BuildConfig.VERSION_CODE) return null
        if (s.versionCode <= Prefs(ctx).shellDeclined) return null
        return s
    }

    fun decline(ctx: Context, s: Manifest.ShellRelease) { Prefs(ctx).shellDeclined = s.versionCode }

    /** Download (blocking, call off the main thread) and hand to the system installer. */
    fun downloadAndInstall(act: Activity, s: Manifest.ShellRelease, progress: (Long, Long) -> Unit) {
        val f = apkFile(act)
        Net.download(Mirrors.github(s.url, Prefs(act).githubPrefix), f, s.sha256, progress)
        act.runOnUiThread { install(act) }
    }

    /** Sent to the "install unknown apps" setting with the APK downloaded; see [resume] */
    @Volatile private var awaitingPermission = false

    /** True when the system installer was opened; false when the user first has to allow installing apps */
    fun install(act: Activity): Boolean {
        if (!act.packageManager.canRequestPackageInstalls()) {
            awaitingPermission = runCatching {
                act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName)))
            }.isSuccess
            return false
        }
        val uri = Uri.parse("content://${act.packageName}.apk/$APK_NAME")
        act.startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** Back from that setting (Activity.onResume): go on to the installer if it was allowed */
    fun resume(act: Activity) {
        if (!awaitingPermission) return
        awaitingPermission = false
        if (act.packageManager.canRequestPackageInstalls() && apkFile(act).isFile) install(act)
    }
}
