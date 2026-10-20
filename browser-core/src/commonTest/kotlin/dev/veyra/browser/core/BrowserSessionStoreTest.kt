package dev.veyra.browser.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BrowserSessionStoreTest {
    @Test
    fun openingAndSelectingTabsKeepsIndependentTabMetadata() {
        val store = BrowserSessionStore("https://example.com")
        val first = store.activeTab ?: error("Initial tab missing")
        val second = store.openTab("https://openai.com", "OpenAI")

        assertNotEquals(first.id, second.id)
        assertEquals(second.id, store.state.value.activeTabId)
        assertTrue(store.selectTab(first.id))
        assertEquals(first.id, store.state.value.activeTabId)
        assertEquals("https://example.com", store.activeTab?.url)
    }

    @Test
    fun closingLastTabCreatesAnEmptyReplacement() {
        val store = BrowserSessionStore()
        val initialId = store.activeTab?.id ?: error("Initial tab missing")

        assertTrue(store.closeTab(initialId))
        assertEquals(1, store.state.value.tabs.size)
        assertNotEquals(initialId, store.state.value.activeTabId)
        assertEquals("about:blank", store.activeTab?.url)
    }

    @Test
    fun privateTabsDoNotRecordHistory() {
        val store = BrowserSessionStore()
        val privateTab = store.openTab(isPrivate = true)

        assertTrue(store.recordVisit(privateTab.id, "https://private.example", "Private"))
        assertTrue(store.state.value.history.isEmpty())

        val regularTab = store.openTab()
        assertTrue(store.recordVisit(regularTab.id, "https://public.example", "Public"))
        assertEquals(listOf("https://public.example"), store.state.value.history.map { it.url })
    }

    @Test
    fun bookmarksAreUniqueByUrlAndCanBeRemoved() {
        val store = BrowserSessionStore()

        assertTrue(store.addBookmark("https://example.com", "Example"))
        assertTrue(store.addBookmark("https://example.com", "Updated title"))
        assertEquals(1, store.state.value.bookmarks.size)
        assertEquals("Updated title", store.state.value.bookmarks.single().title)
        assertTrue(store.removeBookmark("https://example.com"))
        assertFalse(store.removeBookmark("https://example.com"))
    }
}
