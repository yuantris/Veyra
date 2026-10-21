package dev.veyra.browser.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserProfileTest {
    @Test
    fun defaultProfileIsRegularAndPersistent() {
        val provider = InMemoryBrowserProfileProvider()

        assertEquals(BrowserProfileKind.REGULAR, provider.defaultProfile().kind)
        assertEquals(BrowserStoragePolicy.PERSISTENT, provider.defaultProfile().storagePolicy)
    }

    @Test
    fun privateProfileRequiresEphemeralStorageAndNoHistory() {
        assertFailsWith<IllegalArgumentException> {
            BrowserProfile(
                id = ProfileId("private"),
                kind = BrowserProfileKind.PRIVATE,
                storagePolicy = BrowserStoragePolicy.PERSISTENT,
                allowsHistory = false,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BrowserProfile(
                id = ProfileId("private"),
                kind = BrowserProfileKind.PRIVATE,
                storagePolicy = BrowserStoragePolicy.EPHEMERAL,
                allowsHistory = true,
            )
        }
    }

    @Test
    fun providerRejectsUnknownProfileAndKeepsDefaultRegular() {
        val provider = InMemoryBrowserProfileProvider()

        assertNull(provider.get(ProfileId("missing")))
        assertTrue(provider.defaultProfile().id == ProfileId("default"))
        assertFalse(provider.defaultProfile().isPrivate)
    }
}
