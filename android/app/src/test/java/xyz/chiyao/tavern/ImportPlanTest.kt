package xyz.chiyao.tavern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportPlanTest {
    @Test fun wholeSillyTavernFolderInsideWrapper() {
        val p = ImportPlan.of(listOf(
            "SillyTavern/server.js",
            "SillyTavern/config.yaml",
            "SillyTavern/data/default-user/characters/a.png",
            "SillyTavern/data/_webpack/x/lib.js",
            "SillyTavern/plugins/sync/index.js",
            "SillyTavern/public/scripts/extensions/third-party/JS-Slash-Runner/manifest.json",
            "SillyTavern/public/index.html",
        ))!!
        assertEquals(ImportPlan.Kind.FULL, p.kind)
        assertEquals("data/default-user/characters/a.png", p.target("SillyTavern/data/default-user/characters/a.png"))
        assertNull("webpack cache skipped", p.target("SillyTavern/data/_webpack/x/lib.js"))
        assertEquals("plugins/sync/index.js", p.target("SillyTavern/plugins/sync/index.js"))
        assertEquals("third-party/JS-Slash-Runner/manifest.json",
            p.target("SillyTavern/public/scripts/extensions/third-party/JS-Slash-Runner/manifest.json"))
        assertEquals("config.yaml", p.target("SillyTavern/config.yaml"))
        assertNull("program files ignored", p.target("SillyTavern/public/index.html"))
        assertNull(p.target("SillyTavern/server.js"))
        assertEquals(listOf("data", "plugins", "third-party", "config.yaml"), p.replaces)
    }

    @Test fun ownExportFormat() {
        val p = ImportPlan.of(listOf("data/default-user/settings.json", "third-party/x/manifest.json",
            "plugins-disabled/y/index.js", "config.yaml"))!!
        assertEquals(ImportPlan.Kind.FULL, p.kind)
        assertEquals("third-party/x/manifest.json", p.target("third-party/x/manifest.json"))
        assertEquals("plugins-disabled/y/index.js", p.target("plugins-disabled/y/index.js"))
    }

    @Test fun dataFolderContents() {
        val p = ImportPlan.of(listOf("default-user/chats/a.jsonl", "alice/settings.json"))!!
        assertEquals(ImportPlan.Kind.DATA, p.kind)
        assertEquals("data/default-user/chats/a.jsonl", p.target("default-user/chats/a.jsonl"))
        assertEquals("data/alice/settings.json", p.target("alice/settings.json"))
    }

    @Test fun singleUserFolderWrapped() {
        val p = ImportPlan.of(listOf("default-user-backup/characters/a.png", "default-user-backup/settings.json"))!!
        assertEquals(ImportPlan.Kind.USER, p.kind)
        assertEquals("data/default-user/characters/a.png", p.target("default-user-backup/characters/a.png"))
        assertEquals(listOf("data/default-user"), p.replaces)
    }

    @Test fun unrelatedZip() = assertNull(ImportPlan.of(listOf("photo.jpg", "notes/readme.txt")))
}
