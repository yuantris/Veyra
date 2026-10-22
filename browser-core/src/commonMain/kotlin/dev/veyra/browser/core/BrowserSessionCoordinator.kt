package dev.veyra.browser.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single platform-neutral owner of tab metadata ↔ engine-session coordination.
 *
 * UI code sends commands to this class and observes its projections. It must not reconcile
 * BrowserSessionStore against BrowserEngineManager itself. The store remains metadata-only;
 * BrowserController/BrowserEngineManager remain responsible for engine/session lifecycle.
 *
 * The supplied scope must outlive this coordinator and be cancelled by its application owner.
 */
class BrowserSessionCoordinator(
    private val store: BrowserSessionStore,
    private val controller: BrowserController,
    private val profileProvider: BrowserProfileProvider,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var started = false
    private var closed = false
    private var pageStateJob: Job? = null
    private var eventJob: Job? = null

    val state: StateFlow<BrowserSessionState> get() = store.state
    val activeTab: BrowserTab? get() = store.activeTab
    val engines: StateFlow<Map<TabId, BrowserEngineContract>> get() = controller.registeredEngines
    val pageStates: StateFlow<Map<TabId, BrowserPageState>> get() = controller.pageStates
    val events: Flow<BrowserEvent> get() = controller.events

    /** Installs the projections and opens engines for tabs restored in metadata. Safe to call twice. */
    suspend fun start() = mutex.withLock {
        check(!closed) { "Browser session coordinator is closed." }
        if (!started) {
            started = true
            pageStateJob = scope.launch {
                controller.pageStates.collect { pages ->
                    val tabsById = store.state.value.tabs.associateBy { it.id }
                    pages.values.forEach { page ->
                        val tab = tabsById[page.tabId.value] ?: return@forEach
                        store.updateTab(
                            tabId = tab.id,
                            url = page.url,
                            title = page.title.takeIf(String::isNotBlank),
                            isLoading = page.loadPhase == BrowserLoadPhase.STARTING ||
                                page.loadPhase == BrowserLoadPhase.LOADING,
                            canGoBack = page.canGoBack,
                            canGoForward = page.canGoForward,
                        )
                    }
                }
            }
            eventJob = scope.launch {
                controller.events.collect { event ->
                    if (event is BrowserEvent.NavigationCompleted) {
                        val page = controller.pageStates.value[event.tabId]
                        store.recordVisit(event.tabId.value, event.url, page?.title.orEmpty())
                    }
                }
            }
        }
        store.state.value.tabs.toList().forEach { ensureEngineLocked(it) }
    }

    suspend fun openTab(
        url: String = "about:blank",
        title: String = "New tab",
        isPrivate: Boolean = false,
    ): BrowserTab = mutex.withLock {
        check(!closed) { "Browser session coordinator is closed." }
        check(started) { "Browser session coordinator has not been started." }
        val tab = store.openTab(url = url, title = title, isPrivate = isPrivate)
        try {
            ensureEngineLocked(tab)
            tab
        } catch (failure: Throwable) {
            // Roll back only the newly-created metadata entry. Existing tabs remain untouched.
            store.closeTab(tab.id)
            throw failure
        }
    }

    fun selectTab(tabId: String): Boolean {
        if (closed) return false
        return store.selectTab(tabId)
    }

    suspend fun closeTab(tabId: String): Boolean = mutex.withLock {
        if (closed) return false
        val tab = store.state.value.tabs.firstOrNull { it.id == tabId } ?: return@withLock false
        var closeFailure: Throwable? = null
        try {
            controller.closeTab(TabId(tabId))
        } catch (failure: Throwable) {
            // The manager removes the tab from its registry before native disposal. Metadata must
            // still follow that terminal transition, while the disposal failure remains observable.
            closeFailure = failure
        }
        store.closeTab(tabId)
        val remaining = store.state.value.tabs
        remaining.forEach { replacement ->
            if (controller.registeredEngines.value[TabId(replacement.id)] == null) {
                try {
                    ensureEngineLocked(replacement)
                } catch (failure: Throwable) {
                    if (closeFailure == null) closeFailure = failure
                    else closeFailure?.addSuppressed(failure)
                }
            }
        }
        if (closeFailure != null) throw closeFailure as Throwable
        // A metadata-only tab with no engine is not considered a successfully closed browser tab.
        tab.id.isNotBlank()
    }

    suspend fun navigate(request: NavigationRequest): NavigationDecision = mutex.withLock {
        if (closed) return@withLock NavigationDecision.Cancel
        val tab = store.state.value.tabs.firstOrNull { it.id == request.tabId.value }
            ?: return@withLock NavigationDecision.Cancel
        ensureEngineLocked(tab)
        val decision = controller.navigate(request)
        if (decision == NavigationDecision.Cancel) {
            store.updateTab(tab.id, isLoading = false)
        }
        decision
    }

    fun decideSurfaceNavigation(request: NavigationRequest): NavigationDecision =
        if (closed) NavigationDecision.Cancel else controller.decideSurfaceNavigation(request)

    fun goBack(tabId: TabId): Boolean = !closed && controller.goBack(tabId)
    fun goForward(tabId: TabId): Boolean = !closed && controller.goForward(tabId)
    fun reload(tabId: TabId): Boolean = !closed && controller.reload(tabId)
    fun stop(tabId: TabId): Boolean = !closed && controller.stop(tabId)
    fun detachSurface(tabId: TabId) = controller.detachSurface(tabId)

    fun updateTab(
        tabId: String,
        url: String? = null,
        title: String? = null,
        isLoading: Boolean? = null,
        canGoBack: Boolean? = null,
        canGoForward: Boolean? = null,
    ): Boolean = if (closed) false else store.updateTab(
        tabId, url, title, isLoading, canGoBack, canGoForward,
    )

    fun addBookmark(url: String, title: String = url): Boolean =
        !closed && store.addBookmark(url, title)

    fun removeBookmark(url: String): Boolean = !closed && store.removeBookmark(url)
    fun clearHistory() {
        if (!closed) store.clearHistory()
    }

    private suspend fun ensureEngineLocked(tab: BrowserTab) {
        val tabId = TabId(tab.id)
        val profile = profileProvider.profileFor(isPrivate = tab.isPrivate)
            ?: throw IllegalStateException(
                if (tab.isPrivate) "No isolated private browser profile is configured."
                else "No regular browser profile is configured.",
            )
        val existing = controller.registeredEngines.value[tabId]
        if (existing != null) {
            require(existing.profileId == profile.id) {
                "Tab ${tab.id} metadata profile does not match its live engine profile."
            }
            return
        }
        controller.openTab(tabId, profile.id, tab.url)
    }

    suspend fun close() = mutex.withLock {
        if (closed) return@withLock
        closed = true
        pageStateJob?.cancel()
        pageStateJob = null
        eventJob?.cancel()
        eventJob = null
        controller.close()
    }
}
