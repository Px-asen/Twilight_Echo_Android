package com.twilightecho.android.playbackhost

import com.twilightecho.android.coreclient.DefaultCoreClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The user's last explicit connection choices, restored at start. Holds server IDs only; tokens and
 * certificates stay in [TrustedDesktopStore], so a copy without them reconnects nothing.
 */
@Serializable
internal data class DesktopSession(
    /** The computer the user last connected to; reconnected in the background at start. */
    val desktopId: String? = null,
    /** Whether the user last browsed that computer's library rather than the phone's. */
    val libraryOnDesktop: Boolean = false,
    /** The computer the user last chose to play on; null means the phone. Output is never chosen for the user. */
    val outputDesktopId: String? = null
) {
    fun forgetting(serverId: String) = DesktopSession(
        desktopId = desktopId.takeIf { it != serverId },
        libraryOnDesktop = libraryOnDesktop && desktopId != serverId,
        outputDesktopId = outputDesktopId.takeIf { it != serverId }
    )
}

internal class DesktopSessionStore(private val directory: File) {
    private val file get() = File(directory, FILE_NAME)

    @Synchronized fun load(): DesktopSession = runCatching {
        DefaultCoreClient.DefaultJson.decodeFromString<DesktopSession>(file.readText())
    }.getOrDefault(DesktopSession())

    @Synchronized fun update(transform: (DesktopSession) -> DesktopSession) {
        val current = load()
        val next = transform(current)
        if (next == current) return
        directory.mkdirs()
        val temp = File(directory, "$FILE_NAME.tmp")
        temp.writeText(DefaultCoreClient.DefaultJson.encodeToString(next))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object { const val FILE_NAME = "desktop_session.json" }
}
