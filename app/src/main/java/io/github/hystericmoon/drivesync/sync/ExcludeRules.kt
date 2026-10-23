package io.github.hystericmoon.drivesync.sync

/**
 * gitignore-style patterns, one per line, case-insensitive:
 * - `*.tmp`, `.DS_Store`, `node_modules/`: no slash inside → matches a file or folder name anywhere.
 * - `Photos/raw`, `build/out.log`: slash inside → matches from the pair root.
 * - `*` stays within one folder, `**` crosses folders, `?` is one character.
 * - Blank lines and lines starting with `#` are ignored.
 * An excluded folder excludes everything inside it.
 *
 * Excluded paths are invisible to the planner on both sides, so excluding something never deletes it.
 */
class ExcludeRules(patterns: String) {
    private val namePatterns = mutableListOf<Regex>()
    private val pathPatterns = mutableListOf<Regex>()

    init {
        for (raw in patterns.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.trimEnd('/').removePrefix("/")
            if (p.isEmpty()) continue
            if ('/' in p) pathPatterns += globToRegex(p) else namePatterns += globToRegex(p)
        }
    }

    val isEmpty: Boolean get() = namePatterns.isEmpty() && pathPatterns.isEmpty()

    fun isExcluded(path: String): Boolean {
        if (isEmpty) return false
        var prefix = ""
        for (segment in path.split('/')) {
            prefix = if (prefix.isEmpty()) segment else "$prefix/$segment"
            if (namePatterns.any { it.matches(segment) }) return true
            if (pathPatterns.any { it.matches(prefix) }) return true
        }
        return false
    }

    companion object {
        const val EXAMPLES = "*.tmp\n.DS_Store\nnode_modules/\nPhotos/raw"

        fun globToRegex(glob: String): Regex {
            val sb = StringBuilder()
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    c == '*' && glob.getOrNull(i + 1) == '*' -> {
                        sb.append(".*")
                        i++
                        if (glob.getOrNull(i + 1) == '/') i++ // "**/" also matches zero folders
                    }
                    c == '*' -> sb.append("[^/]*")
                    c == '?' -> sb.append("[^/]")
                    else -> sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            return Regex(sb.toString(), RegexOption.IGNORE_CASE)
        }
    }
}
