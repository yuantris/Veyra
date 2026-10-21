package dev.veyra.browser.core

import kotlinx.coroutines.CoroutineScope
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
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BrowserControllerTest {
    @Test
    fun openTabResolvesProfileAndReusesEngine() = runBlocking {
        val factory = FakeFactory()
        val manager = BrowserEngineManager(factory)
        val profiles = InMemoryBrowserProfileProvider()
        val scope = CoroutineScope(SupervisorJob())
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
    fun forwardsEventsOnlyWhileEngineIsOwned() = runBlocking {
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
        assertTrue(controller.closeTab(TabId("tab-1")))
        assertFalse(controller.closeTab(TabId("tab-1")))
        assertNull(controller.registeredEngines.value[TabId("tab-1")])
        controller.close()
        scope.cancel()
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
        override val pageState: StateFlow<BrowserPageState> = MutableStateFlow(BrowserPageState(tabId))
        private val mutableEvents = MutableSharedFlow<BrowserEvent>()
        override val events: Flow<BrowserEvent> = mutableEvents
        override val capabilities = BrowserCapabilities()
        var navigateCount = 0
        override fun navigate(request: NavigationRequest) { navigateCount++ }
        override fun goBack() = Unit
        override fun goForward() = Unit
        override fun reload() = Unit
        override fun stop() = Unit
        override suspend fun close() = Unit
    }
}
