package xyz.chiyao.tavern

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import java.util.UUID

/**
 * Receives page-made files (blob:, data:, same-origin downloads) from assets/inject/save.js and writes them
 * into Downloads. Only pages given [key] can use it; the key is injected into the local server's pages only.
 *
 *   begin(key, name, mime) → id, or "!reason"
 *   write(id, base64)      → "" or "!reason"
 *   end(id, ok)            → "" or "!reason"; ok = false drops the half-written file
 */
class Saver(private val act: Activity) {
    val key: String = UUID.randomUUID().toString()
    private val open = HashMap<String, PublicDownloads>()

    @JavascriptInterface
    @Synchronized
    fun begin(key: String, name: String, mime: String): String {
        if (key != this.key) return "!无权保存"
        if (PublicDownloads.needsPermission() &&
            act.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return "!授予存储权限后，请重新保存"
        }
        val type = mime.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
        return try {
            val id = UUID.randomUUID().toString()
            open[id] = PublicDownloads.create(act, name, type)
            id
        } catch (e: Exception) {
            "!" + (e.message ?: "无法创建文件")
        }
    }

    @JavascriptInterface
    @Synchronized
    fun write(id: String, b64: String): String {
        val d = open[id] ?: return "!保存已中断"
        return try {
            d.out.write(Base64.decode(b64, Base64.DEFAULT))
            ""
        } catch (e: Exception) {
            open.remove(id)?.abort()
            "!" + (e.message ?: "写入失败")
        }
    }

    @JavascriptInterface
    @Synchronized
    fun end(id: String, ok: Boolean): String {
        val d = open.remove(id) ?: return if (ok) "!保存已中断" else ""
        if (!ok) { d.abort(); return "" }
        return try {
            d.finish()
            act.runOnUiThread { Toast.makeText(act, "已保存到「下载」：${d.name}", Toast.LENGTH_SHORT).show() }
            ""
        } catch (e: Exception) {
            d.abort()
            "!" + (e.message ?: "保存失败")
        }
    }

    companion object { const val REQ_STORAGE = 41 }
}
