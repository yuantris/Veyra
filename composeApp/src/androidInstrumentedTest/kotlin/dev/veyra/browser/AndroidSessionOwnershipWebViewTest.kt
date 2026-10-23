package dev.veyra.browser

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking
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
    private val createdViews = mutableListOf<WebView>()

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(DownloadTestActivity::class.java)
    }

    @After
    fun tearDown() {
        createdViews.forEach { it.destroy() }
        createdViews.clear()
        scenario.close()
    }

    /** A real [WebView]-backed surface whose only extra behavior is recording requested loads. */
    private class RealWebViewSurfacePort(webView: WebView) : BrowserSurfacePort {
        val webView: WebView = webView
        val loadedUrls = mutableListOf<String>()
        private val mainHandler = Handler(Looper.getMainLooper())

        override fun loadUrl(url: String) {
            loadedUrls += url
            mainHandler.post { webView.loadUrl(url) }
        }

        override fun goBack() {
            mainHandler.post { if (webView.canGoBack()) webView.goBack() }
        }

        override fun goForward() {
            mainHandler.post { if (webView.canGoForward()) webView.goForward() }
        }

        override fun reload() {
            mainHandler.post { webView.reload() }
        }

        override fun stop() {
            mainHandler.post { webView.stopLoading() }
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
            createdViews += webView
            port = RealWebViewSurfacePort(webView)
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
        scenario.onActivity { first.webView.destroy() }

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
        scenario.onActivity { surface.webView.destroy() }
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
        scenario.onActivity { surface.webView.destroy() }

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
}
