package xyz.chiyao.tavern

/**
 * How a backup zip maps onto the app's folders. Recognised layouts, with or without one wrapping folder:
 *
 *   whole SillyTavern folder   data/, plugins/, public/scripts/extensions/third-party/, config.yaml
 *   this app's export          data/, plugins/, plugins-disabled/, third-party/, config.yaml
 *   a data folder's contents   default-user/ (and other user folders)  → data/
 *   a single user folder       characters/, chats/, settings.json …      → data/default-user/
 *
 * [moves] maps a prefix inside the zip to a path under filesDir. [replaces] are the folders and files
 * cleared before unpacking: an import replaces what it brings, and leaves the rest alone.
 */
data class ImportPlan(val kind: Kind, val moves: List<Pair<String, String>>) {
    enum class Kind { FULL, DATA, USER }

    val replaces: List<String> get() = moves.map { it.second }

    /** Where an entry goes, or null when the import ignores it */
    fun target(entry: String): String? {
        for ((from, to) in moves) {
            if (from.isEmpty() || entry == from || entry.startsWith("$from/")) {
                val rest = entry.removePrefix(from).trimStart('/')
                if (rest.split('/').any { it in SKIP }) return null
                return if (rest.isEmpty()) to else "$to/$rest"
            }
        }
        return null
    }

    companion object {
        /** Caches SillyTavern rebuilds by itself; carrying them over only wastes space */
        private val SKIP = setOf("_webpack", "_cache", "node_modules", ".DS_Store", "__MACOSX")
        private val USER_MARKERS = listOf("characters/", "chats/", "worlds/", "settings.json", "OpenAI Settings/")

        fun of(entries: List<String>): ImportPlan? {
            val names = entries.map { it.replace('\\', '/') }.filter { !it.startsWith("__MACOSX/") }

            // a data/ folder at the top or one level down
            prefixOf(names, "data/")?.let { p ->
                val moves = mutableListOf("${p}data" to "data")
                if (names.any { it.startsWith("${p}plugins/") }) moves += "${p}plugins" to "plugins"
                if (names.any { it.startsWith("${p}plugins-disabled/") }) moves += "${p}plugins-disabled" to "plugins-disabled"
                val tp = "${p}public/scripts/extensions/third-party"
                if (names.any { it.startsWith("$tp/") }) moves += tp to "third-party"
                else if (names.any { it.startsWith("${p}third-party/") }) moves += "${p}third-party" to "third-party"
                if ("${p}config.yaml" in names) moves += "${p}config.yaml" to "config.yaml"
                return ImportPlan(Kind.FULL, moves)
            }
            prefixOf(names, "default-user/")?.let { p ->
                return ImportPlan(Kind.DATA, listOf((if (p.isEmpty()) "" else p.trimEnd('/')) to "data"))
            }
            for (m in USER_MARKERS) {
                prefixOf(names, m)?.let { p ->
                    return ImportPlan(Kind.USER, listOf((if (p.isEmpty()) "" else p.trimEnd('/')) to "data/default-user"))
                }
            }
            return null
        }

        /** "" when [marker] is at the top of the zip, "X/" when inside one wrapping folder, else null */
        private fun prefixOf(names: List<String>, marker: String): String? {
            if (names.any { it.startsWith(marker) || it == marker.trimEnd('/') }) return ""
            return names.firstOrNull { n ->
                val i = n.indexOf('/')
                i > 0 && n.substring(i + 1).let { it.startsWith(marker) || it == marker.trimEnd('/') }
            }?.let { it.substring(0, it.indexOf('/') + 1) }
        }
    }
}
