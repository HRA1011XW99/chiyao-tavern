package xyz.chiyao.tavern

import java.io.File

/**
 * The few edits the shell makes to SillyTavern's config.yaml. Only top-level scalar keys, edited line by line
 * so the user's comments and layout stay as they are. Listening address and port are not written here:
 * they are passed on the command line, which wins over the file.
 */
object ConfigYaml {
    fun set(text: String, key: String, value: String): String {
        val re = Regex("^${Regex.escape(key)}:.*$", RegexOption.MULTILINE)
        if (re.containsMatchIn(text)) return re.replace(text) { "$key: $value" }
        val sep = if (text.isEmpty() || text.endsWith("\n")) "" else "\n"
        return "$text$sep$key: $value\n"
    }

    fun get(text: String, key: String): String? =
        Regex("^${Regex.escape(key)}:\\s*(.*?)\\s*(#.*)?$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)

    fun setFile(f: File, key: String, value: String) {
        val text = if (f.isFile) f.readText() else ""
        val next = set(text, key, value)
        if (next != text) f.writeText(next)
    }
}
