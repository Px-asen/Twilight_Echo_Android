package com.twilightecho.android.adapters

import com.twilightecho.android.coreclient.SafDocumentIdentity
import org.junit.Assert.*
import org.junit.Test

class MusicFolderVisitsTest {
    @Test fun `provider-local visits emit once and an explicit nested grant extends traversal without duplicates`() {
        val visits = MusicFolderVisits()
        val alpha = SafDocumentIdentity("alpha.documents", "shared:Music/Deep")
        val beta = alpha.copy(authority = "beta.documents")
        assertTrue(visits.needsRead(alpha, 0))
        // A failed read is not recorded, so another grant can still read it.
        assertTrue(visits.needsRead(alpha, 0))
        assertTrue(visits.record(alpha, 0))
        assertFalse(visits.needsRead(alpha, 0))
        assertTrue(visits.needsRead(beta, 0))
        assertTrue(visits.record(beta, 0))
        // The directory was reached at the parent's depth limit; picking it as
        // a root now permits its children, without emitting its songs again.
        assertTrue(visits.needsRead(alpha, 8))
        assertFalse(visits.record(alpha, 8))
        assertFalse(visits.needsRead(alpha, 8))
        assertFalse(visits.needsRead(alpha, 3))
    }
}
