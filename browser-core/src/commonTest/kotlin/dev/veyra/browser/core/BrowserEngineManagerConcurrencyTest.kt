package dev.veyra.browser.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserEngineManagerConcurrencyTest {
    @Test
    fun slowCreationForOneTabDoesNotBlockAnotherTab() = runBlocking {
        val firstCreationStarted = CompletableDeferred<Unit>()
        val releaseFirstCreation = CompletableDeferred<Unit>()
        val factory = object : BrowserEngineFactory {
            override suspend fun create(request: BrowserEngineCreateRequest): BrowserEngineContract {
                if (request.tabId == TabId("slow")) {
                    firstCreationStarted.complete(Unit)
                    releaseFirstCreation.await()
                }
                return FakeEngine(request.tabId, request.profileId)
            }
        }
        val manager = BrowserEngineManager(factory)

        val slow = async {
            manager.openTab(BrowserEngineCreateRequest(TabId("slow"), ProfileId("default")))
        }
        firstCreationStarted.await()

        val fast = async {
            manager.openTab(BrowserEngineCreateRequest(TabId("fast"), ProfileId("default")))
        }
        val fastEngine = fast.await()
        assertEquals(TabId("fast"), fastEngine.tabId)

        releaseFirstCreation.complete(Unit)
        slow.await()
        manager.closeAll()
    }

    @Test
    fun closeTabWaitsForCreationAndClosesTheCreatedEngine() = runBlocking {
        val creationStarted = CompletableDeferred<Unit>()
        val releaseCreation = CompletableDeferred<Unit>()
        lateinit var created: FakeEngine
        val manager = BrowserEngineManager(object : BrowserEngineFactory {
            override suspend fun create(request: BrowserEngineCreateRequest): BrowserEngineContract {
                creationStarted.complete(Unit)
                releaseCreation.await()
                return FakeEngine(request.tabId, request.profileId).also { created = it }
            }
        })

        val opening = async {
            manager.openTab(BrowserEngineCreateRequest(TabId("tab-1"), ProfileId("default")))
        }
        creationStarted.await()
        val closing = async { manager.closeTab(TabId("tab-1")) }
        yield()
        assertFalse(closing.isCompleted)

        releaseCreation.complete(Unit)
        opening.await()
        assertTrue(closing.await())
        assertEquals(1, created.closeCount)
        assertEquals(0, manager.size)
    }

    @Test
    fun closeAllWaitsForCreationAndDisposesAnEngineRejectedByShutdown() = runBlocking {
        val creationStarted = CompletableDeferred<Unit>()
        val releaseCreation = CompletableDeferred<Unit>()
        lateinit var created: FakeEngine
        val manager = BrowserEngineManager(object : BrowserEngineFactory {
            override suspend fun create(request: BrowserEngineCreateRequest): BrowserEngineContract {
                creationStarted.complete(Unit)
                releaseCreation.await()
                return FakeEngine(request.tabId, request.profileId).also { created = it }
            }
        })

        val opening = async {
            runCatching {
                manager.openTab(BrowserEngineCreateRequest(TabId("tab-1"), ProfileId("default")))
            }
        }
        creationStarted.await()
        val shutdown = async { manager.closeAll() }
        yield()
        assertFalse(shutdown.isCompleted)

        releaseCreation.complete(Unit)
        assertTrue(opening.await().isFailure)
        shutdown.await()
        assertEquals(1, created.closeCount)
        assertEquals(0, manager.size)
    }

    private class FakeEngine(
        override val tabId: TabId,
        override val profileId: ProfileId,
    ) : BrowserEngineContract {
        override val sessionId = SessionId("session-${tabId.value}")
        override val pageState: StateFlow<BrowserPageState> = MutableStateFlow(BrowserPageState(tabId))
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
        }
    }
}
