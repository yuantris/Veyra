package dev.veyra.browser.core.engine

import dev.veyra.browser.core.BrowserCapabilities
import dev.veyra.browser.core.BrowserEngineContract
import dev.veyra.browser.core.BrowserEngineCreateRequest
import dev.veyra.browser.core.BrowserEngineFactory
import dev.veyra.browser.core.BrowserEvent
import dev.veyra.browser.core.BrowserEventHub
import dev.veyra.browser.core.BrowserLoadPhase
import dev.veyra.browser.core.BrowserPageState
import dev.veyra.browser.core.DownloadController
import dev.veyra.browser.core.DownloadPhase
import dev.veyra.browser.core.DownloadRequest
import dev.veyra.browser.core.DownloadTask
import dev.veyra.browser.core.InMemoryBrowserProfileProvider
import dev.veyra.browser.core.NavigationDecision
import dev.veyra.browser.core.NavigationId
import dev.veyra.browser.core.NavigationPolicyHandler
import dev.veyra.browser.core.NavigationRequest
import dev.veyra.browser.core.NavigationSource
import dev.veyra.browser.core.ProfileId
import dev.veyra.browser.core.RequestId
import dev.veyra.browser.core.SessionId
import dev.veyra.browser.core.TabId
import dev.veyra.browser.core.navigation.BrowserNavigationCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BrowserControllerTest {
    @Test
    fun openTabResolvesProfileAndReusesEngine() = runBlocking {
        val factory = FakeFactory()
        val manager = BrowserEngineManager(factory)
        val profiles = InMemoryBrowserProfileProvider()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = BrowserController(
            manager,
            profiles,
            BrowserNavigationCoordinator(),
            BrowserEventHub(),
            scope,
        )

        val first = controller.openTab(TabId("tab-1"), initialUrl = "https://example.com")
        val second = controller.openTab(TabId("tab-1"), initialUrl = "https://ignored.example")

        assertSame(first, second)
        assertEquals(ProfileId("default"), first.profileId)
        assertEquals(1, factory.createCount)
        controller.close()
        scope.cancel()
    }

    @Test
    fun exposesLatestPageStateAndRemovesItWhenTabCloses() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = BrowserController(
            BrowserEngineManager(FakeFactory()),
            InMemoryBrowserProfileProvider(),
            BrowserNavigationCoordinator(),
            BrowserEventHub(),
            scope,
        )
        val tabId = TabId("tab-state")
        val engine = controller.openTab(tabId) as FakeEngine
        assertEquals(BrowserPageState(tabId), controller.pageStates.value[tabId])

        val loadedPage = BrowserPageState(
            tabId = tabId,
            url = "https://example.com",
            displayUrl = "https://example.com",
            title = "Example",
            loadPhase = BrowserLoadPhase.SUCCEEDED,
            progress = 100,
            canGoBack = true,
        )
        engine.updatePageState(loadedPage)
        assertEquals(loadedPage, controller.pageStates.value[tabId])

        assertTrue(controller.closeTab(tabId))
        assertFalse(controller.pageStates.value.containsKey(tabId))
        controller.close()
        scope.cancel()
    }

    @Test
    fun unknownProfileIsRejectedBeforeEngineCreation() = runBlocking {
        val factory = FakeFactory()
        val controller = BrowserController(
            BrowserEngineManager(factory),
            InMemoryBrowserProfileProvider(),
            BrowserNavigationCoordinator(),
            BrowserEventHub(),
            CoroutineScope(SupervisorJob()),
        )

        val failure = runCatching {
            controller.openTab(TabId("tab-1"), ProfileId("missing"))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, factory.createCount)
        controller.close()
    }

    @Test
    fun navigationExecutesOnlyWhenPolicyAllows() = runBlocking {
        val factory = FakeFactory()
        val controller = BrowserController(
            BrowserEngineManager(factory),
            InMemoryBrowserProfileProvider(),
            BrowserNavigationCoordinator(),
            BrowserEventHub(),
            CoroutineScope(SupervisorJob()),
        )
        val engine = controller.openTab(TabId("tab-1"))

        assertEquals(
            NavigationDecision.Allow,
            controller.navigate(NavigationRequest(TabId("tab-1"), "https://example.com")),
        )
        assertEquals(1, (engine as FakeEngine).navigateCount)
        assertEquals(
            NavigationDecision.OpenExternal("mailto:help@example.com"),
            controller.navigate(NavigationRequest(TabId("tab-1"), "mailto:help@example.com")),
        )
        assertEquals(1, engine.navigateCount)
        assertEquals(NavigationDecision.Cancel, controller.navigate(NavigationRequest(TabId("missing"), "https://example.com")))
        controller.close()
    }

    @Test
    fun navigationIsCancelledIfTabClosesDuringPolicyEvaluation() = runBlocking {
        val factory = FakeFactory()
        val manager = BrowserEngineManager(factory)
        val tabId = TabId("tab-closes-during-policy")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val policy = object : NavigationPolicyHandler {
            override fun decide(request: NavigationRequest): NavigationDecision {
                // Re-enter the lifecycle from policy evaluation to deterministically model a
                // tab-close race between the controller's initial lookup and command dispatch.
                runBlocking { manager.closeTab(request.tabId) }
                return NavigationDecision.Allow
            }
        }
        val controller = BrowserController(
            manager,
            InMemoryBrowserProfileProvider(),
            BrowserNavigationCoordinator(policy),
            BrowserEventHub(),
            scope,
        )
        val engine = controller.openTab(tabId) as FakeEngine

        assertEquals(
            NavigationDecision.Cancel,
            controller.navigate(NavigationRequest(tabId, "https://example.com")),
        )
        assertEquals(0, engine.navigateCount, "A closed engine must not receive the pending navigation")
        assertFalse(manager.contains(tabId))

        controller.close()
        scope.cancel()
    }

    @Test
    fun surfaceNavigationEvaluatesPolicyWithoutDispatchingAnotherLoad() = runBlocking {
        val factory = FakeFactory()
        val controller = BrowserController(
            BrowserEngineManager(factory),
            InMemoryBrowserProfileProvider(),
            BrowserNavigationCoordinator(),
            BrowserEventHub(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        val engine = controller.openTab(TabId("tab-1")) as FakeEngine

        assertEquals(
            NavigationDecision.Allow,
            controller.decideSurfaceNavigation(
                NavigationRequest(TabId("tab-1"), "https://example.com", NavigationSource.PAGE),
            ),
        )
        assertEquals(0, engine.navigateCount)
        assertEquals(
            NavigationDecision.OpenExternal("mailto:help@example.com"),
            controller.decideSurfaceNavigation(
                NavigationRequest(TabId("tab-1"), "mailto:help@example.com", NavigationSource.PAGE),
            ),
        )
        assertEquals(0, engine.navigateCount)
        // Subframe navigation is not a top-level tab navigation and must bypass this policy.
        assertEquals(
            NavigationDecision.Allow,
            controller.decideSurfaceNavigation(
                NavigationRequest(
                    tabId = TabId("tab-1"),
                    url = "mailto:resource@example.com",
                    source = NavigationSource.PAGE,
                    isMainFrame = false,
                ),
            ),
        )
        assertEquals(0, engine.navigateCount)
        assertEquals(
            NavigationDecision.Cancel,
            controller.decideSurfaceNavigation(
                NavigationRequest(TabId("closed"), "https://example.com", NavigationSource.PAGE),
            ),
        )
        assertEquals(0, engine.navigateCount)
        controller.close()
    }

    @Test
    fun downloadStartsRequireOwnedTabAndMatchingProfile() = runBlocking {
        val downloads = FakeDownloadController()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = BrowserController(
            BrowserEngineManager(FakeFactory()),
            InMemoryBrowserProfileProvider(),
            BrowserNavigationCoordinator(),
            BrowserEventHub(),
            scope,
            downloads,
        )
        val tabId = TabId("download-tab")
        controller.openTab(tabId)
        val request = DownloadRequest(
            requestId = RequestId("download-request"),
            tabId = tabId,
            profileId = ProfileId("default"),
            url = "https://example.com/archive.zip",
            suggestedFileName = "archive.zip",
        )

        assertEquals("download-1", controller.startDownload(request))
        assertEquals(listOf(request), downloads.started)
        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                controller.startDownload(request.copy(profileId = ProfileId("other")))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                controller.startDownload(request.copy(tabId = TabId("missing")))
            }
        }
        controller.cancelDownload("download-1")
        assertEquals(listOf("download-1"), downloads.cancelled)

        controller.close()
        scope.cancel()
    }

    @Test
    fun forwardsEngineEventsWhileOwnedAndStopsAfterClose() = runBlocking {
        val factory = FakeFactory()
        val hub = BrowserEventHub()
        val scope = CoroutineScope(SupervisorJob())
        val controller = BrowserController(
            BrowserEngineManager(factory),
            InMemoryBrowserProfileProvider(),
            BrowserNavigationCoordinator(),
            hub,
            scope,
        )
        val engine = controller.openTab(TabId("tab-1")) as FakeEngine
        val expected = BrowserEvent.TitleChanged(TabId("tab-1"), "Example")
        val received = async(start = CoroutineStart.UNDISPATCHED) { hub.events.first() }
        engine.emit(expected)
        assertEquals(expected, received.await())

        assertTrue(controller.closeTab(TabId("tab-1")))
        assertFalse(controller.closeTab(TabId("tab-1")))
        assertNull(controller.registeredEngines.value[TabId("tab-1")])
        controller.close()
        scope.cancel()
    }

    @Test
    fun lateEventsFromClosedEngineAreNotForwardedAfterSameTabReopens() = runBlocking {
        val createdEngines = mutableListOf<FakeEngine>()
        val factory = object : BrowserEngineFactory {
            override suspend fun create(request: BrowserEngineCreateRequest): BrowserEngineContract =
                FakeEngine(request.tabId, request.profileId).also(createdEngines::add)
        }
        val hub = BrowserEventHub()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = BrowserController(
            BrowserEngineManager(factory),
            InMemoryBrowserProfileProvider(),
            BrowserNavigationCoordinator(),
            hub,
            scope,
        )
        val tabId = TabId("reused-tab")
        val oldEngine = controller.openTab(tabId) as FakeEngine
        assertTrue(controller.closeTab(tabId))
        val replacementEngine = controller.openTab(tabId) as FakeEngine
        assertEquals(2, createdEngines.size)
        assertFalse(oldEngine === replacementEngine)

        val received = mutableListOf<BrowserEvent>()
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            hub.events.collect { received += it }
        }
        oldEngine.emit(BrowserEvent.TitleChanged(tabId, "stale title"))
        val currentEvent = BrowserEvent.TitleChanged(tabId, "current title")
        replacementEngine.emit(currentEvent)

        assertEquals(
            listOf<BrowserEvent>(currentEvent),
            received,
            "Events from a closed engine must not leak into a replacement session that reuses its TabId.",
        )
        collector.cancel()
        controller.close()
        scope.cancel()
    }

    @Test
    fun dropsLateCompletionFromPreviousNavigation() = runBlocking {
        val factory = FakeFactory()
        val hub = BrowserEventHub()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = BrowserController(
            BrowserEngineManager(factory),
            InMemoryBrowserProfileProvider(),
            BrowserNavigationCoordinator(),
            hub,
            scope,
        )
        val engine = controller.openTab(TabId("tab-1")) as FakeEngine
        val received = mutableListOf<BrowserEvent>()
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            hub.events.collect { received += it }
        }
        val oldId = NavigationId("old")
        val currentId = NavigationId("current")

        engine.emit(BrowserEvent.NavigationStarted(TabId("tab-1"), currentId, "https://current.example"))
        engine.emit(BrowserEvent.NavigationCompleted(TabId("tab-1"), oldId, "https://old.example"))
        engine.emit(BrowserEvent.NavigationCompleted(TabId("tab-1"), currentId, "https://current.example"))

        assertEquals(
            listOf(
                BrowserEvent.NavigationStarted(TabId("tab-1"), currentId, "https://current.example"),
                BrowserEvent.NavigationCompleted(TabId("tab-1"), currentId, "https://current.example"),
            ),
            received,
        )
        collector.cancel()
        controller.close()
        scope.cancel()
    }

    @Test
    fun newTabDecisionIsReturnedToTheCallerWithoutLoadingTheCurrentTab() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = BrowserController(
            BrowserEngineManager(FakeFactory()),
            InMemoryBrowserProfileProvider(),
            BrowserNavigationCoordinator(
                policyHandler = object : NavigationPolicyHandler {
                    override fun decide(request: NavigationRequest): NavigationDecision =
                        NavigationDecision.OpenInNewTab(request.url)
                },
            ),
            BrowserEventHub(),
            scope,
        )
        val engine = controller.openTab(TabId("tab-1")) as FakeEngine

        val decision = controller.navigate(NavigationRequest(TabId("tab-1"), "https://example.com"))

        // The decision is the caller's to route; the current tab must not load it.
        assertIs<NavigationDecision.OpenInNewTab>(decision)
        assertEquals("https://example.com", decision.url)
        assertEquals(0, engine.navigateCount)
        controller.close()
        scope.cancel()
    }

    private class FakeDownloadController : DownloadController {
        private val mutableTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
        override val tasks: StateFlow<List<DownloadTask>> = mutableTasks
        val started = mutableListOf<DownloadRequest>()
        val cancelled = mutableListOf<String>()

        override suspend fun start(request: DownloadRequest): String {
            started += request
            mutableTasks.value = mutableTasks.value + DownloadTask(
                id = "download-1",
                request = request,
                phase = DownloadPhase.QUEUED,
            )
            return "download-1"
        }

        override suspend fun cancel(downloadId: String) {
            cancelled += downloadId
        }
    }

    private class FakeFactory : BrowserEngineFactory {
        var createCount = 0
        override suspend fun create(request: BrowserEngineCreateRequest): BrowserEngineContract {
            createCount++
            return FakeEngine(request.tabId, request.profileId)
        }
    }

    private class FakeEngine(
        override val tabId: TabId,
        override val profileId: ProfileId,
    ) : BrowserEngineContract {
        override val sessionId = SessionId("session-${tabId.value}")
        private val mutablePageState = MutableStateFlow(BrowserPageState(tabId))
        override val pageState: StateFlow<BrowserPageState> = mutablePageState
        private val mutableEvents = MutableSharedFlow<BrowserEvent>()
        override val events: Flow<BrowserEvent> = mutableEvents
        override val capabilities = BrowserCapabilities(downloads = true)
        var navigateCount = 0
        fun updatePageState(state: BrowserPageState) { mutablePageState.value = state }
        suspend fun emit(event: BrowserEvent) { mutableEvents.emit(event) }
        override fun navigate(request: NavigationRequest) { navigateCount++ }
        override fun goBack() = Unit
        override fun goForward() = Unit
        override fun reload() = Unit
        override fun stop() = Unit
        override suspend fun close() = Unit
    }
}
