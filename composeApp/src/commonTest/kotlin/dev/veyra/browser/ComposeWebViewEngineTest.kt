package dev.veyra.browser

import dev.veyra.browser.core.BrowserLoadPhase
import dev.veyra.browser.core.BrowserEngineCreateRequest
import dev.veyra.browser.core.BrowserErrorCategory
import dev.veyra.browser.core.BrowserEvent
import dev.veyra.browser.core.DownloadRequest
import dev.veyra.browser.core.RequestId
import dev.veyra.browser.core.NavigationRequest
import dev.veyra.browser.core.NavigationSource
import dev.veyra.browser.core.ProfileId
import dev.veyra.browser.core.TabId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class ComposeWebViewEngineTest {
    @Test
    fun navigationBeforeSurfaceAttachmentIsDeliveredWhenSurfaceAttaches() {
        val engine = ComposeWebViewEngine(
            BrowserEngineCreateRequest(
                tabId = TabId("tab-1"),
                profileId = ProfileId("default"),
            ),
        )
        val surface = RecordingSurface()

        engine.navigate(NavigationRequest(TabId("tab-1"), "https://example.com"))

        assertTrue(surface.loadedUrls.isEmpty())
        assertEquals("https://example.com", engine.pageState.value.url)
        assertEquals(BrowserLoadPhase.STARTING, engine.pageState.value.loadPhase)

        engine.attachSurface(surface)

        assertEquals(listOf("https://example.com"), surface.loadedUrls)
    }

    @Test
    fun detachingSurfaceKeepsEngineUsableAndReattachesPendingNavigation() {
        val engine = ComposeWebViewEngine(
            BrowserEngineCreateRequest(TabId("tab-1"), ProfileId("default")),
        )
        val firstSurface = RecordingSurface()
        engine.attachSurface(firstSurface)
        engine.detachSurface(firstSurface)

        engine.navigate(NavigationRequest(TabId("tab-1"), "https://after-detach.example"))
        assertEquals(emptyList(), firstSurface.loadedUrls)

        val secondSurface = RecordingSurface()
        engine.attachSurface(secondSurface)

        assertEquals(listOf("https://after-detach.example"), secondSurface.loadedUrls)
    }


    @Test
    fun detachAndReattachWithoutPendingNavigationDoesNotIssueDuplicateLoad() {
        val tabId = TabId("tab-switch")
        val engine = ComposeWebViewEngine(
            BrowserEngineCreateRequest(tabId, ProfileId("default")),
        )
        val firstSurface = RecordingSurface()
        engine.attachSurface(firstSurface)
        engine.navigate(NavigationRequest(tabId, "https://page-a.example"))
        assertEquals(listOf("https://page-a.example"), firstSurface.loadedUrls)

        // Capture health as represented by this adapter: null means the renderer has not
        // reported an explicit health state. Reattachment must preserve that state, not invent one.
        val rendererHealthBeforeDetach = engine.pageState.value.rendererHealthy

        // A tab switch disposes only the native surface; it must not close or re-navigate
        // the logical engine when the same tab becomes selected again.
        engine.detachSurface(firstSurface)
        val restoredSurface = RecordingSurface()
        engine.attachSurface(restoredSurface)

        assertEquals(emptyList(), restoredSurface.loadedUrls)
        assertEquals("https://page-a.example", engine.pageState.value.url)
        assertEquals(rendererHealthBeforeDetach, engine.pageState.value.rendererHealthy)
    }

    @Test
    fun navigationEventsAreNormalizedAndCompletedBySurfaceObservation() = runBlocking {
        val engine = ComposeWebViewEngine(
            BrowserEngineCreateRequest(TabId("tab-1"), ProfileId("default")),
        )
        val event = async(start = CoroutineStart.UNDISPATCHED) {
            engine.events.first { it is BrowserEvent.NavigationStarted }
        }

        engine.navigate(NavigationRequest(TabId("tab-1"), "https://example.com"))
        val started = assertIs<BrowserEvent.NavigationStarted>(event.await())
        assertEquals("https://example.com", started.url)

        val committed = async(start = CoroutineStart.UNDISPATCHED) {
            engine.events.first { it is BrowserEvent.NavigationCommitted }
        }
        val completed = async(start = CoroutineStart.UNDISPATCHED) {
            engine.events.first { it is BrowserEvent.NavigationCompleted }
        }
        engine.observe(
            url = "https://example.com",
            title = "Example",
            isLoading = false,
            canGoBack = true,
            canGoForward = false,
        )

        assertEquals(started.navigationId, assertIs<BrowserEvent.NavigationCommitted>(committed.await()).navigationId)
        assertEquals(started.navigationId, assertIs<BrowserEvent.NavigationCompleted>(completed.await()).navigationId)
        assertEquals(BrowserLoadPhase.SUCCEEDED, engine.pageState.value.loadPhase)
        assertEquals("Example", engine.pageState.value.title)
        assertTrue(engine.pageState.value.canGoBack)
    }

    @Test
    fun downloadRequestsAreEmittedWithoutStartingTransfers() = runBlocking {
        val tabId = TabId("tab-download")
        val profileId = ProfileId("default")
        val engine = ComposeWebViewEngine(
            BrowserEngineCreateRequest(tabId, profileId),
        )
        val request = DownloadRequest(
            requestId = RequestId("download-1"),
            tabId = tabId,
            profileId = profileId,
            url = "https://example.com/file.zip",
            suggestedFileName = "file.zip",
            mimeType = "application/zip",
            expectedBytes = 1024,
        )
        val emitted = async(start = CoroutineStart.UNDISPATCHED) {
            engine.events.first { it is BrowserEvent.DownloadRequested }
        }

        engine.reportDownloadRequested(request)

        val event = assertIs<BrowserEvent.DownloadRequested>(emitted.await())
        assertEquals(tabId, event.tabId)
        assertEquals(request, event.request)
        assertEquals("about:blank", engine.pageState.value.url)
    }

    @Test
    fun closeIsIdempotentAndPreventsFurtherNavigation() = runBlocking {
        val engine = ComposeWebViewEngine(
            BrowserEngineCreateRequest(TabId("tab-1"), ProfileId("default")),
        )
        val surface = RecordingSurface()
        engine.attachSurface(surface)

        engine.close()
        engine.close()
        engine.navigate(NavigationRequest(TabId("tab-1"), "https://closed.example"))

        assertTrue(runCatching { engine.attachSurface(RecordingSurface()) }.isFailure)
        assertTrue(surface.loadedUrls.isEmpty())
        assertFalse(engine.pageState.value.url == "https://closed.example")
    }

    @Test
    fun rendererCrashIsObservableAndIsNotTreatedAsTabClose() = runBlocking {
        val tabId = TabId("tab-crash")
        val engine = ComposeWebViewEngine(
            BrowserEngineCreateRequest(tabId, ProfileId("default")),
        )
        val surface = RecordingSurface()
        engine.attachSurface(surface)
        engine.navigate(NavigationRequest(tabId, "https://example.com", NavigationSource.USER))

        val crashed = async(start = CoroutineStart.UNDISPATCHED) {
            engine.events.first { it is BrowserEvent.RendererCrashed }
        }
        engine.reportRendererCrashed(recoverable = true)
        val event = assertIs<BrowserEvent.RendererCrashed>(crashed.await())
        assertEquals(true, event.recoverable)

        val state = engine.pageState.value
        assertEquals(false, state.rendererHealthy)
        assertEquals(BrowserLoadPhase.FAILED, state.loadPhase)
        assertEquals("content_process_crashed", state.error?.code)
        assertEquals(BrowserErrorCategory.CONTENT_PROCESS_CRASHED, state.error?.category)

        // A crashed renderer is not a closed session: the engine keeps accepting commands, and
        // the Android flow (dead WebView detached, replacement attached) recovers the pending
        // navigation on the new surface.
        engine.detachSurface(surface)
        engine.navigate(NavigationRequest(tabId, "https://example.com/after", NavigationSource.USER))
        val recoverySurface = RecordingSurface()
        engine.attachSurface(recoverySurface)
        assertEquals(listOf("https://example.com/after"), recoverySurface.loadedUrls)
    }

    private class RecordingSurface : BrowserSurfacePort {
        val loadedUrls = mutableListOf<String>()
        var backCount = 0
        var forwardCount = 0
        var reloadCount = 0
        var stopCount = 0

        override fun loadUrl(url: String) {
            loadedUrls += url
        }

        override fun goBack() {
            backCount++
        }

        override fun goForward() {
            forwardCount++
        }

        override fun reload() {
            reloadCount++
        }

        override fun stop() {
            stopCount++
        }
    }
}
