package io.github.hystericmoon.drivesync.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcludeRulesTest {
    private val rules = ExcludeRules(
        """
        # comment
        *.tmp
        .DS_Store
        node_modules/
        Photos/raw
        /build/*.log
        docs/**/draft-*

        """.trimIndent(),
    )

    @Test fun nameMatchesAnywhere() {
        assertTrue(rules.isExcluded("a.tmp"))
        assertTrue(rules.isExcluded("deep/down/b.TMP"))
        assertTrue(rules.isExcluded("x/.DS_Store"))
        assertFalse(rules.isExcluded("a.tmpl"))
    }

    @Test fun excludedFolderExcludesContents() {
        assertTrue(rules.isExcluded("node_modules"))
        assertTrue(rules.isExcluded("web/node_modules/lib/index.js"))
        assertTrue(rules.isExcluded("Photos/raw/IMG_1.dng"))
        assertFalse(rules.isExcluded("Photos/edited/IMG_1.jpg"))
    }

    @Test fun pathPatternsAnchorAtRoot() {
        assertTrue(rules.isExcluded("build/out.log"))
        assertFalse(rules.isExcluded("src/build/out.log"))
        assertFalse(rules.isExcluded("build/sub/out.log"))
    }

    @Test fun doubleStarCrossesFolders() {
        assertTrue(rules.isExcluded("docs/draft-1.md"))
        assertTrue(rules.isExcluded("docs/a/b/draft-2.md"))
        assertFalse(rules.isExcluded("docs/a/final.md"))
    }

    @Test fun emptyRulesExcludeNothing() {
        assertFalse(ExcludeRules("\n# only comments\n").isExcluded("anything"))
    }
}
