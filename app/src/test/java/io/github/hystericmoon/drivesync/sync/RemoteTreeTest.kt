package io.github.hystericmoon.drivesync.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTreeTest {
    private fun node(id: String, name: String, parent: String?, dir: Boolean = false) =
        RemoteTree.Node(id, name, parent, dir, if (dir) null else "md5", null, 1, "x")

    @Test fun resolvesNestedPaths() {
        val t = RemoteTree("root")
        t.put(node("d", "docs", "root", dir = true))
        t.put(node("f", "a.txt", "d"))
        assertEquals(setOf("docs", "docs/a.txt"), t.resolve(emptySet()).keys)
    }

    @Test fun folderRenameMovesChildren() {
        val t = RemoteTree("root")
        t.put(node("d", "docs", "root", dir = true))
        t.put(node("f", "a.txt", "d"))
        t.put(node("d", "papers", "root", dir = true)) // rename arrives via Changes API
        assertEquals(setOf("papers", "papers/a.txt"), t.resolve(emptySet()).keys)
    }

    @Test fun removedParentDropsChildren() {
        val t = RemoteTree("root")
        t.put(node("d", "docs", "root", dir = true))
        t.put(node("f", "a.txt", "d"))
        t.remove("d")
        assertTrue(t.resolve(emptySet()).isEmpty())
    }

    @Test fun movedOutOfScopeDisappears() {
        val t = RemoteTree("root")
        t.put(node("f", "a.txt", "somewhere-else"))
        assertTrue(t.resolve(emptySet()).isEmpty())
    }

    @Test fun duplicateNamesPreferBaselineAndSkipOthersWithChildren() {
        val t = RemoteTree("root")
        t.put(node("aaa", "Docs", "root", dir = true))
        t.put(node("zzz", "docs", "root", dir = true))
        t.put(node("c1", "x.txt", "aaa"))
        t.put(node("c2", "y.txt", "zzz"))
        val resolved = t.resolve(preferredIds = setOf("zzz"))
        assertEquals(setOf("docs", "docs/y.txt"), resolved.keys)
        assertEquals(setOf("Docs", "Docs/x.txt"), t.skipped.map { it.path }.toSet())
        assertTrue(t.skipped.first { it.path == "Docs" }.reason.contains("“docs”"))
        assertTrue(t.skipped.first { it.path == "Docs" }.reason.contains("upper/lower case"))
        assertTrue(t.skipped.first { it.path == "Docs/x.txt" }.reason.contains("folder above"))
    }

    @Test fun exactDuplicateNamesSaySo() {
        val t = RemoteTree("root")
        t.put(node("a1", "same.txt", "root"))
        t.put(node("a2", "same.txt", "root"))
        assertEquals(setOf("same.txt"), t.resolve(emptySet()).keys)
        assertTrue(t.skipped.single().reason.contains("same name"))
    }

    @Test fun ignoresInvalidNamesAndTrashFolder() {
        val t = RemoteTree("root")
        t.put(node("a", "bad/name", "root"))
        t.put(node("b", ".sync-trash", "root", dir = true))
        t.put(node("c", "inside.txt", "b"))
        assertTrue(t.resolve(emptySet()).isEmpty())
        // Both are reported once (the trash folder's contents aren't listed one by one).
        assertTrue(t.skipped.first { it.path == "bad/name" }.reason.contains("“/”"))
        assertEquals(setOf("bad/name", ".sync-trash"), t.skipped.map { it.path }.toSet())
    }

    @Test fun namesThePhoneCantHold() {
        for (bad in listOf("a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", "a\\b", "a/b", "dot.", "tab\tx", "", ".", "..", "x".repeat(256), "é".repeat(128))) {
            assertTrue("should reject \"$bad\"", RemoteTree.phoneNameProblem(bad) != null)
        }
        for (ok in listOf("photo.JPG", ".hidden", "a%b&c'd;e=f+g,h.txt", "[x]{y}.txt", "emoji 🙂.txt", "x".repeat(255), "é".repeat(127))) {
            assertEquals("should accept \"$ok\"", null, RemoteTree.phoneNameProblem(ok))
        }
    }
}
