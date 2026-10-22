package dev.veyra.browser.core.session

import dev.veyra.browser.core.BrowserCapabilities
import dev.veyra.browser.core.BrowserEngineContract
import dev.veyra.browser.core.BrowserEngineCreateRequest
import dev.veyra.browser.core.BrowserEngineFactory
import dev.veyra.browser.core.BrowserEvent
import dev.veyra.browser.core.BrowserEventHub
import dev.veyra.browser.core.BrowserLoadPhase
import dev.veyra.browser.core.BrowserPageState
import dev.veyra.browser.core.InMemoryBrowserProfileProvider
import dev.veyra.browser.core.NavigationRequest
import dev.veyra.browser.core.ProfileId
import dev.veyra.browser.core.SessionId
import dev.veyra.browser.core.TabId
import dev.veyra.browser.core.engine.BrowserController
import dev.veyra.browser.core.engine.BrowserEngineManager
import dev.veyra.browser.core.navigation.BrowserNavigationCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BrowserSessionCoordinatorTest {
    @Test
    fun coordinatorOwnsOpenSelectCloseAndLastTabReplacement() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val factory = CoordinatorTestFactory()
        val store = BrowserSessionStore("about:blank")
        val manager = BrowserEngineManager(factory)
        val controller = BrowserController(
            manager,
            InMemoryBrowserProfileProvider(),
            BrowserNavigationCoordinator(),
            BrowserEventHub(),
            scope,
        )
        val coordinator = BrowserSessionCoordinator(
            store = store,
            controller = controller,
            profileProvider = InMemoryBrowserProfileProvider(),
            scope = scope,
        )

        coordinator.start()
        val first = store.activeTab ?: error("Initial tab missing")
        assertTrue(manager.contains(TabId(first.id)))

        val second = coordinator.openTab("https://second.example")
        assertEquals(2, manager.size)
        assertTrue(coordinator.selectTab(first.id))
        assertTrue(manager.contains(TabId(first.id)))
        assertTrue(manager.contains(TabId(second.id)))

        assertTrue(coordinator.closeTab(second.id))
        assertFalse(manager.contains(TabId(second.id)))
        assertTrue(manager.contains(TabId(first.id)))

        assertTrue(coordinator.closeTab(first.id))
        val replacement = store.activeTab ?: error("Replacement tab missing")
        assertNotEquals(first.id, replacement.id)
        assertTrue(manager.contains(TabId(replacement.id)))
        assertEquals(1, manager.size)

        coordinator.close()
        scope.cancel()
    }

    @Test
    fun failedNewEngineCreationRollsBackOnlyNewTabMetadata() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val factory = CoordinatorTestFactory()
        val store = BrowserSessionStore("about:blank")
        val profiles = InMemoryBrowserProfileProvider()
        val controller = BrowserController(
            BrowserEngineManager(factory),
            profiles,
            BrowserNavigationCoordinator(),
            BrowserEventHub(),
            scope,
        )
        val coordinator = BrowserSessionCoordinator(store, controller, profiles, scope)
        coordinator.start()
        val originalId = store.activeTab?.id ?: error("Initial tab missing")

        factory.failNextCreate = true
        val failure = runCatching { coordinator.openTab("https://failed.example") }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(listOf(originalId), store.state.value.tabs.map { it.id })
        assertTrue(controller.registeredEngines.value.containsKey(TabId(originalId)))

        coordinator.close()
        scope.cancel()
    }

    @Test
    fun privateTabUsesEphemeralPrivateProfileInsteadOfDefaultProfile() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val factory = CoordinatorTestFactory()
        val store = BrowserSessionStore("about:blank")
        val profiles = InMemoryBrowserProfileProvider()
        val controller = BrowserController(
            BrowserEngineManager(factory),
            profiles,
            BrowserNavigationCoordinator(),
            BrowserEventHub(),
            scope,
        )
        val coordinator = BrowserSessionCoordinator(store, controller, profiles, scope)
        coordinator.start()

        val privateTab = coordinator.openTab(isPrivate = true)
        val engine = controller.registeredEngines.value[TabId(privateTab.id)]
            ?: error("Private tab engine missing")
        assertEquals(ProfileId("private"), engine.profileId)
        assertTrue(factory.requests.last().isPrivate)

        coordinator.close()
        scope.cancel()
    }
}

private class CoordinatorTestFactory : BrowserEngineFactory {
    val requests = mutableListOf<BrowserEngineCreateRequest>()
    var failNextCreate = false

    override suspend fun create(request: BrowserEngineCreateRequest): BrowserEngineContract {
        requests += request
        if (failNextCreate) {
            failNextCreate = false
            throw IllegalStateException("Synthetic engine creation failure")
        }
        return CoordinatorTestEngine(request)
    }
}

private class CoordinatorTestEngine(
    request: BrowserEngineCreateRequest,
) : BrowserEngineContract {
    override val tabId = request.tabId
    override val sessionId = SessionId("session-${request.tabId.value}")
    override val profileId = request.profileId
    private val mutablePageState = MutableStateFlow(BrowserPageState(tabId = tabId, url = request.initialUrl))
    override val pageState: StateFlow<BrowserPageState> = mutablePageState
    private val mutableEvents = MutableSharedFlow<BrowserEvent>(extraBufferCapacity = 16)
    override val events: Flow<BrowserEvent> = mutableEvents
    override val capabilities = BrowserCapabilities()
    var closed = false
        private set

    override fun navigate(request: NavigationRequest) {
        mutablePageState.value = mutablePageState.value.copy(
            url = request.url,
            displayUrl = request.url,
            loadPhase = BrowserLoadPhase.LOADING,
        )
    }

    override fun goBack() = Unit
    override fun goForward() = Unit
    override fun reload() = Unit
    override fun stop() {
        mutablePageState.value = mutablePageState.value.copy(loadPhase = BrowserLoadPhase.STOPPED)
    }

    override suspend fun close() {
        closed = true
    }
}
