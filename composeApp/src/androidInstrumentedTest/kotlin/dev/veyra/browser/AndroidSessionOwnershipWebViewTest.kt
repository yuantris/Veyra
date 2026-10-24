package dev.veyra.browser

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
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
        lateinit var original: WebView
        var pageFinished = CountDownLatch(1)
        scenario.onActivity { activity ->
            original = WebView(activity)
            original.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    pageFinished.countDown()
                }
            }
            activity.setContentView(original)
            original.loadDataWithBaseURL(
                "https://restore.example/one",
                "<html><body>one</body></html>",
                "text/html",
                "UTF-8",
                null,
            )
        }
        assertTrue("First local document should finish loading", pageFinished.await(5, TimeUnit.SECONDS))

        pageFinished = CountDownLatch(1)
        scenario.onActivity {
            original.loadDataWithBaseURL(
                "https://restore.example/two",
                "<html><body>two</body></html>",
                "text/html",
                "UTF-8",
                null,
            )
        }
        assertTrue("Second local document should finish loading", pageFinished.await(5, TimeUnit.SECONDS))

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
        var backNavigationFinished = CountDownLatch(1)
        var restoredCurrentIndex = -1
        scenario.onActivity { activity ->
            restored = WebView(activity)
            restored.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    backNavigationFinished.countDown()
                }
            }
            activity.setContentView(restored)
            restoreResult = restored.restoreState(snapshot)
                ?: throw AssertionError("restoreState should restore the saved navigation list")
            restoredCurrentIndex = restored.copyBackForwardList().currentIndex
        }
        try {
            assertTrue("Restored history should contain at least two entries", restoreResult.size >= 2)
            scenario.onActivity {
                assertTrue("Restored WebView should be able to navigate back", restored.canGoBack())
                backNavigationFinished = CountDownLatch(1)
                restored.goBack()
            }
            assertTrue(
                "Back navigation after restore should finish",
                backNavigationFinished.await(5, TimeUnit.SECONDS),
            )
            scenario.onActivity {
                assertTrue(
                    "Back navigation should move to an earlier history entry",
                    restored.copyBackForwardList().currentIndex < restoredCurrentIndex,
                )
            }
        } finally {
            scenario.onActivity {
                restored.stopLoading()
                restored.destroy()
            }
        }
    }

}
