package xyz.chiyao.tavern

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class Cancelled : IOException("已取消")

object Net {
    private const val UA = "ChiyaoTavern"

    fun text(url: String, timeoutMs: Int): String {
        val c = open(url, timeoutMs, timeoutMs)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Download to [dest] and check the sha256. Writes to a .part file first so a broken download is never
     * mistaken for a finished one. [cancel] is polled between chunks.
     */
    fun download(url: String, dest: File, sha256: String, progress: (Long, Long) -> Unit, cancel: () -> Boolean = { false }) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        val c = open(url, 15_000, 30_000)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancel()) throw Cancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
            }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (sha256.isNotEmpty() && got != sha256.lowercase()) {
                throw IOException("文件校验失败，请重试或更换加速地址")
            }
            dest.delete()
            if (!part.renameTo(dest)) throw IOException("无法保存下载的文件")
        } finally {
            c.disconnect()
            part.delete()
        }
    }

    private fun open(url: String, connectMs: Int, readMs: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = connectMs
        c.readTimeout = readMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        return c
    }
}
