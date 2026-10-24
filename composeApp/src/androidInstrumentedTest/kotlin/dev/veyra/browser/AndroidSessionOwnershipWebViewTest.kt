package dev.veyra.browser

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.test.platform.app.InstrumentationRegistry
import dev.veyra.browser.core.session.BrowserTab
import java.util.concurrent.atomic.AtomicReference
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.veyra.browser.core.BrowserEngineCreateRequest
import dev.veyra.browser.core.NavigationDecision
import dev.veyra.browser.core.NavigationRequest
import dev.veyra.browser.core.NavigationSource
import dev.veyra.browser.core.ProfileId
import dev.veyra.browser.core.TabId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.runner.RunWith

/**
 * Confirms the session-ownership invariants on the PRODUCTION Android renderer path: a real
 * device/system [WebView] created by [createWebView] and wired as the engine's [BrowserSurfacePort],
 * exactly as [AndroidChromiumSurfaceRenderer] does at runtime.
 *
 * This is the Android counterpart to [ComposeWebViewEngineTest] (which drives the same engine with a
 * fake [BrowserSurfacePort]). It re-runs the load-bearing invariants I1/I3/I6 against the real WebView
 * surface, so a green run proves they hold on the shipping Android path, not only in common unit tests.
 *
 * All assertions are about what the engine requests of its surface; they do not depend on a network
 * response, so they are deterministic.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSessionOwnershipWebViewTest {

    private lateinit var scenario: ActivityScenario<DownloadTestActivity>
    private val createdPorts = mutableListOf<RealWebViewSurfacePort>()

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(DownloadTestActivity::class.java)
    }

    @After
    fun tearDown() {
        // Tear down every port (which marks itself destroyed so no late forwarded call can touch a
        // dead WebView), then release the activity.
        // WebView APIs, including destroy(), must run on the main looper.
        // ActivityScenario.onActivity dispatches this cleanup onto the activity thread.
        scenario.onActivity {
            createdPorts.forEach { it.destroyWebView() }
            createdPorts.clear()
        }
        scenario.close()
    }

    /**
     * A real [WebView]-backed surface. It records every request the engine makes of it (that is what
     * the ownership assertions check), and faithfully forwards the request to the live WebView the way
     * [AndroidChromiumSurfaceRenderer] does at runtime — but only while the WebView is alive. Once the
     * surface is destroyed (simulating a tab close), the guard prevents the main-thread-posted
     * forwarding from calling into a destroyed WebView, which would otherwise throw on the next looper
     * dispatch and fail the whole instrumentation run.
     */
    private class RealWebViewSurfacePort(webView: WebView) : BrowserSurfacePort {
        val webView: WebView = webView
        val loadedUrls = mutableListOf<String>()
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var destroyed = false

        /** Tears down the underlying WebView and blocks any late-forwarded call from touching it. */
        fun destroyWebView() {
            destroyed = true
            webView.destroy()
        }

        override fun loadUrl(url: String) {
            loadedUrls += url
            if (!destroyed) mainHandler.post { if (!destroyed) webView.loadUrl(url) }
        }

        override fun goBack() {
            if (!destroyed) mainHandler.post { if (!destroyed && webView.canGoBack()) webView.goBack() }
        }

        override fun goForward() {
            if (!destroyed) mainHandler.post { if (!destroyed && webView.canGoForward()) webView.goForward() }
        }

        override fun reload() {
            if (!destroyed) mainHandler.post { if (!destroyed) webView.reload() }
        }

        override fun stop() {
            if (!destroyed) mainHandler.post { if (!destroyed) webView.stopLoading() }
        }
    }

    /** Builds a real WebView via the production [createWebView] factory and wraps it as a surface. */
    private fun makeSurface(tabId: TabId): RealWebViewSurfacePort {
        lateinit var port: RealWebViewSurfacePort
        scenario.onActivity { activity ->
            val webView = createWebView(
                context = activity,
                tabId = tabId,
                profileId = ProfileId("default"),
                onDownloadRequested = {},
                onNavigationRequest = { NavigationDecision.Allow },
                onNavigationDecision = {},
                onRendererGone = {},
                observe = { _, _, _, _, _, _ -> },
            )
            port = RealWebViewSurfacePort(webView)
            // Mirror the shipping renderer: the WebView is attached to the window hierarchy.
            activity.setContentView(webView)
            createdPorts += port
        }
        return port
    }

    @Test
    fun detachingSurfaceKeepsEngineAliveAndReattachesPendingNavigation() {
        val engine = ComposeWebViewEngine(
            BrowserEngineCreateRequest(TabId("tab-1"), ProfileId("default")),
        )
        val first = makeSurface(TabId("tab-1"))
        engine.attachSurface(first)
        engine.navigate(NavigationRequest(TabId("tab-1"), "https://a.example"))

        // Simulate the Compose surface leaving composition: detach (engine keeps running) and destroy
        // the native WebView.
        engine.detachSurface(first)
        scenario.onActivity { first.destroyWebView() }

        // Re-navigate while detached: must buffer, not reach the dead surface.
        engine.navigate(NavigationRequest(TabId("tab-1"), "https://b.example"))
        assertEquals(listOf("https://a.example"), first.loadedUrls)

        // Re-entry recreates the WebView/surface and re-binds; the pending navigation is delivered.
        val second = makeSurface(TabId("tab-1"))
        engine.attachSurface(second)
        assertEquals(listOf("https://b.example"), second.loadedUrls)
    }

    @Test
    fun closeIsIdempotentAndRejectsFurtherWork() {
        val engine = ComposeWebViewEngine(
            BrowserEngineCreateRequest(TabId("tab-1"), ProfileId("default")),
        )
        val surface = makeSurface(TabId("tab-1"))
        engine.attachSurface(surface)
        engine.navigate(NavigationRequest(TabId("tab-1"), "https://open.example"))

        runBlocking {
            engine.close()
            engine.close()
        }

        // A closed engine refuses a new surface.
        var attachFailed = false
        try {
            engine.attachSurface(makeSurface(TabId("tab-1")))
        } catch (_: IllegalStateException) {
            attachFailed = true
        }
        assertTrue("Attaching a surface to a closed engine must fail", attachFailed)

        // Navigation after close is a no-op; the recorded URL does not change.
        engine.navigate(NavigationRequest(TabId("tab-1"), "https://closed.example"))
        assertEquals("https://open.example", engine.pageState.value.url)
    }

    @Test
    fun rendererCrashIsObservableAndIsNotTreatedAsTabClose() {
        val engine = ComposeWebViewEngine(
            BrowserEngineCreateRequest(TabId("tab-1"), ProfileId("default")),
        )
        val surface = makeSurface(TabId("tab-1"))
        engine.attachSurface(surface)
        engine.navigate(NavigationRequest(TabId("tab-1"), "https://a.example", NavigationSource.USER))

        // The renderer process died but the session stays open (I6).
        engine.reportRendererCrashed(recoverable = true)
        assertEquals(false, engine.pageState.value.rendererHealthy)

        // Recovery: detach the dead surface, issue a new navigation, reattach a fresh WebView.
        engine.detachSurface(surface)
        scenario.onActivity { surface.destroyWebView() }
        engine.navigate(NavigationRequest(TabId("tab-1"), "https://a.example/after", NavigationSource.USER))
        val recovery = makeSurface(TabId("tab-1"))
        engine.attachSurface(recovery)
        assertEquals(listOf("https://a.example/after"), recovery.loadedUrls)
    }

    @Test
    fun lateNavigationAfterSurfaceDestroyIsSafeAndBuffered() {
        val engine = ComposeWebViewEngine(
            BrowserEngineCreateRequest(TabId("tab-1"), ProfileId("default")),
        )
        val surface = makeSurface(TabId("tab-1"))
        engine.attachSurface(surface)
        engine.navigate(NavigationRequest(TabId("tab-1"), "https://a.example"))

        // Tab closed: surface detaches and the native WebView is destroyed.
        engine.detachSurface(surface)
        scenario.onActivity { surface.destroyWebView() }

        // Late navigations must not throw and must never reach the destroyed surface.
        engine.navigate(NavigationRequest(TabId("tab-1"), "https://late.example"))
        engine.navigate(NavigationRequest(TabId("tab-1"), "https://late2.example"))
        assertEquals(
            "Late navigation must not be delivered to the destroyed surface",
            listOf("https://a.example"),
            surface.loadedUrls,
        )

        // The engine is still alive; a fresh surface receives the last buffered navigation.
        val fresh = makeSurface(TabId("tab-1"))
        engine.attachSurface(fresh)
        assertEquals(listOf("https://late2.example"), fresh.loadedUrls)
    }

    @Test
    fun closingTabDiscardsUnconsumedWebViewSnapshot() {
        val store = AndroidWebViewStateStore()
        val snapshot = Bundle().apply { putString("history-marker", "tab-a-history") }
        store.save("tab-a", snapshot)

        store.onTabClosed("tab-a")

        assertEquals(
            "A closed tab must not restore its old native navigation history",
            null,
            store.takeForRestore("tab-a"),
        )
    }

    @Test
    fun restoringWebViewSnapshotConsumesItExactlyOnce() {
        val store = AndroidWebViewStateStore()
        val snapshot = Bundle().apply { putString("history-marker", "tab-a-history") }
        store.save("tab-a", snapshot)

        assertEquals(snapshot, store.takeForRestore("tab-a"))
        assertEquals(
            "A consumed snapshot must not be reused by a later surface",
            null,
            store.takeForRestore("tab-a"),
        )
    }
    @Test
    fun webViewSaveRestorePreservesBackForwardHistory() {
        // Use actual data: URLs rather than loadDataWithBaseURL(). The latter synthesizes a
        // data:text/html;charset=utf-8;base64,... history URL on WebView, so the supplied base URL
        // is not a reliable assertion target after save/restore.
        val firstUrl = "data:text/html,%3Chtml%3E%3Cbody%3Eone%3C/body%3E%3C/html%3E"
        val secondUrl = "data:text/html,%3Chtml%3E%3Cbody%3Etwo%3C/body%3E%3C/html%3E"
        lateinit var original: WebView
        val firstPageFinished = CountDownLatch(1)
        val secondPageFinished = CountDownLatch(1)
        scenario.onActivity { activity ->
            original = WebView(activity)
            original.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    when (url) {
                        firstUrl -> firstPageFinished.countDown()
                        secondUrl -> secondPageFinished.countDown()
                    }
                }
            }
            activity.setContentView(original)
            original.loadUrl(firstUrl)
        }
        assertTrue("First document should finish loading", firstPageFinished.await(5, TimeUnit.SECONDS))

        scenario.onActivity { original.loadUrl(secondUrl) }
        assertTrue("Second document should finish loading", secondPageFinished.await(5, TimeUnit.SECONDS))

        lateinit var snapshot: Bundle
        scenario.onActivity {
            assertTrue("Second document should have a previous history entry", original.canGoBack())
            snapshot = Bundle()
            assertNotNull("saveState should return a history list", original.saveState(snapshot))
            original.stopLoading()
            original.destroy()
        }

        lateinit var restored: WebView
        lateinit var restoreResult: android.webkit.WebBackForwardList
        val backNavigationFinished = CountDownLatch(1)
        scenario.onActivity { activity ->
            restored = WebView(activity)
            restored.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (url == firstUrl) backNavigationFinished.countDown()
                }
            }
            activity.setContentView(restored)
            restoreResult = restored.restoreState(snapshot)
                ?: throw AssertionError("restoreState should restore the saved navigation list")
        }
        try {
            assertTrue("Restored history should contain at least two entries", restoreResult.size >= 2)
            lateinit var expectedBackUrl: String
            scenario.onActivity {
                assertTrue("Restored WebView should be able to navigate back", restored.canGoBack())
                val historyBeforeBack = restored.copyBackForwardList()
                assertTrue("Restored current index should be valid", historyBeforeBack.currentIndex >= 0)
                val previousHistoryItem = historyBeforeBack.getItemAtIndex(historyBeforeBack.currentIndex - 1)
                assertNotNull("Previous history item should exist", previousHistoryItem)
                expectedBackUrl = previousHistoryItem?.url
                    ?: throw AssertionError("Previous history item should have a URL")
                assertEquals("Restored history should contain the expected previous entry", firstUrl, expectedBackUrl)
                restored.goBack()
            }
            assertTrue(
                "Back navigation should finish at the expected previous URL: $expectedBackUrl",
                backNavigationFinished.await(5, TimeUnit.SECONDS),
            )
            scenario.onActivity {
                val historyAfterBack = restored.copyBackForwardList()
                val currentHistoryItem = historyAfterBack.currentItem
                assertNotNull("Current history item should exist after back navigation", currentHistoryItem)
                assertEquals(
                    "Back navigation should land on the expected previous URL",
                    expectedBackUrl,
                    currentHistoryItem?.url ?: throw AssertionError("Current history item should have a URL"),
                )
                assertTrue(
                    "Back navigation should move to an earlier history entry",
                    historyAfterBack.currentIndex >= 0 &&
                        historyAfterBack.currentIndex < historyAfterBack.size - 1,
                )
            }
        } finally {
            scenario.onActivity {
                restored.stopLoading()
                restored.destroy()
            }
        }
    }


    @Test
    fun composeTabSwitchAToBToARestoresTabAHistory() {
        val firstUrl = "data:text/html,%3Chtml%3E%3Cbody%3Etab-a-one%3C/body%3E%3C/html%3E"
        val secondUrl = "data:text/html,%3Chtml%3E%3Cbody%3Etab-a-two%3C/body%3E%3C/html%3E"
        val tabBUrl = "data:text/html,%3Chtml%3E%3Cbody%3Etab-b%3C/body%3E%3C/html%3E"
        val tabAId = TabId("compose-tab-a-${System.nanoTime()}")
        val tabBId = TabId("compose-tab-b-${System.nanoTime()}")
        val engineA = ComposeWebViewEngine(
            BrowserEngineCreateRequest(tabAId, ProfileId("default"), initialUrl = firstUrl),
        )
        val engineB = ComposeWebViewEngine(
            BrowserEngineCreateRequest(tabBId, ProfileId("default"), initialUrl = tabBUrl),
        )
        val selectTab = AtomicReference<((String) -> Unit)?>(null)

        try {
            scenario.onActivity { activity ->
                activity.setContent {
                    var selectedTab by remember { mutableStateOf("a") }
                    selectTab.set { selectedTab = it }
                    val renderer = AndroidChromiumSurfaceRenderer
                    if (selectedTab == "a") {
                        renderer.Render(
                            tab = BrowserTab(id = tabAId.value, url = engineA.pageState.value.url, title = "A"),
                            engine = engineA,
                            isVisible = true,
                            onNavigationRequest = { NavigationDecision.Allow },
                            onNavigationDecision = {},
                        )
                    } else {
                        renderer.Render(
                            tab = BrowserTab(id = tabBId.value, url = engineB.pageState.value.url, title = "B"),
                            engine = engineB,
                            isVisible = true,
                            onNavigationRequest = { NavigationDecision.Allow },
                            onNavigationDecision = {},
                        )
                    }
                }
            }
            // Creating an engine with initialUrl initializes its page state, but does not itself
            // issue a surface navigation. Drive the same explicit navigation command the browser
            // controller sends when opening a tab, after Compose has attached the renderer.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            engineA.navigate(NavigationRequest(tabAId, firstUrl, NavigationSource.USER))

            assertTrue(
                "Tab A's first document should load",
                waitForPageState(engineA) { it.url == firstUrl && it.loadPhase == dev.veyra.browser.core.BrowserLoadPhase.SUCCEEDED },
            )
            engineA.navigate(NavigationRequest(tabAId, secondUrl, NavigationSource.USER))
            assertTrue(
                "Tab A should reach its second document and have a back entry",
                waitForPageState(engineA) { it.url == secondUrl && it.canGoBack && it.loadPhase == dev.veyra.browser.core.BrowserLoadPhase.SUCCEEDED },
            )

            scenario.onActivity { selectTab.get()?.invoke("b") }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            engineB.navigate(NavigationRequest(tabBId, tabBUrl, NavigationSource.USER))
            assertTrue(
                "Tab B should attach and load while A is detached",
                waitForPageState(engineB) { it.url == tabBUrl && it.loadPhase == dev.veyra.browser.core.BrowserLoadPhase.SUCCEEDED },
            )

            scenario.onActivity { selectTab.get()?.invoke("a") }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue(
                "Tab A's restored surface should retain the second URL and back history",
                waitForPageState(engineA) { it.url == secondUrl && it.canGoBack },
            )

            engineA.goBack()
            assertTrue(
                "Back navigation after A → B → A should reach A's first history entry",
                waitForPageState(engineA) { it.url == firstUrl && !it.canGoBack && it.loadPhase == dev.veyra.browser.core.BrowserLoadPhase.SUCCEEDED },
            )
        } finally {
            scenario.onActivity { activity ->
                activity.setContent { }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            AndroidChromiumSurfaceRenderer.onTabClosed(tabAId.value)
            AndroidChromiumSurfaceRenderer.onTabClosed(tabBId.value)
            runBlocking {
                engineA.close()
                engineB.close()
            }
        }
    }

    private fun waitForPageState(
        engine: ComposeWebViewEngine,
        timeoutMs: Long = 10_000,
        predicate: (dev.veyra.browser.core.BrowserPageState) -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (predicate(engine.pageState.value)) return true
            Thread.sleep(50)
        }
        return false
    }

}
