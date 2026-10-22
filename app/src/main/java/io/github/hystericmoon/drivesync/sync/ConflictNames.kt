package io.github.hystericmoon.drivesync.sync

import java.time.LocalDate

object ConflictNames {
    /**
     * "report.pdf" → "report (conflict 2026-10-22).pdf". Dotfiles and extensionless names get the
     * suffix at the end. Adds " 2", " 3"… until [taken] (case-insensitive) doesn't contain it.
     */
    fun conflictName(name: String, date: LocalDate, taken: (String) -> Boolean): String {
        val dot = name.lastIndexOf('.')
        val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var n = 1
        while (true) {
            val suffix = if (n == 1) "conflict $date" else "conflict $date $n"
            val candidate = "$stem ($suffix)$ext"
            if (!taken(candidate)) return candidate
            n++
        }
    }
}
