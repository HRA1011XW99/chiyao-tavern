package xyz.chiyao.tavern

import android.content.Context
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

/** Export everything the user owns to Downloads; import a backup zip (see [ImportPlan] for the layouts). */
class Backup(private val ctx: Context) {
    private val store = Store(ctx.filesDir)

    /** Folders SillyTavern rebuilds by itself */
    private fun skip(f: File) = f.isDirectory && f.name in setOf("_webpack", "_cache")

    fun hasData() = store.data.listFiles()?.any { it.isDirectory && !it.name.startsWith("_") } == true

    /** Returns the file name written to Downloads */
    fun export(prefix: String = "吃药酒馆备份"): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val dl = PublicDownloads.create(ctx, "$prefix-$stamp.zip", "application/zip")
        try {
            Zips.write(dl.out, listOf(
                "data" to store.data,
                "plugins" to store.plugins,
                "plugins-disabled" to store.pluginsDisabled,
                "third-party" to store.thirdParty,
                "config.yaml" to store.config,
            ), ::skip)
            dl.finish()
            return dl.name
        } catch (e: Exception) {
            dl.abort()
            throw e
        }
    }

    /** Zips made on Chinese Windows often store names in GBK without saying so */
    private fun openZip(f: File): ZipFile {
        val utf8 = runCatching { ZipFile(f).also { z -> z.entries().toList() } }
        return utf8.getOrNull() ?: ZipFile(f, java.nio.charset.Charset.forName("GBK"))
    }

    class Result(val kind: ImportPlan.Kind, val files: Int, val backup: String?)

    /**
     * Import a zip. The server must be stopped first. What the zip brings replaces what is here;
     * the current data is exported to Downloads before anything is touched.
     */
    fun import(uri: Uri, progress: (String) -> Unit): Result {
        val tmp = File(ctx.cacheDir, "import.zip")
        try {
            progress("正在读取压缩包")
            ctx.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: throw IllegalStateException("无法打开该文件")
            openZip(tmp).use { zip ->
                val names = zip.entries().toList().map { it.name }
                val plan = ImportPlan.of(names)
                    ?: throw IllegalStateException("无法识别该压缩包。请选择 SillyTavern 文件夹、data 文件夹或本应用导出的备份")
                var backup: String? = null
                if (hasData()) {
                    progress("正在备份现有数据")
                    backup = export("吃药酒馆导入前备份")
                }
                progress("正在导入")
                val root = ctx.filesDir.canonicalFile
                for (t in plan.replaces) deleteTree(File(root, t))
                var n = 0
                for (e in zip.entries()) {
                    val target = plan.target(e.name) ?: continue
                    val out = File(root, target).canonicalFile
                    if (!out.path.startsWith(root.path + File.separator)) throw SecurityException("zip entry outside target: ${e.name}")
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    zip.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                    if (++n % 200 == 0) progress("正在导入（$n 个文件）")
                }
                return Result(plan.kind, n, backup)
            }
        } finally {
            tmp.delete()
        }
    }
}
