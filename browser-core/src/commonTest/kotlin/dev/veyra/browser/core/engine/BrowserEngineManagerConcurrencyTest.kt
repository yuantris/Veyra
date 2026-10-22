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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
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

    @Test
    fun concurrentOpenOfSameTabSharesOneCreation() = runBlocking {
        var createCount = 0
        val creationStarted = CompletableDeferred<Unit>()
        val releaseCreation = CompletableDeferred<Unit>()
        val factory = object : BrowserEngineFactory {
            override suspend fun create(request: BrowserEngineCreateRequest): BrowserEngineContract {
                createCount++
                creationStarted.complete(Unit)
                releaseCreation.await()
                return FakeEngine(request.tabId, request.profileId)
            }
        }
        val manager = BrowserEngineManager(factory)

        val first = async {
            manager.openTab(BrowserEngineCreateRequest(TabId("tab-1"), ProfileId("default")))
        }
        creationStarted.await()

        // Arrives while the first creation is still in flight: the second caller must join the
        // in-flight creation instead of starting a second one, whichever order the claims run in.
        val second = async {
            manager.openTab(BrowserEngineCreateRequest(TabId("tab-1"), ProfileId("default")))
        }
        yield()

        releaseCreation.complete(Unit)
        val firstEngine = first.await()
        val secondEngine = second.await()

        assertSame(firstEngine, secondEngine)
        assertEquals(1, createCount)
        assertEquals(1, manager.size)
        manager.closeAll()
    }

    @Test
    fun concurrentCloseOfSameTabClosesTheEngineOnce() = runBlocking {
        val closeStarted = CompletableDeferred<Unit>()
        val releaseClose = CompletableDeferred<Unit>()
        lateinit var engine: FakeEngine
        val manager = BrowserEngineManager(object : BrowserEngineFactory {
            override suspend fun create(request: BrowserEngineCreateRequest): BrowserEngineContract =
                FakeEngine(request.tabId, request.profileId).also { engine = it }
        })
        manager.openTab(BrowserEngineCreateRequest(TabId("tab-1"), ProfileId("default")))
        engine.onClose = {
            closeStarted.complete(Unit)
            releaseClose.await()
        }

        val firstClose = async { manager.closeTab(TabId("tab-1")) }
        closeStarted.await()
        val secondClose = async { manager.closeTab(TabId("tab-1")) }
        yield()
        // The second caller waits for the in-flight close instead of disposing a second time.
        assertFalse(secondClose.isCompleted)

        releaseClose.complete(Unit)
        assertTrue(firstClose.await())
        assertFalse(secondClose.await())
        assertEquals(1, engine.closeCount)
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

        /** Optional gate so a test can hold close() open and observe concurrent callers. */
        var onClose: (suspend () -> Unit)? = null

        override fun navigate(request: NavigationRequest) = Unit
        override fun goBack() = Unit
        override fun goForward() = Unit
        override fun reload() = Unit
        override fun stop() = Unit
        override suspend fun close() {
            closeCount++
            onClose?.invoke()
        }
    }
}
