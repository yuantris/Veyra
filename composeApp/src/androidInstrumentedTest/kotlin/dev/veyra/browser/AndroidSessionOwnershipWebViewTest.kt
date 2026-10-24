package dev.veyra.browser

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.test.platform.app.InstrumentationRegistry
import dev.veyra.browser.core.session.BrowserTab
import dev.veyra.browser.core.session.BrowserSessionCoordinator
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private fun makeSurface(
        tabId: TabId,
        observe: (WebView, String?, String?, Boolean, String?, String?) -> Unit = { _, _, _, _, _, _ -> },
    ): RealWebViewSurfacePort {
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
                observe = observe,
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
        val firstUrl = "data:text/html,tab-a-one"
        val secondUrl = "data:text/html,tab-a-two"
        val tabBUrl = "data:text/html,tab-b"
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
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            assertTrue(
                "Tab A's first document should load; state=" + engineA.pageState.value,
                waitForPageState(engineA) { it.url == firstUrl && it.progress == 100 && it.navigationId == null },
            )
            engineA.navigate(NavigationRequest(tabAId, secondUrl, NavigationSource.USER))
            assertTrue(
                "Tab A should reach its second document and have a back entry; state=" + engineA.pageState.value,
                waitForPageState(engineA) { it.url == secondUrl && it.canGoBack && it.progress == 100 && it.navigationId == null },
            )

            // Repeat surface disposal/recreation several times before exercising Back. Each cycle
            // must save the native history, restore it into the new WebView, and leave the engine
            // bound to the currently attached surface.
            repeat(5) { cycle ->
                scenario.onActivity { selectTab.get()?.invoke("b") }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertTrue(
                    "Tab B should attach and load while A is detached (cycle ${cycle + 1})",
                    waitForPageState(engineB) {
                        it.url == tabBUrl && it.progress == 100 && it.navigationId == null
                    },
                )

                scenario.onActivity { selectTab.get()?.invoke("a") }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertTrue(
                    "Tab A should restore its second URL and back history (cycle ${cycle + 1}); " +
                        nativeWebViewHistoryDiagnostics(),
                    waitForPageState(engineA) { it.url == secondUrl && it.canGoBack },
                )
            }

            // Capture the native list immediately before issuing Back and again if the engine fails
            // to observe the expected callback. This distinguishes a lost command from a lost
            // history entry or a callback/state synchronization problem.
            val nativeHistoryBeforeBack = nativeWebViewHistoryDiagnostics()
            engineA.goBack()
            val returnedToFirstEntry = waitForPageState(engineA) {
                it.url == firstUrl && !it.canGoBack && it.progress == 100 && it.navigationId == null
            }
            val nativeHistoryAfterBack = nativeWebViewHistoryDiagnostics()
            assertTrue(
                "Back navigation after repeated A → B → A switches should reach A's first history entry; " +
                    "final engine state=${engineA.pageState.value}; " +
                    "native history before Back=$nativeHistoryBeforeBack; " +
                    "native history after Back=$nativeHistoryAfterBack",
                returnedToFirstEntry,
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

    /**
     * Reads the live WebView history from the currently composed surface on the UI thread.
     * Included in failure messages so an intermittent Back failure shows whether the native list
     * itself was restored and whether the goBack command changed its current index.
     */
    private fun nativeWebViewHistoryDiagnostics(): String {
        var diagnostics = "no WebView found in activity view tree"
        scenario.onActivity { activity ->
            fun findWebView(view: View): WebView? {
                if (view is WebView) return view
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) {
                        findWebView(view.getChildAt(index))?.let { return it }
                    }
                }
                return null
            }

            val webView = findWebView(activity.window.decorView)
            if (webView != null) {
                val history = webView.copyBackForwardList()
                val entries = (0 until history.size).joinToString(separator = " | ") { index ->
                    val item = history.getItemAtIndex(index)
                    "${if (index == history.currentIndex) "*" else ""}$index:${item.url}"
                }
                diagnostics = "url=${webView.url}, canGoBack=${webView.canGoBack()}, " +
                    "canGoForward=${webView.canGoForward()}, currentIndex=${history.currentIndex}, " +
                    "size=${history.size}, entries=[$entries]"
            }
        }
        return diagnostics
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


    @Test
    fun coordinatorNavigationReachesTheProductionSystemWebViewSurface() {
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = BrowserSessionCoordinator.create(
            engineFactory = SurfaceBoundBrowserEngineFactory(),
            scope = ownerScope,
        )
        var binding: BrowserSurfaceBinding? = null
        var surface: RealWebViewSurfacePort? = null

        try {
            runBlocking { coordinator.start() }
            val tab = coordinator.activeTab ?: error("Coordinator did not create its initial tab")
            val tabId = TabId(tab.id)
            val engine = coordinator.engines.value[tabId]
                ?: error("Coordinator did not register the initial tab engine")
            val activeBinding = engine as? BrowserSurfaceBinding
                ?: error("Production Android engine does not expose the renderer binding")
            val activeSurface = makeSurface(tabId) { view, url, title, loading, errorCode, errorMessage ->
                // Exercise the real WebViewClient/WebChromeClient callback path into the engine,
                // rather than manually calling observe() after navigation.
                activeBinding.observe(
                    url = url,
                    title = title,
                    isLoading = loading,
                    canGoBack = view.canGoBack(),
                    canGoForward = view.canGoForward(),
                    errorCode = errorCode,
                    errorMessage = errorMessage,
                )
            }
            binding = activeBinding
            surface = activeSurface
            activeBinding.attachSurface(activeSurface)

            val firstUrl =
                "data:text/html,%3Chtml%3E%3Chead%3E%3Ctitle%3EVeyra%20first%3C/title%3E%3C/head%3E" +
                    "%3Cbody%3Efirst%3C/body%3E%3C/html%3E"
            val decision = runBlocking {
                coordinator.navigate(
                    NavigationRequest(
                        tabId = tabId,
                        url = firstUrl,
                        source = NavigationSource.USER,
                    ),
                )
            }

            assertEquals(NavigationDecision.Allow, decision)
            assertTrue(
                "Coordinator -> controller -> engine must dispatch navigation to the real WebView port; " +
                    "observed=" + activeSurface.loadedUrls,
                activeSurface.loadedUrls.lastOrNull() == firstUrl,
            )
            assertTrue(
                "The first native document title must flow into the engine and session metadata",
                waitForPageState(engine) {
                    it.title == "Veyra first" &&
                        coordinator.activeTab?.title == "Veyra first" &&
                        !coordinator.activeTab!!.isLoading
                },
            )
            assertEquals(firstUrl, coordinator.activeTab?.url)

            // Navigate to a second deterministic document through the coordinator, then verify
            // native WebView history commands and native callbacks update the session projection.
            val secondUrl =
                "data:text/html,%3Chtml%3E%3Chead%3E%3Ctitle%3EVeyra%20second%3C/title%3E%3C/head%3E" +
                    "%3Cbody%3Esecond%3C/body%3E%3C/html%3E"
            assertEquals(
                NavigationDecision.Allow,
                runBlocking {
                    coordinator.navigate(
                        NavigationRequest(tabId, secondUrl, NavigationSource.USER),
                    )
                },
            )
            assertTrue(
                "Second native document must update coordinator metadata",
                waitForPageState(engine) {
                    it.title == "Veyra second" &&
                        coordinator.activeTab?.title == "Veyra second" &&
                        coordinator.activeTab?.canGoBack == true
                },
            )
            assertTrue("Coordinator should dispatch back to the native WebView", coordinator.goBack(tabId))
            assertTrue(
                "Native back navigation callback must restore the first page projection",
                waitForPageState(engine) {
                    it.title == "Veyra first" &&
                        coordinator.activeTab?.title == "Veyra first" &&
                        coordinator.activeTab?.canGoForward == true
                },
            )
            assertTrue("Coordinator should dispatch forward to the native WebView", coordinator.goForward(tabId))
            assertTrue(
                "Native forward navigation callback must restore the second page projection",
                waitForPageState(engine) {
                    it.title == "Veyra second" &&
                        coordinator.activeTab?.title == "Veyra second"
                },
            )

        } finally {
            surface?.let { nativeSurface ->
                binding?.detachSurface(nativeSurface)
                scenario.onActivity { nativeSurface.destroyWebView() }
            }
            runBlocking { coordinator.close() }
            ownerScope.cancel()
        }
    }


}
