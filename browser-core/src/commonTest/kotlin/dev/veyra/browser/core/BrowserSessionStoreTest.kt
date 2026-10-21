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

    @Test
    fun closingActiveTabSelectsTheNearestRemainingTab() {
        val store = BrowserSessionStore("https://first.example")
        val first = store.activeTab ?: error("Initial tab missing")
        val second = store.openTab("https://second.example", "Second")
        val third = store.openTab("https://third.example", "Third")

        assertTrue(store.closeTab(third.id))
        assertEquals(second.id, store.state.value.activeTabId)
        assertEquals(listOf(first.id, second.id), store.state.value.tabs.map { it.id })

        assertTrue(store.closeTab(second.id))
        assertEquals(first.id, store.state.value.activeTabId)
        assertEquals(listOf(first.id), store.state.value.tabs.map { it.id })
    }

    @Test
    fun closingInactiveTabDoesNotChangeTheActiveTab() {
        val store = BrowserSessionStore()
        val first = store.activeTab ?: error("Initial tab missing")
        val second = store.openTab("https://second.example", "Second")

        assertTrue(store.selectTab(first.id))
        assertTrue(store.closeTab(second.id))

        assertEquals(first.id, store.state.value.activeTabId)
        assertEquals(listOf(first.id), store.state.value.tabs.map { it.id })
    }

    @Test
    fun closedTabRejectsLateEngineCallbacks() {
        val store = BrowserSessionStore()
        val first = store.activeTab ?: error("Initial tab missing")
        val second = store.openTab("https://second.example", "Second")

        assertTrue(store.closeTab(second.id))
        assertFalse(store.updateTab(second.id, url = "https://late.example", isLoading = false))
        assertFalse(store.recordVisit(second.id, "https://late.example", "Late callback"))
        assertEquals(listOf(first.id), store.state.value.tabs.map { it.id })
        assertTrue(store.state.value.history.isEmpty())
    }

    @Test
    fun historyDeduplicatesUrlsAndCanBeCleared() {
        val store = BrowserSessionStore()
        val tab = store.activeTab ?: error("Initial tab missing")

        assertTrue(store.recordVisit(tab.id, "https://one.example", "One"))
        assertTrue(store.recordVisit(tab.id, "https://two.example", "Two"))
        assertTrue(store.recordVisit(tab.id, "https://one.example", "One updated"))

        assertEquals(
            listOf("https://one.example", "https://two.example"),
            store.state.value.history.map { it.url },
        )
        store.clearHistory()
        assertTrue(store.state.value.history.isEmpty())
    }

    @Test
    fun closingLastTabPreservesBookmarksAndHistory() {
        val store = BrowserSessionStore()
        val tab = store.activeTab ?: error("Initial tab missing")
        store.addBookmark("https://saved.example", "Saved")
        store.recordVisit(tab.id, "https://visited.example", "Visited")

        assertTrue(store.closeTab(tab.id))

        assertEquals(listOf("https://saved.example"), store.state.value.bookmarks.map { it.url })
        assertEquals(listOf("https://visited.example"), store.state.value.history.map { it.url })
    }
}
