package dev.veyra.browser.core.engine

import dev.veyra.browser.core.BrowserEngineContract
import dev.veyra.browser.core.BrowserEngineCreateRequest
import dev.veyra.browser.core.BrowserEngineFactory
import dev.veyra.browser.core.BrowserEvent
import dev.veyra.browser.core.BrowserCapabilities
import dev.veyra.browser.core.BrowserPageState
import dev.veyra.browser.core.NavigationRequest
import dev.veyra.browser.core.ProfileId
import dev.veyra.browser.core.SessionId
import dev.veyra.browser.core.TabId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BrowserEngineManagerTest {
    @Test
    fun reusesEngineForSameTabAndProfile() = runBlocking {
        val factory = FakeFactory()
        val manager = BrowserEngineManager(factory)
        val request = BrowserEngineCreateRequest(
            tabId = TabId("tab-1"),
            profileId = ProfileId("default"),
            initialUrl = "https://example.com",
        )

        val first = manager.openTab(request)
        val second = manager.openTab(request)

        assertSame(first, second)
        assertEquals(1, factory.createCount)
        assertEquals(1, manager.size)
    }

    @Test
    fun rejectsProfileMismatchForExistingTab() = runBlocking {
        val manager = BrowserEngineManager(FakeFactory())
        manager.openTab(BrowserEngineCreateRequest(TabId("tab-1"), ProfileId("default")))

        val failure = runCatching {
            manager.openTab(BrowserEngineCreateRequest(TabId("tab-1"), ProfileId("private")))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(1, manager.size)
    }

    @Test
    fun detachDoesNotCloseSessionButCloseTabDoes() = runBlocking {
        val manager = BrowserEngineManager(FakeFactory())
        val tabId = TabId("tab-1")
        val engine = manager.openTab(BrowserEngineCreateRequest(tabId, ProfileId("default")))

        manager.detachSurface(tabId)

        assertSame(engine, manager.get(tabId))
        assertEquals(0, (engine as FakeEngine).closeCount)
        assertTrue(manager.closeTab(tabId))
        assertFalse(manager.closeTab(tabId))
        assertNull(manager.get(tabId))
        assertEquals(1, engine.closeCount)
    }

    @Test
    fun closeAllClosesEveryRegisteredEngine() = runBlocking {
        val manager = BrowserEngineManager(FakeFactory())
        val first = manager.openTab(BrowserEngineCreateRequest(TabId("tab-1"), ProfileId("default")))
        val second = manager.openTab(BrowserEngineCreateRequest(TabId("tab-2"), ProfileId("default")))

        manager.closeAll()

        assertEquals(0, manager.size)
        assertEquals(1, (first as FakeEngine).closeCount)
        assertEquals(1, (second as FakeEngine).closeCount)
    }

    @Test
    fun shutdownRejectsLateTabCreation() = runBlocking {
        val factory = FakeFactory()
        val manager = BrowserEngineManager(factory)
        manager.openTab(BrowserEngineCreateRequest(TabId("tab-1"), ProfileId("default")))
        manager.closeAll()

        val failure = runCatching {
            manager.openTab(BrowserEngineCreateRequest(TabId("tab-2"), ProfileId("default")))
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, factory.createCount)
        assertEquals(0, manager.size)
    }

    private class FakeFactory : BrowserEngineFactory {
        var createCount = 0

        override suspend fun create(request: BrowserEngineCreateRequest): BrowserEngineContract {
            createCount += 1
            return FakeEngine(request.tabId, request.profileId)
        }
    }

    private class FakeEngine(
        override val tabId: TabId,
        override val profileId: ProfileId,
    ) : BrowserEngineContract {
        override val sessionId = SessionId("session-${tabId.value}")
        override val pageState: StateFlow<BrowserPageState> =
            MutableStateFlow(BrowserPageState(tabId = tabId))
        override val events: Flow<BrowserEvent> = emptyFlow()
        override val capabilities = BrowserCapabilities()
        var closeCount = 0
            private set

        override fun navigate(request: NavigationRequest) = Unit
        override fun goBack() = Unit
        override fun goForward() = Unit
        override fun reload() = Unit
        override fun stop() = Unit
        override suspend fun close() {
            closeCount += 1
        }
    }
}
