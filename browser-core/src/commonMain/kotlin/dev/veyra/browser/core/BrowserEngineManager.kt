package dev.veyra.browser.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns modern [BrowserEngineContract] instances independently of Compose surface lifetimes.
 *
 * All operations that mutate ownership are serialized. Switching the visible tab must not call
 * [closeTab]; only an explicit tab-close operation releases its engine/session.
 */
class BrowserEngineManager(
    private val factory: BrowserEngineFactory,
) {
    private val mutex = Mutex()
    private val engines = linkedMapOf<TabId, BrowserEngineContract>()
    private val _registeredEngines = MutableStateFlow<Map<TabId, BrowserEngineContract>>(emptyMap())
    /** Immutable snapshots of currently owned engines, safe for UI observation. */
    val registeredEngines: StateFlow<Map<TabId, BrowserEngineContract>> = _registeredEngines.asStateFlow()

    private val closingTabs = mutableSetOf<TabId>()

    val size: Int
        get() = _registeredEngines.value.size

    suspend fun openTab(request: BrowserEngineCreateRequest): BrowserEngineContract =
        mutex.withLock {
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
                // A factory can allocate native resources before returning a mismatched engine.
                runCatching { engine.close() }
                throw failure
            }
        }

    fun get(tabId: TabId): BrowserEngineContract? = _registeredEngines.value[tabId]

    fun contains(tabId: TabId): Boolean = _registeredEngines.value.containsKey(tabId)

    /**
     * Detaching or hiding a platform view does not release its engine/session.
     * The platform UI adapter is responsible for detaching the native surface itself.
     */
    fun detachSurface(tabId: TabId) {
        // Intentionally no-op: session ownership is not tied to surface attachment.
    }

    suspend fun closeTab(tabId: TabId): Boolean {
        val engine = mutex.withLock {
            if (tabId in closingTabs) return false
            val owned = engines.remove(tabId) ?: return false
            closingTabs += tabId
            publishEngines()
            owned
        }

        return try {
            engine.close()
            true
        } finally {
            mutex.withLock { closingTabs -= tabId }
        }
    }

    private fun publishEngines() {
        _registeredEngines.value = engines.toMap()
    }

    /** Closes every tab even if one platform adapter fails to release its resources. */
    suspend fun closeAll() {
        val failures = mutableListOf<Throwable>()
        val tabIds = mutex.withLock { engines.keys.toList() }
        tabIds.forEach { tabId ->
            try {
                closeTab(tabId)
            } catch (failure: Throwable) {
                failures += failure
            }
        }
        if (failures.isNotEmpty()) {
            throw IllegalStateException(
                "Failed to close ${failures.size} browser engine(s).",
                failures.first(),
            )
        }
    }
}
