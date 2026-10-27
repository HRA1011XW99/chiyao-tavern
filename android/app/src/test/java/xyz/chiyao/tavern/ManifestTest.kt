package xyz.chiyao.tavern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManifestTest {
    private val full = """
        {"st":{"build":7,"version":"1.18.0","commit":"8172dcd","url":"https://x/st.zip","sha256":"ABC","size":123,"minShell":2},
         "shell":{"versionCode":3,"versionName":"1.0.3","url":"https://x/a.apk","sha256":"DEF","notes":"fix"}}
    """.trimIndent()

    @Test fun parsesBothSections() {
        val m = Manifest.parse(full)
        assertEquals(7, m.st!!.build)
        assertEquals("1.18.0-7", m.st!!.id)
        assertEquals("abc", m.st!!.sha256)
        assertEquals(2, m.st!!.minShell)
        assertEquals(3, m.shell!!.versionCode)
        assertEquals("fix", m.shell!!.notes)
    }

    @Test fun sectionsAreOptional() {
        val m = Manifest.parse("""{"shell":{"versionCode":1,"versionName":"1","url":"u","sha256":"s"}}""")
        assertNull(m.st)
        assertEquals(1, m.shell!!.versionCode)
    }

    private val pkg = Manifest.parse(full).st!!

    @Test fun installsWhenNothingInstalled() =
        assertEquals(StDecision.Install(pkg), StDecision.of(pkg, null, emptySet(), 5))

    @Test fun installsNewerBuild() =
        assertEquals(StDecision.Install(pkg), StDecision.of(pkg, 6, emptySet(), 5))

    @Test fun skipsSameOrOlder() {
        assertEquals(StDecision.UpToDate, StDecision.of(pkg, 7, emptySet(), 5))
        assertEquals(StDecision.UpToDate, StDecision.of(pkg, 9, emptySet(), 5))
    }

    @Test fun skipsKnownBad() =
        assertEquals(StDecision.UpToDate, StDecision.of(pkg, 6, setOf(7), 5))

    @Test fun waitsForShell() =
        assertEquals(StDecision.NeedsShell(pkg), StDecision.of(pkg, 6, emptySet(), 1))

    @Test fun noPackagePublished() =
        assertEquals(StDecision.UpToDate, StDecision.of(null, null, emptySet(), 1))
}
