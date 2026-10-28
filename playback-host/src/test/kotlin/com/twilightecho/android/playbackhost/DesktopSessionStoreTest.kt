package com.twilightecho.android.playbackhost

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopSessionStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun choicesPersistAndAFreshOrDamagedFileRestoresNothing() {
        val store = DesktopSessionStore(folder.root)
        assertEquals(DesktopSession(), store.load())
        store.update { it.copy(desktopId = "a", libraryOnDesktop = true) }
        store.update { it.copy(outputDesktopId = "a") }
        assertEquals(DesktopSession("a", true, "a"), DesktopSessionStore(folder.root).load())
        File(folder.root, DesktopSessionStore.FILE_NAME).writeText("{broken")
        assertEquals(DesktopSession(), store.load())
    }

    @Test fun forgettingAComputerDropsOnlyItsChoices() {
        assertEquals(DesktopSession(), DesktopSession("a", true, "a").forgetting("a"))
        assertEquals(DesktopSession("a", true, null), DesktopSession("a", true, "b").forgetting("b"))
        assertEquals(DesktopSession(null, false, "a"), DesktopSession("b", true, "a").forgetting("b"))
    }
}
