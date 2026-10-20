package dev.veyra.browser.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BrowserContractsTest {
    @Test
    fun pageStateAllowsUnknownProgressAndValidNormalizedProgress() {
        assertNull(BrowserPageState(tabId = TabId("tab-1")).progress)
        assertEquals(0, BrowserPageState(tabId = TabId("tab-1"), progress = 0).progress)
        assertEquals(100, BrowserPageState(tabId = TabId("tab-1"), progress = 100).progress)
    }

    @Test
    fun pageStateRejectsProgressOutsideNormalizedRange() {
        assertFailsWith<IllegalArgumentException> {
            BrowserPageState(tabId = TabId("tab-1"), progress = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            BrowserPageState(tabId = TabId("tab-1"), progress = 101)
        }
    }

    @Test
    fun navigationEventsCarryTabAndNavigationIdentity() {
        val tabId = TabId("tab-7")
        val navigationId = NavigationId("navigation-12")
        val event = BrowserEvent.NavigationCommitted(
            tabId = tabId,
            navigationId = navigationId,
            url = "https://example.com",
        )

        assertEquals(tabId, event.tabId)
        assertEquals(navigationId, (event as BrowserEvent.NavigationCommitted).navigationId)
    }

    @Test
    fun unsupportedCapabilitiesAreExplicit() {
        val capabilities = BrowserCapabilities()

        assertEquals(RequestInterceptionScope.NONE, capabilities.requestInterception)
        assertEquals(false, capabilities.webExtensions)
        assertEquals(false, capabilities.privateProfiles)
    }
}
