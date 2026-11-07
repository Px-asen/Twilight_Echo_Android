package com.twilightecho.android.coreclient

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SafTrackIdsTest {
    private val id = "local:saf:" + "a".repeat(24)
    private val authority = "example.documents"
    private val source = "content://$authority/tree/root/document/shared%3AMusic%2Fa%23%25%3F.flac"

    @Test fun providerNamespacesPreserveLegacyFingerprintsAndExternalStorage() {
        val cue = "local:cue:" + "b".repeat(24)
        val sacd = "local:sacd:" + "c".repeat(24) + ":multichannel:2"
        for (old in listOf(id, cue, sacd)) {
            assertEquals(old, SafTrackIds.qualify(old, null))
            assertEquals(old, SafTrackIds.qualify(old, SafTrackIds.EXTERNAL_STORAGE))
            assertEquals("$old@$authority", SafTrackIds.fromSource(old, source))
            assertEquals(old, SafTrackIds.legacy("$old@$authority"))
            assertEquals("$old@$authority", SafTrackIds.fromSource("$old@$authority", source))
            assertEquals("$old@$authority", SafTrackIds.fromSource(old, source.replace("tree/root/", "")))
            assertEquals("$old@$authority", SafTrackIds.fromSource(old, "$source#twilight-sacd=stereo:2"))
        }
        assertEquals(id, SafTrackIds.fromSource(id, "content://media/external/audio/media/1"))
        assertEquals(id, SafTrackIds.fromSource(id, "D:/Music/a.flac"))
        assertEquals("provider:1", SafTrackIds.fromSource("provider:1", source))
    }

    @Test fun documentVisitKeysSeparateProvidersButNotOverlappingTreeGrants() {
        val visited = hashSetOf<SafDocumentIdentity>()
        assertTrue(visited.add(SafDocumentIdentity(authority, "shared:Music")))
        assertTrue(visited.add(SafDocumentIdentity("other.documents", "shared:Music")))
        assertFalse(visited.add(SafDocumentIdentity(authority, "shared:Music")))
        assertTrue(visited.add(SafDocumentIdentity(authority, "shared:music")))
    }

    @Test fun sourceBackedExclusionsMigrateAndNewExternalExclusionsDoNotHideOtherProviders() {
        val old = UserPreferences(excludedLocalTrackIds = setOf(id), excludedLocalTrackSources = mapOf(id to source))
        val migrated = old.migrateLocalTrackExclusions()
        assertEquals(setOf("$id@$authority"), migrated.excludedLocalTrackIds)
        assertEquals(mapOf("$id@$authority" to source), migrated.excludedLocalTrackSources)
        assertEquals(migrated, migrated.migrateLocalTrackExclusions())
        assertEquals(migrated, Json.decodeFromString<UserPreferences>(Json.encodeToString(migrated)))
        assertTrue(migrated.excludesLocalTrack(CoreTrackView("$id@$authority", title = "Custom", source = source)))
        assertFalse(migrated.excludesLocalTrack(CoreTrackView(id, title = "External")))
        val external = old.copy(excludedLocalTrackSources = mapOf(id to source.replace(authority, SafTrackIds.EXTERNAL_STORAGE)))
            .migrateLocalTrackExclusions()
        assertTrue(external.excludesLocalTrack(CoreTrackView(id, title = "External")))
        assertFalse(external.excludesLocalTrack(CoreTrackView("$id@$authority", title = "Custom")))
    }

    @Test fun sourceLessLegacyExclusionsRetainTheirScopeWithoutGuessingOneProvider() {
        val old = UserPreferences(excludedLocalTrackIds = setOf(id))
        val songs = listOf(CoreTrackView(id, title = "External"), CoreTrackView("$id@$authority", title = "Custom"),
            CoreTrackView("${id}@other.documents", title = "Other"))
        assertEquals(old, old.migrateLocalTrackExclusions())
        assertTrue(songs.all(old::excludesLocalTrack))
        assertEquals(1, old.ambiguousLocalExclusions(songs))
        assertEquals(0, old.ambiguousLocalExclusions(songs.take(1)))
        assertFalse(old.excludesLocalTrack(CoreTrackView("local:saf:" + "d".repeat(24), title = "Unrelated")))
        val restored = old.copy(excludedLocalTrackIds = emptySet(), excludedLocalTrackSources = emptyMap())
        assertTrue(songs.none(restored::excludesLocalTrack))
    }
}
