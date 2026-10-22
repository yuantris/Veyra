package dev.veyra.browser.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns BrowserEngineContract instances independently of Compose surface lifetimes.
 * Switching tabs or detaching a surface must not close the engine/session.
 */
class BrowserEngineManager(
    private val factory: BrowserEngineFactory,
) {
    private val mutex = Mutex()
    private val engines = linkedMapOf<TabId, BrowserEngineContract>()
    private val _registeredEngines = MutableStateFlow<Map<TabId, BrowserEngineContract>>(emptyMap())
    val registeredEngines: StateFlow<Map<TabId, BrowserEngineContract>> = _registeredEngines.asStateFlow()

    private val closingTabs = mutableMapOf<TabId, CompletableDeferred<Result<Unit>>>()
    private var shuttingDown = false

    val size: Int get() = _registeredEngines.value.size

    suspend fun openTab(request: BrowserEngineCreateRequest): BrowserEngineContract =
        mutex.withLock {
            check(!shuttingDown) { "Browser engine manager is shutting down." }
            check(request.tabId !in closingTabs) {
                "Cannot open an engine for a tab while it is closing."
            }
            engines[request.tabId]?.let { existing ->
                require(existing.profileId == request.profileId) {
                    "Tab ${request.tabId.value} is already bound to a different profile."
                }
                return@withLock existing
            }

            val engine = factory.create(request)
            try {
                require(engine.tabId == request.tabId) {
                    "Engine factory returned an engine for a different tab."
                }
                require(engine.profileId == request.profileId) {
                    "Engine factory returned an engine for a different profile."
                }
                engines[request.tabId] = engine
                publishEngines()
                engine
            } catch (failure: Throwable) {
                runCatching { engine.close() }
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
            is CloseClaim.Own -> {
                val result = runCatching { claim.engine.close() }
                mutex.withLock {
                    if (closingTabs[tabId] === claim.completion) closingTabs.remove(tabId)
                    claim.completion.complete(result)
                }
                result.getOrThrow()
                true
            }
        }
    }

    private fun publishEngines() {
        _registeredEngines.value = engines.toMap()
    }

    /**
     * Stops accepting new tabs, closes all registered engines, and waits for closes that were
     * already underway. A single adapter failure does not prevent other engines from closing.
     */
    suspend fun closeAll() {
        val claimsAndWaiters = mutex.withLock {
            shuttingDown = true
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
            val result = runCatching { claim.engine.close() }
            mutex.withLock {
                val tabId = claim.engine.tabId
                if (closingTabs[tabId] === claim.completion) closingTabs.remove(tabId)
                claim.completion.complete(result)
            }
            result.exceptionOrNull()?.let(failures::add)
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

    private sealed interface CloseClaim {
        data class Own(
            val engine: BrowserEngineContract,
            val completion: CompletableDeferred<Result<Unit>>,
        ) : CloseClaim
        data class Wait(val completion: CompletableDeferred<Result<Unit>>) : CloseClaim
    }
}
