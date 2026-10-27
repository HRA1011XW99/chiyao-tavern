package xyz.chiyao.tavern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigYamlTest {
    private val sample = """
        # -- SERVER PLUGIN CONFIGURATION --
        enableServerPlugins: false
        enableServerPluginsAutoUpdate: true
        whitelist:
          - ::1
          - 127.0.0.1
    """.trimIndent() + "\n"

    @Test fun replacesOnlyTheExactKey() {
        val out = ConfigYaml.set(sample, "enableServerPlugins", "true")
        assertEquals("true", ConfigYaml.get(out, "enableServerPlugins"))
        assertEquals("true", ConfigYaml.get(out, "enableServerPluginsAutoUpdate"))
        assert(out.contains("# -- SERVER PLUGIN CONFIGURATION --"))
        assert(out.contains("  - 127.0.0.1"))
    }

    @Test fun appendsMissingKey() {
        val out = ConfigYaml.set("port: 8000", "enableServerPlugins", "true")
        assertEquals("port: 8000\nenableServerPlugins: true\n", out)
        assertEquals("true", ConfigYaml.get(ConfigYaml.set("", "a", "true"), "a"))
    }

    @Test fun getIgnoresComments() {
        assertEquals("false", ConfigYaml.get("enableServerPlugins: false # off\n", "enableServerPlugins"))
        assertNull(ConfigYaml.get("other: 1\n", "enableServerPlugins"))
    }
}
