package xyz.chiyao.tavern

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Delete a tree without following symbolic links. File.deleteRecursively follows them, which would
 * walk from a package's plugins link straight into the user's plugins and delete those too.
 */
fun deleteTree(f: File) {
    val p = f.toPath()
    if (!java.nio.file.Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
    java.nio.file.Files.walkFileTree(p, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
        override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes) =
            java.nio.file.FileVisitResult.CONTINUE.also { java.nio.file.Files.delete(file) }
        override fun postVisitDirectory(dir: java.nio.file.Path, exc: java.io.IOException?) =
            java.nio.file.FileVisitResult.CONTINUE.also { java.nio.file.Files.delete(dir) }
    })
}

object Zips {
    /** Unpack into [dest], refusing entries that would land outside it (zip slip). */
    fun extract(input: InputStream, dest: File, onEntry: (String) -> Unit = {}) {
        val root = dest.canonicalFile
        root.mkdirs()
        ZipInputStream(input.buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val out = File(root, e.name).canonicalFile
                if (out != root && !out.path.startsWith(root.path + File.separator)) {
                    throw SecurityException("zip entry outside target: ${e.name}")
                }
                if (e.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                    if (e.time > 0) out.setLastModified(e.time)
                }
                onEntry(e.name)
            }
        }
    }

    /**
     * Zip [entries] (path inside the zip → file or directory on disk). Symbolic links are skipped:
     * the package links (plugins, third-party) point back at the very folders being exported.
     */
    fun write(out: OutputStream, entries: List<Pair<String, File>>, skip: (File) -> Boolean = { false }) {
        ZipOutputStream(out.buffered()).use { zout ->
            fun add(name: String, f: File) {
                if (java.nio.file.Files.isSymbolicLink(f.toPath()) || skip(f)) return
                if (f.isDirectory) {
                    zout.putNextEntry(ZipEntry("$name/"))
                    zout.closeEntry()
                    f.listFiles()?.sortedBy { it.name }?.forEach { add("$name/${it.name}", it) }
                } else if (f.isFile) {
                    zout.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
                    f.inputStream().use { it.copyTo(zout) }
                    zout.closeEntry()
                }
            }
            for ((name, f) in entries) if (f.exists()) add(name.trimEnd('/'), f)
        }
    }
}
