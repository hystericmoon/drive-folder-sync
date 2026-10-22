package io.github.hystericmoon.drivesync.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ConflictNamesTest {
    private val day = LocalDate.of(2026, 10, 22)

    @Test fun insertsBeforeExtension() =
        assertEquals("report (conflict 2026-10-22).pdf", ConflictNames.conflictName("report.pdf", day) { false })

    @Test fun noExtension() =
        assertEquals("Makefile (conflict 2026-10-22)", ConflictNames.conflictName("Makefile", day) { false })

    @Test fun dotfile() =
        assertEquals(".env (conflict 2026-10-22)", ConflictNames.conflictName(".env", day) { false })

    @Test fun numbersWhenTaken() {
        val taken = setOf("a (conflict 2026-10-22).txt", "a (conflict 2026-10-22 2).txt")
        assertEquals("a (conflict 2026-10-22 3).txt", ConflictNames.conflictName("a.txt", day) { it in taken })
    }
}
