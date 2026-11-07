package com.twilightecho.android.adapters

import com.twilightecho.android.coreclient.SafDocumentIdentity

/** A nested grant can reach deeper than a parent grant's eight-level budget.
 * Re-list for that extra reach, but emit a directory's songs only once. */
internal class MusicFolderVisits {
    private val depths = HashMap<SafDocumentIdentity, Int>()

    fun needsRead(document: SafDocumentIdentity, remainingDepth: Int): Boolean =
        remainingDepth > (depths[document] ?: -1)

    /** Called only after a successful listing; returns whether to emit songs. */
    fun record(document: SafDocumentIdentity, remainingDepth: Int): Boolean =
        depths.put(document, remainingDepth) == null
}
