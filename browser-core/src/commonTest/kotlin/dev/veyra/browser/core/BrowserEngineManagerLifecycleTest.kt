package dev.veyra.browser.core

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
import kotlin.test.assertFalse
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
}
