package dev.veyra.browser.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserNavigationCoordinatorTest {
    @Test
    fun defaultPolicyAllowsWebAndInternalSchemes() = runBlocking {
        val coordinator = BrowserNavigationCoordinator()

        assertEquals(
            NavigationDecision.Allow,
            coordinator.decide(NavigationRequest(TabId("tab-1"), "https://example.com")),
        )
        assertEquals(
            NavigationDecision.Allow,
            coordinator.decide(NavigationRequest(TabId("tab-1"), "about:blank")),
        )
    }

    @Test
    fun defaultPolicyRoutesUnknownSchemesExternally() = runBlocking {
        val coordinator = BrowserNavigationCoordinator()
        val url = "mailto:help@example.com"

        assertEquals(
            NavigationDecision.OpenExternal(url),
            coordinator.decide(NavigationRequest(TabId("tab-1"), url)),
        )
    }

    @Test
    fun malformedBlankUrlIsCancelled() = runBlocking {
        val coordinator = BrowserNavigationCoordinator()

        assertEquals(
            NavigationDecision.Cancel,
            coordinator.decide(NavigationRequest(TabId("tab-1"), "  ")),
        )
        assertEquals(
            NavigationDecision.Cancel,
            coordinator.decide(NavigationRequest(TabId("tab-1"), "not a url")),
        )
    }
    @Test
    fun defaultPolicyRejectsExecutableAndUntrustedTopLevelSchemes() {
        val coordinator = BrowserNavigationCoordinator()

        listOf(
            "javascript:alert(1)",
            "vbscript:msgbox(1)",
            "file:///etc/passwd",
            "data:text/html,hello",
            "blob:https://example.com/id",
            "content://provider/item",
            "intent://example.com",
        ).forEach { url ->
            assertEquals(NavigationDecision.Cancel, coordinator.decide(NavigationRequest(TabId("tab-1"), url)), url)
        }
    }

    @Test
    fun malformedWebUrlsAndWhitespaceAreCancelled() {
        val coordinator = BrowserNavigationCoordinator()

        listOf(
            "https:",
            "https:///missing-host",
            "https://",
            " https://example.com",
            "https://example.com ",
        ).forEach { url ->
            assertEquals(NavigationDecision.Cancel, coordinator.decide(NavigationRequest(TabId("tab-1"), url)), url)
        }
    }

    @Test
    fun scriptSchemesCannotBeAllowedByCustomPolicy() {
        val coordinator = BrowserNavigationCoordinator(
            policyHandler = NavigationPolicyHandler { NavigationDecision.Allow },
        )

        assertEquals(
            NavigationDecision.Cancel,
            coordinator.decide(NavigationRequest(TabId("tab-1"), "javascript:alert(1)")),
        )
    }

}
