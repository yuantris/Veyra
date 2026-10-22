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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserEngineManagerLifecycleTest {
    @Test
    fun closeAllWaitsForTabCloseAlreadyInProgress() = runBlocking {
        val closeStarted = CompletableDeferred<Unit>()
        val allowCloseToFinish = CompletableDeferred<Unit>()
        val engine = BlockingCloseEngine(TabId("tab-1"), ProfileId("default")) {
            closeStarted.complete(Unit)
            allowCloseToFinish.await()
        }
        val manager = BrowserEngineManager(object : BrowserEngineFactory {
            override suspend fun create(request: BrowserEngineCreateRequest): BrowserEngineContract = engine
        })
        manager.openTab(
            BrowserEngineCreateRequest(
                tabId = engine.tabId,
                profileId = engine.profileId,
                initialUrl = "about:blank",
            ),
        )

        val tabClose = async { manager.closeTab(engine.tabId) }
        closeStarted.await()
        val shutdown = async { manager.closeAll() }
        yield()
        assertFalse(shutdown.isCompleted)

        allowCloseToFinish.complete(Unit)
        assertTrue(tabClose.await())
        shutdown.await()
        assertEquals(1, engine.closeCount)
        assertEquals(0, manager.size)
    }

    @Test
    fun closeTabSurfacesEngineCloseFailureAndLeavesRegistryTerminal() = runBlocking {
        val engine = FailingCloseEngine(
            tabId = TabId("tab-1"),
            profileId = ProfileId("default"),
            closeError = IllegalStateException("native disposal failed"),
        )
        val manager = BrowserEngineManager(object : BrowserEngineFactory {
            override suspend fun create(request: BrowserEngineCreateRequest): BrowserEngineContract = engine
        })
        manager.openTab(BrowserEngineCreateRequest(engine.tabId, engine.profileId))

        // A disposal failure must reach the caller unchanged, never be swallowed into success.
        val thrown = assertFailsWith<IllegalStateException> { manager.closeTab(engine.tabId) }
        assertEquals("native disposal failed", thrown.message)
        assertEquals(1, engine.closeCount)

        // The registry entry was removed before disposal, so state stays clean and terminal.
        assertNull(manager.get(engine.tabId))
        assertEquals(0, manager.size)
        assertFalse(manager.closeTab(engine.tabId))
    }

    @Test
    fun closeAllClosesEveryEngineEvenWhenOneDisposalFails() = runBlocking {
        val failing = FailingCloseEngine(
            tabId = TabId("tab-1"),
            profileId = ProfileId("default"),
            closeError = IllegalStateException("first disposal failed"),
        )
        val healthy = FailingCloseEngine(TabId("tab-2"), ProfileId("default"), closeError = null)
        val manager = BrowserEngineManager(object : BrowserEngineFactory {
            override suspend fun create(request: BrowserEngineCreateRequest): BrowserEngineContract =
                if (request.tabId == TabId("tab-1")) failing else healthy
        })
        manager.openTab(BrowserEngineCreateRequest(TabId("tab-1"), ProfileId("default")))
        manager.openTab(BrowserEngineCreateRequest(TabId("tab-2"), ProfileId("default")))

        val thrown = assertFailsWith<IllegalStateException> { manager.closeAll() }

        // One disposal failure must not skip the others, and the count must be observable.
        assertTrue(thrown.message!!.contains("Failed to close 1 browser engine(s)"))
        assertEquals(1, failing.closeCount)
        assertEquals(1, healthy.closeCount)
        assertEquals(0, manager.size)
    }

    private class BlockingCloseEngine(
        override val tabId: TabId,
        override val profileId: ProfileId,
        private val onClose: suspend () -> Unit,
    ) : BrowserEngineContract {
        override val sessionId = SessionId("session-${tabId.value}")
        override val pageState: StateFlow<BrowserPageState> =
            MutableStateFlow(BrowserPageState(tabId))
        override val events: Flow<BrowserEvent> = MutableSharedFlow()
        override val capabilities = BrowserCapabilities()
        var closeCount = 0
            private set

        override fun navigate(request: NavigationRequest) = Unit
        override fun goBack() = Unit
        override fun goForward() = Unit
        override fun reload() = Unit
        override fun stop() = Unit

        override suspend fun close() {
            closeCount++
            onClose()
        }
    }

    private class FailingCloseEngine(
        override val tabId: TabId,
        override val profileId: ProfileId,
        private val closeError: Throwable?,
    ) : BrowserEngineContract {
        override val sessionId = SessionId("session-${tabId.value}")
        override val pageState: StateFlow<BrowserPageState> =
            MutableStateFlow(BrowserPageState(tabId))
        override val events: Flow<BrowserEvent> = MutableSharedFlow()
        override val capabilities = BrowserCapabilities()
        var closeCount = 0
            private set

        override fun navigate(request: NavigationRequest) = Unit
        override fun goBack() = Unit
        override fun goForward() = Unit
        override fun reload() = Unit
        override fun stop() = Unit

        override suspend fun close() {
            closeCount++
            closeError?.let { throw it }
        }
    }
}
