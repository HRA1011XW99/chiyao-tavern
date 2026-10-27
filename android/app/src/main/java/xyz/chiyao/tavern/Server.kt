package xyz.chiyao.tavern

import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread

/**
 * One run of SillyTavern's server.js under the bundled Node. Output goes to a log file and a ring buffer the
 * shell UI shows; [onExit] fires when the process ends for any reason other than [stop].
 */
class Server(
    private val node: File,
    private val pkg: File,
    private val args: List<String>,
    private val env: Map<String, String>,
    private val log: File,
    private val onExit: (Int) -> Unit,
) {
    @Volatile private var proc: Process? = null
    @Volatile private var stopping = false
    @Volatile var lastOutputAt = 0L
        private set
    private val tail = ArrayDeque<String>()

    fun start() {
        val pb = ProcessBuilder(listOf(node.path, File(pkg, "server.js").path) + args)
            .directory(pkg)
            .redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start()
        proc = p
        lastOutputAt = System.currentTimeMillis()
        thread(name = "tavern-log") {
            log.parentFile?.mkdirs()
            // stopping the process closes the pipe under the reader; an uncaught exception here would kill the app
            try {
                log.bufferedWriter().use { w ->
                    p.inputStream.bufferedReader().forEachLine { raw ->
                        val line = ANSI.replace(raw, "")
                        w.write(line); w.newLine(); w.flush()
                        synchronized(tail) {
                            tail.addLast(line)
                            while (tail.size > TAIL_LINES) tail.removeFirst()
                        }
                        lastOutputAt = System.currentTimeMillis()
                    }
                }
            } catch (_: java.io.IOException) {
            }
        }
        thread(name = "tavern-wait") {
            val code = p.waitFor()
            proc = null
            if (!stopping) onExit(code)
        }
    }

    val alive get() = proc?.isAlive == true

    fun tail(n: Int = TAIL_LINES): List<String> = synchronized(tail) { tail.toList().takeLast(n) }

    fun stop() {
        stopping = true
        val p = proc ?: return
        p.destroy()
        if (!runCatching { p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)) p.destroyForcibly()
        proc = null
    }

    companion object {
        private const val TAIL_LINES = 300
        // colours and cursor moves (CSI), and window titles (OSC, ended by BEL or ESC \)
        private val ANSI = Regex("\u001B\\[[0-9;?]*[A-Za-z]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)?")

        fun portFree(port: Int) = runCatching {
            ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close(); true
        }.getOrDefault(false)

        /** Any HTTP answer means the server is listening; SillyTavern compiles its frontend before it listens */
        fun answers(port: Int) = runCatching {
            val c = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
            c.connectTimeout = 1000
            c.readTimeout = 3000
            c.instanceFollowRedirects = false
            val code = c.responseCode
            c.disconnect()
            code in 100..599
        }.getOrDefault(false)
    }
}
