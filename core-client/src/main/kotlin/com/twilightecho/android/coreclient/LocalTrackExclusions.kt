package com.twilightecho.android.coreclient

/** Upgrade only source-backed records. A scan cannot reconstruct a deleted legacy track's provider. */
fun UserPreferences.migrateLocalTrackExclusions(): UserPreferences {
    val ids = linkedSetOf<String>()
    val sources = linkedMapOf<String, String>()
    for (id in excludedLocalTrackIds) {
        val source = excludedLocalTrackSources[id]
        val next = SafTrackIds.fromSource(id, source)
        ids += next
        if (source != null) sources[next] = source
    }
    return copy(excludedLocalTrackIds = ids, excludedLocalTrackSources = sources)
}

fun UserPreferences.excludesLocalTrack(track: CoreTrackView): Boolean {
    if (track.id in excludedLocalTrackIds) return true
    val legacy = SafTrackIds.legacy(track.id)
    return legacy in excludedLocalTrackIds && legacy !in excludedLocalTrackSources
}

/** Counts source-less exclusions that still hide more than one provider, for the scan result. */
fun UserPreferences.ambiguousLocalExclusions(tracks: List<CoreTrackView>): Int = tracks
    .groupBy { SafTrackIds.legacy(it.id) }
    .count { (legacy, matches) -> legacy in excludedLocalTrackIds && legacy !in excludedLocalTrackSources &&
        matches.map { it.id }.distinct().size > 1 }
