package dev.veyra.browser

import dev.veyra.browser.core.BrowserCapabilities
import dev.veyra.browser.core.BrowserEngineContract
import dev.veyra.browser.core.BrowserEngineCreateRequest
import dev.veyra.browser.core.BrowserEngineFactory
import dev.veyra.browser.core.BrowserError
import dev.veyra.browser.core.BrowserErrorCategory
import dev.veyra.browser.core.BrowserEvent
import dev.veyra.browser.core.BrowserLoadPhase
import dev.veyra.browser.core.BrowserPageState
import dev.veyra.browser.core.NavigationId
import dev.veyra.browser.core.NavigationRequest
import dev.veyra.browser.core.ProfileId
import dev.veyra.browser.core.SessionId
import dev.veyra.browser.core.TabId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The narrow boundary between the platform-neutral engine contract and the Compose WebView
 * rendering surface. No WebView, navigator, or platform object crosses this interface.
 */
internal interface BrowserSurfacePort {
    fun loadUrl(url: String)
    fun goBack()
    fun goForward()
    fun reload()
    fun stop()
}

/**
 * Logical engine/session adapter for the current compose-webview-multiplatform surface.
 *
 * The engine is owned by BrowserController; the Compose surface can detach and reattach without
 * closing this object. A future GeckoView/KCEF/ArkWeb adapter can implement the same contract
 * without changing browser-core or the app shell.
 */
internal class ComposeWebViewEngineFactory : BrowserEngineFactory {
    override suspend fun create(request: BrowserEngineCreateRequest): BrowserEngineContract =
        ComposeWebViewEngine(request)
}

internal class ComposeWebViewEngine(
    request: BrowserEngineCreateRequest,
) : BrowserEngineContract {
    override val tabId: TabId = request.tabId
    override val sessionId = SessionId("session-${request.tabId.value}")
    override val profileId: ProfileId = request.profileId

    private val mutablePageState = MutableStateFlow(
        BrowserPageState(
            tabId = tabId,
            url = request.initialUrl,
            displayUrl = request.initialUrl,
        ),
    )
    override val pageState: StateFlow<BrowserPageState> = mutablePageState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<BrowserEvent>(extraBufferCapacity = 64)
    override val events: Flow<BrowserEvent> = mutableEvents.asSharedFlow()

    override val capabilities = BrowserCapabilities(
        navigationPolicy = false,
        privateProfiles = false,
        sessionRestore = false,
    )

    private var surface: BrowserSurfacePort? = null
    private var pendingUrl: String? = null
    private var navigationCounter = 0L
    private var activeNavigationId: NavigationId? = null
    private var activeNavigationUrl: String? = null
    private var activeNavigationCommitted = false
    private var closed = false

    fun attachSurface(newSurface: BrowserSurfacePort) {
        check(!closed) { "Cannot attach a surface to a closed browser engine." }
        surface = newSurface
        pendingUrl?.let { url ->
            pendingUrl = null
            newSurface.loadUrl(url)
        }
    }

    fun detachSurface(detachedSurface: BrowserSurfacePort) {
        if (surface === detachedSurface) surface = null
    }

    override fun navigate(request: NavigationRequest) {
        if (closed || request.tabId != tabId) return
        val target = request.url.trim()
        if (target.isEmpty()) return

        val navigationId = NavigationId("${tabId.value}-nav-${++navigationCounter}")
        activeNavigationId = navigationId
        activeNavigationUrl = target
        activeNavigationCommitted = false
        mutablePageState.value = mutablePageState.value.copy(
            url = target,
            displayUrl = target,
            loadPhase = BrowserLoadPhase.STARTING,
            progress = 0,
            navigationId = navigationId,
            error = null,
        )
        mutableEvents.tryEmit(BrowserEvent.NavigationStarted(tabId, navigationId, target))

        val currentSurface = surface
        if (currentSurface == null) pendingUrl = target else currentSurface.loadUrl(target)
    }

    override fun goBack() {
        if (!closed) surface?.goBack()
    }

    override fun goForward() {
        if (!closed) surface?.goForward()
    }

    override fun reload() {
        if (!closed) {
            mutablePageState.value = mutablePageState.value.copy(
                loadPhase = BrowserLoadPhase.STARTING,
                progress = 0,
                error = null,
            )
            surface?.reload()
        }
    }

    override fun stop() {
        if (closed) return
        surface?.stop()
        mutablePageState.value = mutablePageState.value.copy(loadPhase = BrowserLoadPhase.STOPPED)
    }

    /** Called by the Compose host when native WebView observations change. */
    fun observe(
        url: String?,
        title: String?,
        isLoading: Boolean,
        canGoBack: Boolean,
        canGoForward: Boolean,
        errorCode: String? = null,
        errorMessage: String? = null,
    ) {
        if (closed) return
        val previous = mutablePageState.value
        val observedUrl = url?.takeIf { it.isNotBlank() } ?: previous.url
        var navigationId = activeNavigationId

        if (observedUrl != previous.url && navigationId == null) {
            navigationId = NavigationId("${tabId.value}-nav-${++navigationCounter}")
            activeNavigationId = navigationId
            activeNavigationUrl = observedUrl
            activeNavigationCommitted = false
            mutableEvents.tryEmit(BrowserEvent.NavigationStarted(tabId, navigationId, observedUrl))
        }

        val error = if (errorCode != null || errorMessage != null) {
            BrowserError(
                code = errorCode ?: "webview_error",
                message = errorMessage ?: "The page could not be loaded.",
                category = BrowserErrorCategory.ENGINE_FAILURE,
                failingUrl = observedUrl,
            )
        } else {
            null
        }
        val observedUrlMatchesNavigation = activeNavigationUrl == null ||
            observedUrl == activeNavigationUrl
        val stateUrl = if (
            activeNavigationId != null && !activeNavigationCommitted && !observedUrlMatchesNavigation
        ) {
            previous.url
        } else {
            observedUrl
        }

        mutablePageState.value = previous.copy(
            url = stateUrl,
            displayUrl = stateUrl,
            title = title ?: previous.title,
            loadPhase = when {
                error != null -> BrowserLoadPhase.FAILED
                isLoading -> if (activeNavigationCommitted) BrowserLoadPhase.LOADING else BrowserLoadPhase.STARTING
                activeNavigationId != null -> BrowserLoadPhase.SUCCEEDED
                else -> BrowserLoadPhase.IDLE
            },
            progress = when {
                error != null -> null
                isLoading -> previous.progress?.coerceIn(0, 99) ?: 10
                else -> 100
            },
            canGoBack = canGoBack,
            canGoForward = canGoForward,
            navigationId = navigationId,
            error = error,
        )

        if (navigationId != null && !activeNavigationCommitted && observedUrlMatchesNavigation) {
            activeNavigationCommitted = true
            mutableEvents.tryEmit(BrowserEvent.NavigationCommitted(tabId, navigationId, observedUrl))
        }

        if (error != null) {
            mutableEvents.tryEmit(BrowserEvent.NavigationFailed(tabId, navigationId, error))
            activeNavigationId = null
            activeNavigationUrl = null
            activeNavigationCommitted = false
        } else if (!isLoading && activeNavigationCommitted) {
            val completedNavigationId = activeNavigationId
            if (completedNavigationId != null) {
                mutableEvents.tryEmit(
                    BrowserEvent.NavigationCompleted(tabId, completedNavigationId, observedUrl),
                )
            }
            activeNavigationId = null
            activeNavigationUrl = null
            activeNavigationCommitted = false
            mutablePageState.value = mutablePageState.value.copy(navigationId = null)
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        surface = null
        pendingUrl = null
        activeNavigationId = null
        activeNavigationUrl = null
        activeNavigationCommitted = false
    }
}
