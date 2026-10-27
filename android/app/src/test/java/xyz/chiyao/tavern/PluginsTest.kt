package xyz.chiyao.tavern

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun pkg(dir: File, gyp: Boolean = false) {
        dir.mkdirs()
        File(dir, "package.json").writeText("{}")
        if (gyp) File(dir, "binding.gyp").writeText("{}")
    }

    @Test fun findsNativePackagesIncludingScopedAndNested() {
        val nm = tmp.newFolder("node_modules")
        pkg(File(nm, "jsdom"))
        pkg(File(nm, "bufferutil"), gyp = true)
        pkg(File(nm, "@scope/addon"), gyp = true)
        pkg(File(nm, "@scope/plain"))
        pkg(File(nm, "ws/node_modules/utf-8-validate"), gyp = true)
        File(nm, ".bin").mkdirs()
        assertEquals(listOf("@scope/addon", "bufferutil", "utf-8-validate"), Plugins.nativePackages(nm))
    }

    @Test fun compiledAddonsAreNotReported() {
        val nm = tmp.newFolder("node_modules")
        pkg(File(nm, "better-sqlite3"), gyp = true)
        File(nm, "better-sqlite3/build/Release").mkdirs()
        File(nm, "better-sqlite3/build/Release/better_sqlite3.node").writeText("")
        pkg(File(nm, "bufferutil"), gyp = true)
        assertEquals(listOf("bufferutil"), Plugins.nativePackages(nm))
    }

    @Test fun noNodeModulesMeansNothingNative() {
        assertEquals(emptyList<String>(), Plugins.nativePackages(File(tmp.root, "missing")))
    }
}
