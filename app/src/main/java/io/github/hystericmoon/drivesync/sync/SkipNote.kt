package io.github.hystericmoon.drivesync.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * Something a sync left alone on purpose, with a reason the user can act on.
 *
 * Quick (Changes API) runs only see what changed in Drive, so notes from the last full scan are
 * stored on the pair and carried forward: they keep showing until the problem is gone. A
 * [block] note also keeps those runs away from the path, because acting on half the picture
 * would recreate the problem (e.g. upload "Notes.txt" next to an unseen "notes.txt" in Drive).
 */
data class SkipNote(val path: String, val reason: String, val block: Boolean) {
    companion object {
        fun encode(notes: Collection<SkipNote>): String =
            if (notes.isEmpty()) "" else JSONArray(notes.map { JSONObject().put("path", it.path).put("reason", it.reason).put("block", it.block) }).toString()

        fun decode(stored: String): List<SkipNote> = if (stored.isBlank()) emptyList() else runCatching {
            val array = JSONArray(stored)
            (0 until array.length()).map { i ->
                array.getJSONObject(i).let { SkipNote(it.getString("path"), it.getString("reason"), it.optBoolean("block")) }
            }
        }.getOrDefault(emptyList())
    }
}
