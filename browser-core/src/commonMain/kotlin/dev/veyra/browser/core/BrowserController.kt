package dev.veyra.browser.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Public facade that coordinates profiles, engine ownership, navigation policy and normalized
 * event publication. UI code should depend on this facade rather than constructing engines.
 *
 * The supplied scope must outlive the controller and should be cancelled by the application owner.
 * Platform adapters remain responsible for native view attachment and enforcing capabilities.
 */
class BrowserController(
    private val engineManager: BrowserEngineManager,
    private val profileProvider: BrowserProfileProvider,
    private val navigationCoordinator: BrowserNavigationCoordinator,
    private val eventHub: BrowserEventHub,
    private val scope: CoroutineScope,
) {
    private val lifecycleMutex = Mutex()
    private val eventJobs = mutableMapOf<TabId, Job>()
    private var closed = false

    val events: Flow<BrowserEvent>
        get() = eventHub.events

    val registeredEngines
        get() = engineManager.registeredEngines

    suspend fun openTab(
        tabId: TabId,
        profileId: ProfileId = profileProvider.defaultProfile().id,
        initialUrl: String = "about:blank",
    ): BrowserEngineContract {
        val profile = profileProvider.get(profileId)
            ?: throw IllegalArgumentException("Unknown browser profile: ${profileId.value}")

        return lifecycleMutex.withLock {
            check(!closed) { "Browser controller is closed." }
            val engine = engineManager.openTab(
                BrowserEngineCreateRequest(
                    tabId = tabId,
                    profileId = profile.id,
                    isPrivate = profile.isPrivate,
                    initialUrl = initialUrl,
                ),
            )
            if (eventJobs[tabId] == null) {
                eventJobs[tabId] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    // Subscribe before openTab returns, so an immediate first navigation event
                    // cannot be lost on dispatchers that schedule launch asynchronously.
                    // Navigation IDs are opaque. Keep the latest started ID local to this
                    // subscription so late commit/finish callbacks from an older page are dropped.
                    var activeNavigationId: NavigationId? = null
                    engine.events.collect { event ->
                        if (engineManager.get(tabId) !== engine || event.tabId != tabId) {
                            return@collect
                        }

                        val isCurrentNavigationEvent = when (event) {
                            is BrowserEvent.NavigationStarted -> {
                                activeNavigationId = event.navigationId
                                true
                            }
                            is BrowserEvent.NavigationCommitted ->
                                event.navigationId == activeNavigationId
                            is BrowserEvent.NavigationCompleted -> {
                                val matches = event.navigationId == activeNavigationId
                                if (matches) activeNavigationId = null
                                matches
                            }
                            is BrowserEvent.NavigationFailed -> {
                                val matches = event.navigationId == null ||
                                    event.navigationId == activeNavigationId
                                if (matches && event.navigationId != null) activeNavigationId = null
                                matches
                            }
                            else -> true
                        }
                        if (isCurrentNavigationEvent) eventHub.publish(event)
                    }
                }
            }
            engine
        }
    }

    /**
     * Runs the shared policy before issuing a top-level navigation.
     *
     * Only Allow is executed by this facade. OpenExternal/OpenInNewTab are returned to the caller
     * for explicit routing; this keeps platform intents and tab creation out of the core module.
     */
    suspend fun navigate(request: NavigationRequest): NavigationDecision {
        val engine = engineManager.get(request.tabId)
            ?: return NavigationDecision.Cancel
        val decision = navigationCoordinator.decide(request)
        if (decision == NavigationDecision.Allow) {
            // Re-check ownership before dispatch in case another caller closed/replaced the tab.
            if (engineManager.get(request.tabId) !== engine) return NavigationDecision.Cancel
            engine.navigate(request)
        }
        return decision
    }

    /**
     * Evaluates a navigation initiated by the native page surface without dispatching a second
     * navigation command. The platform callback must continue or cancel its original request
     * according to this result.
     */
    fun decideSurfaceNavigation(request: NavigationRequest): NavigationDecision {
        val engine = engineManager.get(request.tabId) ?: return NavigationDecision.Cancel
        val decision = navigationCoordinator.decide(request)
        if (engineManager.get(request.tabId) !== engine) return NavigationDecision.Cancel
        return decision
    }

    fun goBack(tabId: TabId): Boolean = engineManager.get(tabId)?.let {
        it.goBack()
        true
    } ?: false

    fun goForward(tabId: TabId): Boolean = engineManager.get(tabId)?.let {
        it.goForward()
        true
    } ?: false

    fun reload(tabId: TabId): Boolean = engineManager.get(tabId)?.let {
        it.reload()
        true
    } ?: false

    fun stop(tabId: TabId): Boolean = engineManager.get(tabId)?.let {
        it.stop()
        true
    } ?: false

    /** Detaching a surface preserves its engine and event subscription. */
    fun detachSurface(tabId: TabId) {
        engineManager.detachSurface(tabId)
    }

    suspend fun closeTab(tabId: TabId): Boolean = lifecycleMutex.withLock {
        val job = eventJobs.remove(tabId)
        job?.cancel()
        val closedEngine = engineManager.closeTab(tabId)
        closedEngine || job != null
    }

    suspend fun close() {
        lifecycleMutex.withLock {
            if (closed) return
            closed = true
            eventJobs.values.forEach(Job::cancel)
            eventJobs.clear()
        }
        engineManager.closeAll()
    }
}
