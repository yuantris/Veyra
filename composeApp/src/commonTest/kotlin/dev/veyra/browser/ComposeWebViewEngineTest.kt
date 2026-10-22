package dev.veyra.browser

import dev.veyra.browser.core.BrowserLoadPhase
import dev.veyra.browser.core.BrowserEngineCreateRequest
import dev.veyra.browser.core.BrowserEvent
import dev.veyra.browser.core.NavigationRequest
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
