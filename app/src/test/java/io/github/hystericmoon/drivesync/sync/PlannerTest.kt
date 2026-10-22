package io.github.hystericmoon.drivesync.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerTest {

    private fun local(path: String, size: Long = 10, mtime: Long = 100, dir: Boolean = false) =
        LocalEntry(path, "doc:$path", path.substringAfterLast('/'), dir, size, mtime, "text/plain")

    private fun remote(path: String, md5: String = "aaa", id: String = "id:$path", dir: Boolean = false) =
        RemoteEntry(path, id, "parent", dir, if (dir) null else md5, "2026-01-01T00:00:00Z", 10, "text/plain")

    private fun base(path: String, size: Long = 10, mtime: Long = 100, md5: String = "aaa", id: String = "id:$path", dir: Boolean = false) =
        Base(path, dir, size, mtime, id, if (dir) null else md5, "2026-01-01T00:00:00Z")

    private fun plan(
        l: List<LocalEntry> = emptyList(),
        r: List<RemoteEntry> = emptyList(),
        b: List<Base> = emptyList(),
        allowDeletes: Boolean = true,
    ) = Planner.plan(l.associateBy { it.path }, r.associateBy { it.path }, b.associateBy { it.path }, allowDeletes)

    private inline fun <reified T : Action> List<Action>.single(): T {
        assertEquals("actions: $this", 1, size)
        assertTrue("expected ${T::class.simpleName}, got ${first()}", first() is T)
        return first() as T
    }

    @Test fun unchangedDoesNothing() {
        assertTrue(plan(listOf(local("a.txt")), listOf(remote("a.txt")), listOf(base("a.txt"))).isEmpty())
    }

    @Test fun newLocalUploads() {
        val a = plan(l = listOf(local("a.txt"))).single<Action.Upload>()
        assertEquals(null, a.replaceId)
    }

    @Test fun newRemoteDownloads() {
        plan(r = listOf(remote("a.txt"))).single<Action.Download>()
    }

    @Test fun localEditUploadsOverExisting() {
        val a = plan(listOf(local("a.txt", mtime = 200)), listOf(remote("a.txt")), listOf(base("a.txt")))
            .single<Action.Upload>()
        assertEquals("id:a.txt", a.replaceId)
    }

    @Test fun remoteEditDownloadsOverExisting() {
        val a = plan(listOf(local("a.txt")), listOf(remote("a.txt", md5 = "bbb")), listOf(base("a.txt")))
            .single<Action.Download>()
        assertEquals("doc:a.txt", a.overwrite?.docId)
    }

    @Test fun bothEditedReconciles() {
        plan(listOf(local("a.txt", size = 11)), listOf(remote("a.txt", md5 = "bbb")), listOf(base("a.txt")))
            .single<Action.Reconcile>()
    }

    @Test fun bothNewReconciles() {
        plan(listOf(local("a.txt")), listOf(remote("a.txt"))).single<Action.Reconcile>()
    }

    @Test fun remoteDeleteMovesLocalToTrash() {
        plan(l = listOf(local("a.txt")), b = listOf(base("a.txt"))).single<Action.TrashLocal>()
    }

    @Test fun localDeleteTrashesRemote() {
        plan(r = listOf(remote("a.txt")), b = listOf(base("a.txt"))).single<Action.TrashRemote>()
    }

    @Test fun localEditBeatsRemoteDelete() {
        val a = plan(l = listOf(local("a.txt", mtime = 999)), b = listOf(base("a.txt"))).single<Action.Upload>()
        assertEquals(null, a.replaceId)
    }

    @Test fun remoteEditBeatsLocalDelete() {
        plan(r = listOf(remote("a.txt", md5 = "new")), b = listOf(base("a.txt"))).single<Action.Download>()
    }

    @Test fun remoteReplacedWithNewIdCountsAsChange() {
        plan(r = listOf(remote("a.txt", id = "other")), b = listOf(base("a.txt"))).single<Action.Download>()
    }

    @Test fun firstSyncNeverDeletes() {
        val actions = plan(
            l = listOf(local("only-local.txt")),
            r = listOf(remote("only-remote.txt")),
            b = listOf(base("only-local.txt"), base("only-remote.txt")),
            allowDeletes = false,
        )
        assertTrue(actions.none { it is Action.TrashLocal || it is Action.TrashRemote || it is Action.RemoveLocalDirIfEmpty || it is Action.RemoveRemoteDirIfEmpty })
        assertEquals(setOf(Action.Upload::class, Action.Download::class), actions.map { it::class }.toSet())
    }

    @Test fun goneBothSidesForgets() {
        plan(b = listOf(base("a.txt"))).single<Action.Forget>()
    }

    @Test fun dirsCreatedParentFirstAndRemovedChildFirst() {
        val create = plan(l = listOf(local("a", dir = true), local("a/b", dir = true), local("a/b/c", dir = true)))
        assertEquals(listOf("a", "a/b", "a/b/c"), create.map { it.path })
        assertTrue(create.all { it is Action.CreateRemoteDir })

        val remove = plan(
            r = listOf(remote("a", dir = true), remote("a/b", dir = true)),
            b = listOf(base("a", dir = true), base("a/b", dir = true)),
        )
        assertEquals(listOf("a/b", "a"), remove.map { it.path })
        assertTrue(remove.all { it is Action.RemoveRemoteDirIfEmpty })
    }

    @Test fun fileActionsComeAfterDirCreation() {
        val actions = plan(l = listOf(local("z.txt"), local("dir", dir = true), local("dir/x.txt")))
        assertTrue(actions.first() is Action.CreateRemoteDir)
    }

    @Test fun fileFolderClashIsSkipped() {
        plan(listOf(local("a")), listOf(remote("a", dir = true))).single<Action.Skip>()
    }
}
