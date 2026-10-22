package dev.veyra.browser.core.engine

import dev.veyra.browser.core.BrowserEngineContract
import dev.veyra.browser.core.BrowserEngineCreateRequest
import dev.veyra.browser.core.BrowserEngineFactory
import dev.veyra.browser.core.ProfileId
import dev.veyra.browser.core.TabId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns BrowserEngineContract instances independently of Compose surface lifetimes.
 *
 * Factory creation and native disposal run outside the registry mutex. This prevents a slow native
 * startup or shutdown from blocking unrelated registry operations. Tab metadata and surfaces are
 * not owned here: detaching a surface must never close its engine/session.
 */
class BrowserEngineManager(
    private val factory: BrowserEngineFactory,
) {
    private val mutex = Mutex()
    private val engines = linkedMapOf<TabId, BrowserEngineContract>()
    private val _registeredEngines = MutableStateFlow<Map<TabId, BrowserEngineContract>>(emptyMap())
    val registeredEngines: StateFlow<Map<TabId, BrowserEngineContract>> = _registeredEngines.asStateFlow()

    private val creatingTabs = mutableMapOf<TabId, Creation>()
    private val closingTabs = mutableMapOf<TabId, CompletableDeferred<Result<Unit>>>()
    private var shuttingDown = false

    val size: Int get() = _registeredEngines.value.size

    suspend fun openTab(request: BrowserEngineCreateRequest): BrowserEngineContract {
        val claim = mutex.withLock {
            check(!shuttingDown) { "Browser engine manager is shutting down." }
            check(request.tabId !in closingTabs) {
                "Cannot open an engine for a tab while it is closing."
            }

            engines[request.tabId]?.let { existing ->
                require(existing.profileId == request.profileId) {
                    "Tab ${request.tabId.value} is already bound to a different profile."
                }
                return@withLock OpenClaim.Existing(existing)
            }

            creatingTabs[request.tabId]?.let { creation ->
                require(creation.profileId == request.profileId) {
                    "Tab ${request.tabId.value} is already being created for a different profile."
                }
                return@withLock OpenClaim.Wait(creation.completion)
            }

            val creation = Creation(request.profileId, CompletableDeferred())
            creatingTabs[request.tabId] = creation
            OpenClaim.Create(creation)
        }

        return when (claim) {
            is OpenClaim.Existing -> claim.engine
            is OpenClaim.Wait -> claim.completion.await().getOrThrow()
            is OpenClaim.Create -> createAndRegister(request, claim.creation)
        }
    }

    private suspend fun createAndRegister(
        request: BrowserEngineCreateRequest,
        creation: Creation,
    ): BrowserEngineContract {
        var createdEngine: BrowserEngineContract? = null
        try {
            val engine = factory.create(request)
            createdEngine = engine
            require(engine.tabId == request.tabId) {
                "Engine factory returned an engine for a different tab."
            }
            require(engine.profileId == request.profileId) {
                "Engine factory returned an engine for a different profile."
            }

            mutex.withLock {
                check(!shuttingDown) { "Browser engine manager shut down during engine creation." }
                check(creatingTabs[request.tabId] === creation) {
                    "Engine creation reservation was lost."
                }
                engines[request.tabId] = engine
                creatingTabs.remove(request.tabId)
                publishEngines()
                creation.completion.complete(Result.success(engine))
            }
            return engine
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                val engineToClose = createdEngine
                if (engineToClose != null) runCatching { engineToClose.close() }
                mutex.withLock {
                    if (creatingTabs[request.tabId] === creation) {
                        creatingTabs.remove(request.tabId)
                    }
                    creation.completion.complete(Result.failure(failure))
                }
            }
            throw failure
        }
    }

    fun get(tabId: TabId): BrowserEngineContract? = _registeredEngines.value[tabId]
    fun contains(tabId: TabId): Boolean = _registeredEngines.value.containsKey(tabId)

    /** Surface detach is deliberately separate from engine/session ownership. */
    fun detachSurface(tabId: TabId) = Unit

    suspend fun closeTab(tabId: TabId): Boolean {
        val claim = mutex.withLock {
            closingTabs[tabId]?.let { return@withLock CloseClaim.Wait(it) }
            creatingTabs[tabId]?.let { return@withLock CloseClaim.WaitForCreation(it.completion) }
            val engine = engines.remove(tabId) ?: return false
            val completion = CompletableDeferred<Result<Unit>>()
            closingTabs[tabId] = completion
            publishEngines()
            CloseClaim.Own(engine, completion)
        }

        return when (claim) {
            is CloseClaim.Wait -> {
                claim.completion.await().getOrThrow()
                false
            }
            is CloseClaim.WaitForCreation -> {
                claim.completion.await().getOrThrow()
                closeTab(tabId)
            }
            is CloseClaim.Own -> {
                closeAndComplete(claim.engine, claim.completion).getOrThrow()
                true
            }
        }
    }

    private suspend fun closeAndComplete(
        engine: BrowserEngineContract,
        completion: CompletableDeferred<Result<Unit>>,
    ): Result<Unit> = withContext(NonCancellable) {
        val result = runCatching { engine.close() }
        mutex.withLock {
            if (closingTabs[engine.tabId] === completion) closingTabs.remove(engine.tabId)
            completion.complete(result)
        }
        result
    }

    private fun publishEngines() {
        _registeredEngines.value = engines.toMap()
    }

    /**
     * Rejects new sessions, waits for in-flight creations, then closes every registered session and
     * waits for tab closes already in progress. One disposal failure never skips other sessions.
     */
    suspend fun closeAll() {
        val creations = mutex.withLock {
            shuttingDown = true
            creatingTabs.values.map { it.completion }
        }
        // A failed/cancelled creation is not a disposal failure; its opener receives that failure.
        creations.forEach { completion -> runCatching { completion.await() } }

        val claimsAndWaiters = mutex.withLock {
            val registered = engines.toList()
            engines.clear()
            val owned = registered.map { (tabId, engine) ->
                val completion = CompletableDeferred<Result<Unit>>()
                closingTabs[tabId] = completion
                CloseClaim.Own(engine, completion)
            }
            publishEngines()
            val alreadyClosing = closingTabs
                .filterKeys { tabId -> registered.none { it.first == tabId } }
                .values
                .toList()
            owned to alreadyClosing
        }

        val failures = mutableListOf<Throwable>()
        claimsAndWaiters.first.forEach { claim ->
            closeAndComplete(claim.engine, claim.completion)
                .exceptionOrNull()
                ?.let(failures::add)
        }
        claimsAndWaiters.second.forEach { completion ->
            completion.await().exceptionOrNull()?.let(failures::add)
        }

        if (failures.isNotEmpty()) {
            throw IllegalStateException(
                "Failed to close ${failures.size} browser engine(s).",
                failures.first(),
            )
        }
    }

    private data class Creation(
        val profileId: ProfileId,
        val completion: CompletableDeferred<Result<BrowserEngineContract>>,
    )

    private sealed interface OpenClaim {
        data class Existing(val engine: BrowserEngineContract) : OpenClaim
        data class Wait(val completion: CompletableDeferred<Result<BrowserEngineContract>>) : OpenClaim
        data class Create(val creation: Creation) : OpenClaim
    }

    private sealed interface CloseClaim {
        data class Own(
            val engine: BrowserEngineContract,
            val completion: CompletableDeferred<Result<Unit>>,
        ) : CloseClaim
        data class Wait(val completion: CompletableDeferred<Result<Unit>>) : CloseClaim
        data class WaitForCreation(
            val completion: CompletableDeferred<Result<BrowserEngineContract>>,
        ) : CloseClaim
    }
}
