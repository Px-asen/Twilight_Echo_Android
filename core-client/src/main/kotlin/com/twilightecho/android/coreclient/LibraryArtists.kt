package com.twilightecho.android.coreclient

import java.text.Normalizer
import java.util.Locale

private val ArtistWhitespace = Regex("[\\s\\p{Z}]+")
private val JoinedArtistAmpersand = Regex("\\s+[&＆]\\s+")

/** Compare spelling variants without removing meaningful punctuation from a person's/band's name. */
fun artistNameKey(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFKC)
    .replace(ArtistWhitespace, " ").trim().lowercase(Locale.ROOT)

/** Structured credits are literal names; only the legacy joined display credit needs splitting. */
fun libraryArtistNames(track: CoreTrackView): List<String> {
    // The desktop already expanded legacy copied/joined tags. Use its complete source membership,
    // not the raw `artists = ["A / B"]` left behind by those scans.
    val catalog = track.catalog
    val catalogNames = catalog?.items?.filter { it.kind == "artist" }?.map { it.title.trim() }?.filter(String::isNotEmpty).orEmpty()
    if (catalogNames.isNotEmpty() && (catalog?.total == null || catalog.total <= catalog.items.size))
        return catalogNames.distinctBy(::artistNameKey)
    val explicit = track.artists.map(String::trim).filter(String::isNotEmpty)
    val names = explicit.ifEmpty {
        track.artist.split(JoinedArtistAmpersand).flatMap { credit ->
            val parts = credit.split('/', '／').map(String::trim).filter(String::isNotEmpty)
            buildList {
                var index = 0
                while (index < parts.size) {
                    // AC/DC is one band, including in a joined AC/DC / Guest credit.
                    if (parts[index].equals("AC", true) && parts.getOrNull(index + 1).equals("DC", true)) {
                        add("${parts[index]}/${parts[index + 1]}")
                        index += 2
                    } else add(parts[index++])
                }
            }
        }
    }
    return names.distinctBy(::artistNameKey).ifEmpty { listOf("未知艺术家") }
}

/** Explicit clears and source display/expiry grants also apply to thumbnails. */
fun EntityMetadataDetail.artworkPreview(now: Long = System.currentTimeMillis()): String? =
    when (artworkOverride?.mode) {
        "clear" -> null
        "value" -> manualArtwork?.dataUrl
        else -> manualArtwork?.dataUrl ?: artwork.firstOrNull { it.usage?.canDisplay(now) != false }?.dataUrl
    }
