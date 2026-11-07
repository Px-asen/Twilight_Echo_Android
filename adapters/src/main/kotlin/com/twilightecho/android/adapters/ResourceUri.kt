package com.twilightecho.android.adapters

import java.net.URI

private val resourceUriScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/** Encodes literal file paths at the URI boundary; existing URI identities stay unchanged. */
internal fun resourceUriString(source: String): String = when {
    resourceUriScheme.containsMatchIn(source) -> source
    source.startsWith('/') -> URI("file", "", source, null, null).toASCIIString()
    else -> URI(null, null, source, null, null).toASCIIString()
}
