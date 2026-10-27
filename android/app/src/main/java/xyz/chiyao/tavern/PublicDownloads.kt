package xyz.chiyao.tavern

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/**
 * A file in the phone's public Downloads folder. Android 10+ writes through MediaStore (no permission);
 * older versions write the folder directly and need WRITE_EXTERNAL_STORAGE, which the caller must hold.
 */
class PublicDownloads private constructor(
    private val ctx: Context, val out: OutputStream, private val uri: Uri?, private val file: File?, val name: String,
) {
    fun finish() {
        out.close()
        if (uri != null) ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        if (file != null) MediaScannerConnection.scanFile(ctx, arrayOf(file.path), null, null)
    }

    fun abort() {
        runCatching { out.close() }
        uri?.let { runCatching { ctx.contentResolver.delete(it, null, null) } }
        file?.delete()
    }

    companion object {
        fun create(ctx: Context, name: String, mime: String): PublicDownloads {
            val clean = name.replace(Regex("""[\\/:*?"<>|\x00-\x1f]"""), "_").trim().ifEmpty { "file" }
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, clean)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("无法在「下载」里创建文件")
                val out = ctx.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("无法写入文件")
                return PublicDownloads(ctx, out, uri, null, clean)
            }
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
            val dot = clean.lastIndexOf('.').takeIf { it > 0 } ?: clean.length
            var f = File(dir, clean)
            var n = 1
            while (f.exists()) f = File(dir, clean.substring(0, dot) + " (" + n++ + ")" + clean.substring(dot))
            return PublicDownloads(ctx, f.outputStream(), null, f, f.name)
        }

        fun needsPermission() = Build.VERSION.SDK_INT < 29
    }
}
