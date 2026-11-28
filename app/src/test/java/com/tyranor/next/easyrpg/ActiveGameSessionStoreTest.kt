package com.tyranor.next.easyrpg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ActiveGameSessionStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun writesAndReadsSessionMetadata() {
        val file = File(tempFolder.root, "active_game_session.json")
        val gameDir = tempFolder.newFolder("YumeNikki")
        val store = ActiveGameSessionStore(file)

        store.write(pid = 4242, gameDir = gameDir.absolutePath, gameTitle = "Yume Nikki")

        val resolved = store.resolveActiveSession(4242)
        assertNotNull(resolved)
        assertEquals(4242, resolved?.pid)
        assertEquals(gameDir.canonicalPath, resolved?.gameDir)
        assertEquals("Yume Nikki", resolved?.gameTitle)
    }

    @Test
    fun clearsStaleMetadataWhenGameProcessIsDead() {
        val file = File(tempFolder.root, "active_game_session.json")
        val gameDir = tempFolder.newFolder("GameOne")
        val store = ActiveGameSessionStore(file)

        store.write(pid = 1111, gameDir = gameDir.absolutePath, gameTitle = "Game One")
        assertTrue(file.isFile)

        assertNull(store.resolveActiveSession(null))
        assertFalse(file.exists())
    }

    @Test
    fun clearIfOwnedOnlyDeletesMatchingPid() {
        val file = File(tempFolder.root, "active_game_session.json")
        val gameDir = tempFolder.newFolder("GameTwo")
        val store = ActiveGameSessionStore(file)

        store.write(pid = 2222, gameDir = gameDir.absolutePath, gameTitle = "Game Two")
        store.clearIfOwned(9999)
        assertNotNull(store.read())

        store.clearIfOwned(2222)
        assertNull(store.read())
    }

    @Test
    fun returnsProcessLockEvenWhenMetadataPidDoesNotMatchOrIsMissing() {
        val file = File(tempFolder.root, "active_game_session.json")
        val store = ActiveGameSessionStore(file)

        val inFlight = store.resolveActiveSession(3333)
        assertNotNull(inFlight)
        assertEquals(3333, inFlight?.pid)
        assertNull(inFlight?.gameDir)
    }

    @Test
    fun comparesGamePathsUsingCanonicalNormalization() {
        val gameDir = tempFolder.newFolder("Ib")
        val withTrailingSlash = "${gameDir.absolutePath}/"
        val withDotSegment = "${gameDir.parentFile!!.absolutePath}/./${gameDir.name}"

        assertTrue(ActiveGameSessionStore.isSameGame(gameDir.absolutePath, withTrailingSlash))
        assertTrue(ActiveGameSessionStore.isSameGame(gameDir.absolutePath, withDotSegment))
        assertFalse(ActiveGameSessionStore.isSameGame(null, gameDir.absolutePath))
        assertFalse(ActiveGameSessionStore.isSameGame(gameDir.absolutePath, tempFolder.newFolder("Other").absolutePath))
    }
}
