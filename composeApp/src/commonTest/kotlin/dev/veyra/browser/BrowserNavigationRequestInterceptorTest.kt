package dev.veyra.browser

import com.multiplatform.webview.request.WebRequest
import com.multiplatform.webview.request.WebRequestInterceptResult
import dev.veyra.browser.core.NavigationDecision
import dev.veyra.browser.core.NavigationRequest
import dev.veyra.browser.core.NavigationSource
import dev.veyra.browser.core.TabId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserNavigationRequestInterceptorTest {
    @Test
    fun allowsMainFrameWhenPolicyAllows() {
        val seen = mutableListOf<NavigationRequest>()
        val interceptor = BrowserNavigationRequestInterceptor(
            tabId = TabId("tab-1"),
            onNavigationRequest = { seen += it; NavigationDecision.Allow },
            onNavigationDecision = { error("No host action expected for Allow") },
        )

        val result = interceptor.intercept(
            WebRequest(url = "https://example.com", isForMainFrame = true),
        )

        assertEquals(WebRequestInterceptResult.Allow, result)
        assertEquals(1, seen.size)
        assertEquals(TabId("tab-1"), seen.single().tabId)
        assertEquals(NavigationSource.PAGE, seen.single().source)
        assertTrue(seen.single().isMainFrame)
    }

    @Test
    fun rejectsMainFrameWhenPolicyCancels() {
        val interceptor = BrowserNavigationRequestInterceptor(
            tabId = TabId("tab-1"),
            onNavigationRequest = { NavigationDecision.Cancel },
            onNavigationDecision = { error("No host action expected for Cancel") },
        )

        assertEquals(
            WebRequestInterceptResult.Reject,
            interceptor.intercept(WebRequest(url = "https://blocked.example", isForMainFrame = true)),
        )
    }

    @Test
    fun routesExternalDecisionToHostAndRejectsNativeNavigation() {
        val hostDecisions = mutableListOf<NavigationDecision>()
        val interceptor = BrowserNavigationRequestInterceptor(
            tabId = TabId("tab-1"),
            onNavigationRequest = { NavigationDecision.OpenExternal(it.url) },
            onNavigationDecision = { hostDecisions += it },
        )
        val request = WebRequest(url = "mailto:help@example.com", isForMainFrame = true)

        assertEquals(WebRequestInterceptResult.Reject, interceptor.intercept(request))
        assertEquals(1, hostDecisions.size)
        assertTrue(hostDecisions.single() is NavigationDecision.OpenExternal)
        assertEquals(request.url, (hostDecisions.single() as NavigationDecision.OpenExternal).url)
    }

    @Test
    fun routesNewTabDecisionToHostAndRejectsCurrentNavigation() {
        val hostDecisions = mutableListOf<NavigationDecision>()
        val interceptor = BrowserNavigationRequestInterceptor(
            tabId = TabId("tab-1"),
            onNavigationRequest = { NavigationDecision.OpenInNewTab(it.url) },
            onNavigationDecision = { hostDecisions += it },
        )
        val request = WebRequest(url = "https://new-tab.example", isForMainFrame = true)

        assertEquals(WebRequestInterceptResult.Reject, interceptor.intercept(request))
        assertEquals(1, hostDecisions.size)
        assertTrue(hostDecisions.single() is NavigationDecision.OpenInNewTab)
        assertEquals(request.url, (hostDecisions.single() as NavigationDecision.OpenInNewTab).url)
    }

    @Test
    fun subframeRequestsBypassTopLevelPolicy() {
        var policyCalled = false
        val interceptor = BrowserNavigationRequestInterceptor(
            tabId = TabId("tab-1"),
            onNavigationRequest = {
                policyCalled = true
                NavigationDecision.Cancel
            },
            onNavigationDecision = { error("No host action expected for subframe") },
        )

        assertEquals(
            WebRequestInterceptResult.Allow,
            interceptor.intercept(
                WebRequest(url = "https://embedded.example", isForMainFrame = false),
            ),
        )
        assertFalse(policyCalled)
    }

    @Test
    fun redirectsAreMarkedAsRedirectSource() {
        var captured: NavigationRequest? = null
        val interceptor = BrowserNavigationRequestInterceptor(
            tabId = TabId("tab-1"),
            onNavigationRequest = { captured = it; NavigationDecision.Allow },
            onNavigationDecision = { error("No host action expected for Allow") },
        )

        interceptor.intercept(
            WebRequest(
                url = "https://redirect-target.example",
                isForMainFrame = true,
                isRedirect = true,
            ),
        )

        assertEquals(NavigationSource.REDIRECT, captured?.source)
    }
}
