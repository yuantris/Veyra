package dev.veyra.browser.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Engine command facade that coordinates engine ownership, navigation policy and normalized event
 * publication. Application/UI code should use BrowserSessionCoordinator rather than calling this
 * facade directly; the coordinator owns metadata-to-engine workflows.
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
    private val downloadController: DownloadController? = null,
) {
    private val lifecycleMutex = Mutex()
    private val eventJobs = mutableMapOf<TabId, Job>()
    private val pageStateJobs = mutableMapOf<TabId, Job>()
    private val mutablePageStates = MutableStateFlow<Map<TabId, BrowserPageState>>(emptyMap())
    private var closed = false

    /** Latest normalized page state for every engine currently owned by this controller. */
    val pageStates: StateFlow<Map<TabId, BrowserPageState>> = mutablePageStates.asStateFlow()

    val events: Flow<BrowserEvent>
        get() = eventHub.events

    val registeredEngines
        get() = engineManager.registeredEngines

    /** Download task state is available when a platform/application download controller is wired. */
    val downloadTasks: StateFlow<List<DownloadTask>>?
        get() = downloadController?.tasks

    /**
     * Starts a download only after the caller has explicitly accepted a DownloadRequested event.
     * The browser core validates tab ownership and profile identity but leaves transport,
     * authentication, destination selection and persistence to the injected controller.
     */
    suspend fun startDownload(request: DownloadRequest): String {
        val controller = checkNotNull(downloadController) {
            "No download controller is configured for this browser."
        }
        val engine = engineManager.get(request.tabId)
            ?: throw IllegalArgumentException("Unknown browser tab: ${request.tabId.value}")
        require(engine.capabilities.downloads) {
            "The active browser engine does not support downloads."
        }
        require(request.profileId == engine.profileId) {
            "Download profile does not match the owning browser tab."
        }
        require(request.url.isNotBlank()) { "Download URL must not be blank." }
        require(request.requestId.value.isNotBlank()) { "Download request ID must not be blank." }

        // Re-check ownership immediately before starting, as tab closure can race with caller work.
        check(engineManager.get(request.tabId) === engine) {
            "The browser tab was closed before the download could start."
        }
        return controller.start(request)
    }

    suspend fun cancelDownload(downloadId: String) {
        val controller = checkNotNull(downloadController) {
            "No download controller is configured for this browser."
        }
        controller.cancel(downloadId)
    }

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
            if (pageStateJobs[tabId] == null) {
                pageStateJobs[tabId] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    engine.pageState.collect { state ->
                        if (engineManager.get(tabId) === engine) {
                            mutablePageStates.value = mutablePageStates.value + (tabId to state)
                        }
                    }
                }
            }
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
        // This entry point governs top-level document navigation only. Subframes and resources
        // are handled by their own adapter/security policy and must not be mistaken for a tab
        // navigation merely because a platform callback surfaced them here.
        if (!request.isMainFrame) return NavigationDecision.Allow
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
        if (closed) return@withLock false
        val eventJob = eventJobs.remove(tabId)
        eventJob?.cancel()
        val pageStateJob = pageStateJobs.remove(tabId)
        pageStateJob?.cancel()
        mutablePageStates.value = mutablePageStates.value - tabId
        val closedEngine = engineManager.closeTab(tabId)
        closedEngine || eventJob != null || pageStateJob != null
    }

    suspend fun close() = lifecycleMutex.withLock {
        if (closed) return@withLock
        // Keep the lifecycle lock until engine shutdown completes. Otherwise a concurrent
        // closeTab() can race with closeAll() after the controller has been marked closed.
        closed = true
        eventJobs.values.forEach(Job::cancel)
        eventJobs.clear()
        pageStateJobs.values.forEach(Job::cancel)
        pageStateJobs.clear()
        mutablePageStates.value = emptyMap()
        engineManager.closeAll()
    }
}
