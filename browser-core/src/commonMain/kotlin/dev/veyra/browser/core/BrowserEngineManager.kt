package dev.veyra.browser.core

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
    private val closingTabs = mutableSetOf<TabId>()

    val size: Int
        get() = engines.size

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
                engine
            } catch (failure: Throwable) {
                // A factory can allocate native resources before returning a mismatched engine.
                runCatching { engine.close() }
                throw failure
            }
        }

    fun get(tabId: TabId): BrowserEngineContract? = engines[tabId]

    fun contains(tabId: TabId): Boolean = engines.containsKey(tabId)

    /**
     * Detaching or hiding a platform view does not release its engine/session.
     * The platform UI adapter is responsible for detaching the native surface itself.
     */
    fun detachSurface(tabId: TabId) {
        // Intentionally no-op: session ownership is not tied to surface attachment.
    }

    suspend fun closeTab(tabId: TabId): Boolean = mutex.withLock {
        if (tabId in closingTabs) return@withLock false
        val engine = engines.remove(tabId) ?: return@withLock false
        closingTabs += tabId
        try {
            engine.close()
            true
        } finally {
            closingTabs -= tabId
        }
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
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }
}
