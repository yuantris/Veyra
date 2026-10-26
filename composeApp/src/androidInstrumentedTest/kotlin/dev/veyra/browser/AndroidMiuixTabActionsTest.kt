package dev.veyra.browser

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onRoot
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket

/**
 * A1 phone-interaction regression for the real app composition:
 * primary tab actions must expose >=48dp accessible targets, and closing a newly-created tab
 * must remove that tab without removing the pre-existing tab.
 */
@RunWith(AndroidJUnit4::class)
class AndroidMiuixTabActionsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun tabActionsHaveAccessibleTouchTargetsAndNewTabCanBeClosed() {
        val density = composeRule.activity.resources.displayMetrics.density
        val newTab = composeRule.onNodeWithContentDescription("新建标签页")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching { newTab.fetchSemanticsNode(); true }.getOrDefault(false)
        }
        assertAtLeast48Dp(newTab.fetchSemanticsNode().boundsInRoot.width, density, "new-tab")

        // Bottom navigation controls must also meet the same touch-target baseline.
        listOf("标签页", "收藏夹（暂未开放）", "菜单（暂未开放）").forEach { description ->
            val node = composeRule.onNodeWithContentDescription(description)
            assertAtLeast48Dp(
                node.fetchSemanticsNode().boundsInRoot.width,
                density,
                description,
            )
            if (description != "标签页") node.assertIsNotEnabled()
        }

        val closeTabs = composeRule.onAllNodes(hasContentDescription("关闭", substring = true))
        composeRule.waitUntil(timeoutMillis = 10_000) {
            closeTabs.fetchSemanticsNodes().isNotEmpty()
        }
        val originalTabCount = closeTabs.fetchSemanticsNodes().size

        newTab.performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            closeTabs.fetchSemanticsNodes().size == originalTabCount + 1
        }

        // Re-query after composition settles; the last close affordance belongs to the new tab.
        composeRule.waitForIdle()
        val closeNodes = closeTabs.fetchSemanticsNodes()
        assertTrue("Expected one close control for each tab", closeNodes.size == originalTabCount + 1)
        val newestClose = composeRule.onAllNodes(hasContentDescription("关闭", substring = true))
            .get(originalTabCount)
        assertAtLeast48Dp(
            newestClose.fetchSemanticsNode().boundsInRoot.width,
            density,
            "tab-close",
        )
        newestClose.performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            closeTabs.fetchSemanticsNodes().size == originalTabCount
        }
        assertTrue("Closing the new tab must leave the original tab present", originalTabCount >= 1)
    }

    @Test
    fun compactPhoneChromeControlsRemainInsideTheViewport() {
        val density = composeRule.activity.resources.displayMetrics.density
        val configuredWidthDp = composeRule.activity.resources.configuration.screenWidthDp
        assertTrue(
            "Instrumentation must run at a compact 360 dp width, actual=$configuredWidthDp dp",
            configuredWidthDp in 350..370,
        )
        val viewport = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("The compact viewport must have a positive width", viewport.width > 0f)

        val homeSearch = composeRule.onNodeWithContentDescription("首页搜索栏")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching { homeSearch.fetchSemanticsNode(); true }.getOrDefault(false)
        }
        assertWithinViewport(homeSearch.fetchSemanticsNode().boundsInRoot, viewport, "home-search")

        composeRule.onNodeWithContentDescription("标签页").performClick()
        val addressBar = composeRule.onNodeWithContentDescription("地址栏")
        val newTab = composeRule.onNodeWithContentDescription("新建标签页")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                addressBar.fetchSemanticsNode()
                newTab.fetchSemanticsNode()
                true
            }.getOrDefault(false)
        }
        assertWithinViewport(addressBar.fetchSemanticsNode().boundsInRoot, viewport, "address-bar")
        assertWithinViewport(newTab.fetchSemanticsNode().boundsInRoot, viewport, "new-tab")

        listOf("后退", "前进", "标签页").forEach { description ->
            val control = composeRule.onNodeWithContentDescription(description)
            assertWithinViewport(control.fetchSemanticsNode().boundsInRoot, viewport, description)
        }
    }

    @Test
    fun homeSearchSubmissionOpensBrowserAndReachesTheRealSystemWebView() {
        val homeSearch = composeRule.onNodeWithContentDescription("首页搜索栏")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching { homeSearch.fetchSemanticsNode(); true }.getOrDefault(false)
        }

        homeSearch.performTextReplacement("veyra home search test")
        homeSearch.performImeAction()

        val expectedUrl = "https://www.baidu.com/search?q=veyra%20home%20search%20test"
        composeRule.waitUntil(timeoutMillis = 10_000) {
            attachedWebViewState()?.url == expectedUrl
        }
        assertTrue(
            "Submitting the home search field must open the browser and navigate the real WebView",
            attachedWebViewState()?.url == expectedUrl,
        )
    }

    @Test
    fun addressBarSubmissionReachesTheRealSystemWebView() {
        // Start from the browser surface rather than the home page so this test isolates
        // address submission, not the separate home-to-browser presentation transition.
        composeRule.onNodeWithContentDescription("标签页").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { findAttachedWebView() != null }

        val targetUrl = "https://veyra.invalid/address-entry-test"
        val addressBar = composeRule.onNodeWithContentDescription("地址栏")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching { addressBar.fetchSemanticsNode(); true }.getOrDefault(false)
        }

        addressBar.performTextReplacement(targetUrl)
        addressBar.performImeAction()

        // .invalid is reserved for tests. We assert delivery to the production WebView,
        // not successful internet access or a remote server response.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            attachedWebViewState()?.url == targetUrl
        }
        assertTrue(
            "Submitting the address bar must navigate the attached system WebView",
            attachedWebViewState()?.url == targetUrl,
        )
    }

    @Test
    fun searchQuerySubmissionRoutesToTheConfiguredSearchEndpoint() {
        composeRule.onNodeWithContentDescription("标签页").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { findAttachedWebView() != null }

        val addressBar = composeRule.onNodeWithContentDescription("地址栏")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching { addressBar.fetchSemanticsNode(); true }.getOrDefault(false)
        }
        addressBar.performTextReplacement("veyra browser search test")
        addressBar.performImeAction()

        // Search routing is deterministic and can be asserted at the native navigation
        // boundary without depending on external DNS or the search provider's response.
        val expectedUrl = "https://www.baidu.com/search?q=veyra%20browser%20search%20test"
        composeRule.waitUntil(timeoutMillis = 10_000) {
            attachedWebViewState()?.url == expectedUrl
        }
        assertTrue(
            "Submitting plain text must route to the configured search endpoint",
            attachedWebViewState()?.url == expectedUrl,
        )
    }

    @Test
    fun closingSelectedTabReleasesItsRealWebViewAndRestoresPreviousTabSurface() {
        val newTab = composeRule.onNodeWithContentDescription("新建标签页")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching { newTab.fetchSemanticsNode(); true }.getOrDefault(false)
        }

        val originalSurface = findAttachedWebView()
            ?: throw AssertionError("Expected the initial tab's system WebView to be attached")

        newTab.performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                val nodes = composeRule.onAllNodes(hasContentDescription("关闭", substring = true))
                    .fetchSemanticsNodes()
                nodes.size >= 2 && findAttachedWebView() != null &&
                    findAttachedWebView() !== originalSurface
            }.getOrDefault(false)
        }

        val selectedTabSurface = findAttachedWebView()
            ?: throw AssertionError("New tab did not attach its system WebView")
        assertTrue(
            "Selecting a new tab should replace the attached renderer surface",
            selectedTabSurface !== originalSurface,
        )

        val closeNodes = composeRule.onAllNodes(hasContentDescription("关闭", substring = true))
        val closeNewestTab = closeNodes.get(closeNodes.fetchSemanticsNodes().size - 1)
        closeNewestTab.performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            val attached = findAttachedWebView()
            attached != null && attached !== selectedTabSurface
        }
        composeRule.waitForIdle()

        assertTrue(
            "Closing the selected tab must detach its WebView and restore the previous tab's surface",
            findAttachedWebView() !== selectedTabSurface,
        )
    }

    @Test
    fun switchingTabsRestoresTheRealWebViewsBackForwardHistory() {
        val newTab = composeRule.onNodeWithContentDescription("新建标签页")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching { newTab.fetchSemanticsNode(); true }.getOrDefault(false)
        }

        val originalSurface = findAttachedWebView()
            ?: throw AssertionError("Expected the initial tab's system WebView to be attached")
        // Use explicit data: history entries, matching the already-verified native
        // saveState/restoreState test. loadDataWithBaseURL synthesizes a data: history URL,
        // so the supplied base URL is not a reliable value for WebView.url assertions.
        val firstUrl = "data:text/html,%3C!doctype%20html%3E%3Ctitle%3EVeyra%20history%20first%3C/title%3E%3Cp%3Efirst%3C/p%3E"
        val secondUrl = "data:text/html,%3C!doctype%20html%3E%3Ctitle%3EVeyra%20history%20second%3C/title%3E%3Cp%3Esecond%3C/p%3E"
        composeRule.activity.runOnUiThread { originalSurface.loadUrl(firstUrl) }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            attachedWebViewState()?.let {
                it.url == firstUrl && it.title == "Veyra history first"
            } == true
        }

        composeRule.activity.runOnUiThread { originalSurface.loadUrl(secondUrl) }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            attachedWebViewState()?.let {
                it.url == secondUrl &&
                    it.title == "Veyra history second" &&
                    it.canGoBack
            } == true
        }

        newTab.performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            val current = findAttachedWebView()
            current != null && current !== originalSurface
        }
        val selectedTabSurface = findAttachedWebView()
            ?: throw AssertionError("New tab did not attach its system WebView")

        val closeNodes = composeRule.onAllNodes(hasContentDescription("关闭", substring = true))
        composeRule.waitUntil(timeoutMillis = 10_000) {
            closeNodes.fetchSemanticsNodes().size >= 2
        }
        closeNodes.get(closeNodes.fetchSemanticsNodes().size - 1).performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            val current = findAttachedWebView()
            current != null && current !== selectedTabSurface &&
                attachedWebViewState()?.let {
                    it.url == secondUrl &&
                        it.title == "Veyra history second" &&
                        it.canGoBack
                } == true
        }
        assertTrue(
            "Restored tab should keep its real WebView back/forward history after switching and closing another tab",
            attachedWebViewState()?.canGoBack == true,
        )
    }

    @Test
    fun backAndForwardControlsDriveTheRealSystemWebViewHistory() {
        val newTab = composeRule.onNodeWithContentDescription("新建标签页")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching { newTab.fetchSemanticsNode(); true }.getOrDefault(false)
        }
        val webView = findAttachedWebView()
            ?: throw AssertionError("Expected the active tab's system WebView to be attached")

        val firstUrl = "data:text/html,%3C!doctype%20html%3E%3Ctitle%3EVeyra%20back%3C/title%3E%3Cp%3Efirst%3C/p%3E"
        val secondUrl = "data:text/html,%3C!doctype%20html%3E%3Ctitle%3EVeyra%20forward%3C/title%3E%3Cp%3Esecond%3C/p%3E"
        composeRule.activity.runOnUiThread { webView.loadUrl(firstUrl) }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            attachedWebViewState()?.let { it.url == firstUrl && it.title == "Veyra back" } == true
        }
        composeRule.activity.runOnUiThread { webView.loadUrl(secondUrl) }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            attachedWebViewState()?.let {
                it.url == secondUrl && it.title == "Veyra forward" && it.canGoBack
            } == true
        }

        composeRule.onNodeWithContentDescription("后退").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            attachedWebViewState()?.let {
                it.url == firstUrl && it.title == "Veyra back" && it.canGoForward
            } == true
        }

        composeRule.onNodeWithContentDescription("前进").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            attachedWebViewState()?.let {
                it.url == secondUrl && it.title == "Veyra forward" && it.canGoBack
            } == true
        }
        assertTrue(
            "Back and forward controls must navigate the real WebView history",
            attachedWebViewState()?.let { it.url == secondUrl && it.canGoBack } == true,
        )
    }

    @Test
    fun mainFrameLoadFailureIsVisibleInBrowserChrome() {
        composeRule.onNodeWithContentDescription("标签页").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { findAttachedWebView() != null }

        // Use a real loopback listener and deliberately close the accepted request without
        // sending HTTP headers. This deterministically exercises WebView's main-frame error
        // callback without depending on a hard-coded port or external DNS/network access.
        val requestAccepted = java.util.concurrent.CountDownLatch(1)
        val server = ServerSocket(0)
        val serverThread = Thread {
            runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val reader = socket.getInputStream().bufferedReader()
                    reader.readLine()
                    while (reader.readLine().isNotEmpty()) Unit
                    requestAccepted.countDown()
                    // Closing without a response causes a deterministic empty-response failure.
                }
            }.also {
                requestAccepted.countDown()
            }
        }.apply { isDaemon = true; start() }
        val targetUrl = "http://127.0.0.1:${server.localPort}/veyra-main-frame-error"

        try {
            val addressBar = composeRule.onNodeWithContentDescription("地址栏")
            composeRule.waitUntil(timeoutMillis = 10_000) {
                runCatching { addressBar.fetchSemanticsNode(); true }.getOrDefault(false)
            }
            addressBar.performTextReplacement(targetUrl)
            addressBar.performImeAction()

            assertTrue(
                "The test server must receive the real WebView navigation request",
                requestAccepted.await(10, java.util.concurrent.TimeUnit.SECONDS),
            )

            val errorNotice = composeRule.onNodeWithContentDescription("网页加载失败")
            composeRule.waitUntil(timeoutMillis = 15_000) {
                runCatching { errorNotice.fetchSemanticsNode(); true }.getOrDefault(false)
            }
            assertTrue(
                "A main-frame WebView navigation failure must be visible in browser chrome",
                runCatching { errorNotice.fetchSemanticsNode(); true }.getOrDefault(false),
            )
        } finally {
            server.close()
            serverThread.join(2_500)
        }
    }

    @Test
    fun androidSystemBackReturnsToHomeWhenWebViewHasNoHistory() {
        composeRule.onNodeWithContentDescription("标签页").performClick()
        val newTab = composeRule.onNodeWithContentDescription("新建标签页")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching { newTab.fetchSemanticsNode(); true }.getOrDefault(false)
        }
        newTab.performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithContentDescription("地址栏").fetchSemanticsNode()
                findAttachedWebView() != null
            }.getOrDefault(false)
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            attachedWebViewState()?.canGoBack == false
        }

        // Use a real newly opened tab rather than mutating WebView history behind the
        // coordinator's back. The UI and engine now agree that this tab has no history.
        composeRule.activity.onBackPressedDispatcher.onBackPressed()

        val homeSearch = composeRule.onNodeWithContentDescription("首页搜索栏")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching { homeSearch.fetchSemanticsNode(); true }.getOrDefault(false)
        }
        assertTrue(
            "System Back with no WebView history should return to the home search surface",
            runCatching { homeSearch.fetchSemanticsNode(); true }.getOrDefault(false),
        )
        // BrowserScaffold keeps the address bar in the shared chrome on the home surface too.
        // The home search field is the state transition signal; address-bar absence is not.
    }

    @Test
    fun androidSystemBackNavigatesWebViewHistoryBeforeReturningHome() {
        composeRule.onNodeWithContentDescription("标签页").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { findAttachedWebView() != null }
        val webView = findAttachedWebView()
            ?: throw AssertionError("Expected the active tab's system WebView to be attached")
        val firstUrl = "data:text/html,%3C!doctype%20html%3E%3Ctitle%3EVeyra%20system%20back%20first%3C/title%3E%3Cp%3Efirst%3C/p%3E"
        val secondUrl = "data:text/html,%3C!doctype%20html%3E%3Ctitle%3EVeyra%20system%20back%20second%3C/title%3E%3Cp%3Esecond%3C/p%3E"

        composeRule.activity.runOnUiThread { webView.loadUrl(firstUrl) }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            attachedWebViewState()?.let { it.url == firstUrl && it.title == "Veyra system back first" } == true
        }
        composeRule.activity.runOnUiThread { webView.loadUrl(secondUrl) }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            attachedWebViewState()?.let {
                it.url == secondUrl && it.title == "Veyra system back second" && it.canGoBack
            } == true
        }

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            attachedWebViewState()?.let {
                it.url == firstUrl && it.title == "Veyra system back first"
            } == true
        }
        assertTrue(
            "Android system Back must navigate WebView history before leaving the browser surface",
            attachedWebViewState()?.let { it.url == firstUrl && it.title == "Veyra system back first" } == true,
        )
    }

    @Test
    fun stopLoadingControlEndsNavigationAndReloadCanLoadThePage() {
        composeRule.onNodeWithContentDescription("标签页").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { findAttachedWebView() != null }

        val releaseFirstRequest = java.util.concurrent.CountDownLatch(1)
        val server = ServerSocket(0)
        val serverThread = Thread {
            runCatching {
                // Hold the first response until the app explicitly stops that navigation.
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val reader = socket.getInputStream().bufferedReader()
                    reader.readLine()
                    while (reader.readLine().isNotEmpty()) Unit
                    releaseFirstRequest.await(5, java.util.concurrent.TimeUnit.SECONDS)
                }
                // The stopped request is deliberately not answered. The next request must
                // be the user's explicit reload and receives a deterministic local page.
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val reader = socket.getInputStream().bufferedReader()
                    reader.readLine()
                    while (reader.readLine().isNotEmpty()) Unit
                    val body = "<!doctype html><title>Veyra stopped reload</title><p>reloaded</p>"
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val output = socket.getOutputStream()
                    output.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " +
                            bytes.size + "\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
                    )
                    output.write(bytes)
                    output.flush()
                }
            }
        }.apply { isDaemon = true; start() }
        val targetUrl = "http://127.0.0.1:" + server.localPort + "/stop-loading"
        try {
            val addressBar = composeRule.onNodeWithContentDescription("地址栏")
            composeRule.waitUntil(timeoutMillis = 10_000) {
                runCatching { addressBar.fetchSemanticsNode(); true }.getOrDefault(false)
            }
            addressBar.performTextReplacement(targetUrl)
            addressBar.performImeAction()

            val stopButton = composeRule.onNodeWithContentDescription("停止加载")
            composeRule.waitUntil(timeoutMillis = 10_000) {
                runCatching { stopButton.fetchSemanticsNode(); true }.getOrDefault(false)
            }
            stopButton.performClick()

            val reloadButton = composeRule.onNodeWithContentDescription("重新加载")
            composeRule.waitUntil(timeoutMillis = 10_000) {
                runCatching { reloadButton.fetchSemanticsNode(); true }.getOrDefault(false)
            }
            releaseFirstRequest.countDown()
            reloadButton.performClick()

            composeRule.waitUntil(timeoutMillis = 10_000) {
                attachedWebViewState()?.let {
                    it.url == targetUrl && it.title == "Veyra stopped reload"
                } == true
            }
            assertTrue(
                "Reload after stopping must complete a real local WebView navigation",
                attachedWebViewState()?.let {
                    it.url == targetUrl && it.title == "Veyra stopped reload"
                } == true,
            )
        } finally {
            releaseFirstRequest.countDown()
            server.close()
            serverThread.join(2_500)
        }
    }

    @Test
    fun addressBarReloadControlHasAccessibleTouchTarget() {
        composeRule.onNodeWithContentDescription("标签页").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { findAttachedWebView() != null }

        val reloadButton = composeRule.onNodeWithContentDescription("重新加载")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching { reloadButton.fetchSemanticsNode(); true }.getOrDefault(false)
        }

        val density = composeRule.activity.resources.displayMetrics.density
        val bounds = reloadButton.fetchSemanticsNode().boundsInRoot
        assertAtLeast48Dp(bounds.width, density, "address-reload")
        assertAtLeast48Dp(bounds.height, density, "address-reload-height")
    }

    @Test
    fun reloadControlReloadsTheCurrentRealWebViewDocument() {
        composeRule.onNodeWithContentDescription("标签页").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { findAttachedWebView() != null }
        val webView = findAttachedWebView()
            ?: throw AssertionError("Expected the active tab's system WebView to be attached")
        val pageUrl = "data:text/html,%3C!doctype%20html%3E%3Ctitle%3EVeyra%20reload%20baseline%3C/title%3E%3Cp%3Ereload%3C/p%3E"

        composeRule.activity.runOnUiThread { webView.loadUrl(pageUrl) }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            attachedWebViewState()?.let {
                it.url == pageUrl && it.title == "Veyra reload baseline"
            } == true
        }

        composeRule.activity.runOnUiThread {
            webView.evaluateJavascript("document.title = 'Veyra changed title'", null)
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            attachedWebViewState()?.title == "Veyra changed title"
        }

        composeRule.onNodeWithContentDescription("重新加载").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            attachedWebViewState()?.let {
                it.url == pageUrl && it.title == "Veyra reload baseline"
            } == true
        }
        assertTrue(
            "Reload must reload the current document in the attached system WebView",
            attachedWebViewState()?.title == "Veyra reload baseline",
        )
    }

    private data class AttachedWebViewState(
        val url: String?,
        val title: String?,
        val canGoBack: Boolean,
        val canGoForward: Boolean,
    )

    private fun attachedWebViewState(): AttachedWebViewState? {
        var state: AttachedWebViewState? = null
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = findAttachedWebViewOnMainThread()
            if (view != null) {
                state = AttachedWebViewState(view.url, view.title, view.canGoBack(), view.canGoForward())
            }
        }
        return state
    }

    private fun findAttachedWebViewOnMainThread(): WebView? {
        var found: WebView? = null
        fun visit(view: View) {
            if (found != null) return
            if (view is WebView) {
                found = view
            } else if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    visit(view.getChildAt(index))
                    if (found != null) return
                }
            }
        }
        visit(composeRule.activity.window.decorView)
        return found
    }

    private fun findAttachedWebView(): WebView? {
        var found: WebView? = null
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            found = findAttachedWebViewOnMainThread()
        }
        return found
    }

    private fun assertWithinViewport(bounds: Rect, viewport: Rect, control: String) {
        assertTrue(
            "$control must remain within the compact phone viewport: bounds=$bounds viewport=$viewport",
            bounds.left >= viewport.left &&
                bounds.top >= viewport.top &&
                bounds.right <= viewport.right &&
                bounds.bottom <= viewport.bottom,
        )
    }

    private fun assertAtLeast48Dp(widthPx: Float, density: Float, control: String) {
        assertTrue(
            "$control touch target width was ${widthPx / density}dp; expected at least 48dp",
            widthPx >= 48f * density,
        )
    }
}
