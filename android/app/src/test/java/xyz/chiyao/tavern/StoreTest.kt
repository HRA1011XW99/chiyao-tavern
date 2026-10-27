package xyz.chiyao.tavern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(vararg files: Pair<String, String>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            for ((name, body) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    private fun pkg(build: Int) = Manifest.StPackage(build, "1.18.0", "c", "u", "s", 0, 1)
    private val stZip get() = zip(
        "server.js" to "//",
        "plugins/package.json" to """{"type":"commonjs"}""",
        "plugins/.gitkeep" to "",
        "public/scripts/extensions/third-party/.gitkeep" to "",
    )

    @Test fun installMakesCurrentAndKeepsPrevious() {
        val s = Store(tmp.root)
        assertNull(s.current)
        s.install(stZip, pkg(1))
        assertEquals(1, s.current!!.build)
        assertNull(s.previous)
        s.install(stZip, pkg(2))
        assertEquals(2, s.current!!.build)
        assertEquals(1, s.previous!!.build)
        s.install(stZip, pkg(3))
        assertEquals(2, s.previous!!.build)
        assertFalse("oldest package pruned", File(s.stDir, pkg(1).id).exists())
    }

    @Test fun incompletePackageIsRejected() {
        val s = Store(tmp.root)
        s.install(stZip, pkg(1))
        val r = runCatching { s.install(zip("README.md" to "x"), pkg(2)) }
        assertTrue(r.isFailure)
        assertEquals(1, s.current!!.build)
        assertFalse(File(s.stDir, pkg(2).id + ".tmp").exists())
    }

    @Test fun rollbackMarksBadAndRestoresPrevious() {
        val s = Store(tmp.root)
        s.install(stZip, pkg(1))
        s.install(stZip, pkg(2))
        val back = s.rollback()
        assertEquals(1, back!!.build)
        assertEquals(1, s.current!!.build)
        assertNull(s.previous)
        assertEquals(setOf(2), s.bad)
        assertNull("nothing left to roll back to", s.rollback())
    }

    @Test fun zipSlipRefused() {
        val r = runCatching { Zips.extract(zip("../evil.txt" to "x"), File(tmp.root, "out")) }
        assertTrue(r.exceptionOrNull() is SecurityException)
        assertFalse(File(tmp.root, "evil.txt").exists())
    }

    /** Windows needs a privilege for symbolic links; phones and the Linux CI do not */
    private fun canLink() = runCatching {
        Files.createSymbolicLink(File(tmp.root, "probe").toPath(), tmp.root.toPath()); true
    }.getOrDefault(false)

    @Test fun linkPointsPackageFoldersAtPersistentOnes() {
        assumeTrue("symbolic links not available on this host", canLink())
        val s = Store(tmp.root)
        s.install(stZip, pkg(1))
        val dir = s.current!!.dir
        s.link(dir)
        val p = File(dir, "plugins").toPath()
        assertTrue(Files.isSymbolicLink(p))
        assertEquals(s.plugins.toPath(), Files.readSymbolicLink(p))
        assertTrue("commonjs marker carried over", File(s.plugins, "package.json").isFile)
        assertTrue(Files.isSymbolicLink(File(dir, "public/scripts/extensions/third-party").toPath()))
        // a plugin installed later survives an update
        File(s.plugins, "demo").mkdirs()
        s.install(stZip, pkg(2))
        s.link(s.current!!.dir)
        assertTrue(File(s.current!!.dir, "plugins/demo").isDirectory)
        s.link(s.current!!.dir) // idempotent
        // pruning the oldest package must not follow its links into the user's folders
        s.install(stZip, pkg(3))
        assertFalse(File(s.stDir, pkg(1).id).exists())
        assertTrue(File(s.plugins, "demo").isDirectory)
    }
}
