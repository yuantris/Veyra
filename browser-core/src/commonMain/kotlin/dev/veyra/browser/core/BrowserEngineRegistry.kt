package dev.veyra.browser.core

/**
 * Owns legacy [BrowserEngine] instances by tab identity.
 *
 * The registry deliberately distinguishes detaching a rendered surface from closing a tab:
 * switching the active tab must not call [BrowserEngine.dispose]. A platform host may detach
 * its view while keeping the engine/session registered, then attach the same session again.
 *
 * This class is UI-thread confined. Platform adapters must marshal calls to their required
 * thread before interacting with native engine objects.
 */
class BrowserEngineRegistry {
    private val engines = linkedMapOf<TabId, BrowserEngine>()
    private val closingTabs = mutableSetOf<TabId>()

    val size: Int
        get() = engines.size

    fun get(tabId: TabId): BrowserEngine? = engines[tabId]

    fun contains(tabId: TabId): Boolean = engines.containsKey(tabId)

    /**
     * Registers the engine for [tabId].
     *
     * Re-registering the exact same instance is a no-op. Replacing an instance disposes the old
     * engine before publishing the new one. Registering while the tab is being closed is rejected.
     */
    fun register(tabId: TabId, engine: BrowserEngine) {
        check(tabId !in closingTabs) { "Cannot register an engine for a tab while it is closing." }

        val current = engines[tabId]
        if (current === engine) return

        if (current != null) {
            current.dispose()
        }
        engines[tabId] = engine
    }

    /**
     * Surface detachment is intentionally a no-op for engine ownership.
     *
     * A Compose host can call this from DisposableEffect when its native view is detached because
     * another tab became active. Only [closeTab] releases the engine.
     */
    fun detachSurface(tabId: TabId) {
        // Keep the native session registered; this is intentionally not a disposal boundary.
        @Suppress("UNUSED_VARIABLE")
        val retained = engines[tabId]
    }

    /**
     * Closes one tab's engine exactly once. Repeated calls and unknown tab IDs are safe.
     *
     * The entry is removed before dispose() so reentrant callbacks cannot retrieve a closing
     * engine from the registry.
     */
    fun closeTab(tabId: TabId): Boolean {
        if (tabId in closingTabs) return false
        val engine = engines.remove(tabId) ?: return false
        closingTabs += tabId
        return try {
            engine.dispose()
            true
        } finally {
            closingTabs -= tabId
        }
    }

    /** Disposes every registered engine, continuing if one adapter throws during disposal. */
    fun closeAll() {
        val tabIds = engines.keys.toList()
        var firstFailure: Throwable? = null

        tabIds.forEach { tabId ->
            try {
                closeTab(tabId)
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure
            }
        }

        firstFailure?.let { throw it }
    }
}
