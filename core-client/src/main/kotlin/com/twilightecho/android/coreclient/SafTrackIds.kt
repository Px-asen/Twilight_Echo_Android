package com.twilightecho.android.coreclient

/** A document is provider-local. Tree grants are access paths, not part of its identity. */
data class SafDocumentIdentity(val authority: String, val documentId: String)

/** Provider namespaces retain the original file/CUE/SACD fingerprint so saved references can migrate. */
object SafTrackIds {
    const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    private val ID = Regex("^(local:(?:saf|cue):[a-f0-9]{24}|local:sacd:[a-f0-9]{24}:(?:stereo|multichannel):[1-9]\\d*)(?:@(.+))?$")
    private val DOCUMENT = Regex("^content://([^/?#]+)/(?:tree/[^/?#]+/)?document/[^/?#]+(?:[?#].*)?$")

    /** Existing ExternalStorage and pure-function callers keep their IDs. */
    fun qualify(id: String, authority: String?): String =
        if (authority == null || authority == EXTERNAL_STORAGE || ID.matchEntire(id)?.groups?.get(2) != null) id
        else "$id@$authority"

    fun legacy(id: String): String = ID.matchEntire(id)?.groupValues?.get(1) ?: id

    /** Only app-generated SAF IDs with a real document source take part in migration. */
    fun fromSource(id: String, source: String?): String {
        if (!ID.matches(id) || source == null) return id
        val authority = DOCUMENT.matchEntire(source)?.groupValues?.get(1) ?: return id
        return qualify(id, authority)
    }
}
