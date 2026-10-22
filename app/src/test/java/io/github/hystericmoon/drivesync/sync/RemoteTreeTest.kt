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
        assertEquals(setOf("Docs", "Docs/x.txt"), t.skipped.toSet())
    }

    @Test fun ignoresInvalidNamesAndTrashFolder() {
        val t = RemoteTree("root")
        t.put(node("a", "bad/name", "root"))
        t.put(node("b", ".sync-trash", "root", dir = true))
        t.put(node("c", "inside.txt", "b"))
        assertTrue(t.resolve(emptySet()).isEmpty())
    }
}
