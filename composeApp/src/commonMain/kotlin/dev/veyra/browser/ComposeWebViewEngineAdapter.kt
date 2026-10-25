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
import dev.veyra.browser.core.DownloadRequest
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
 * Surface-binding contract consumed by the Compose host.
 *
 * This is deliberately separate from [BrowserEngineContract]: controller code only sees the
 * engine contract, while a rendering host uses this bridge to attach its command port and report
 * observations. A future platform renderer can provide its own binding implementation.
 */
internal interface BrowserSurfaceBinding {
    val pageState: StateFlow<BrowserPageState>

    fun attachSurface(surface: BrowserSurfacePort)
    fun detachSurface(surface: BrowserSurfacePort)
    fun observe(
        url: String?,
        title: String?,
        isLoading: Boolean,
        canGoBack: Boolean,
        canGoForward: Boolean,
        errorCode: String? = null,
        errorMessage: String? = null,
    )

    /** Reports loss of the native content renderer without exposing platform types to core. */
    fun reportRendererCrashed(recoverable: Boolean)

    /** Emits a download intent; does not start the transfer. */
    fun reportDownloadRequested(request: DownloadRequest)
}

internal expect fun createPlatformBrowserEngineFactory(): BrowserEngineFactory

/**
 * Logical engine/session adapter for the current compose-webview-multiplatform surface.
 *
 * The engine is owned by BrowserController; the Compose surface can detach and reattach without
 * closing this object. App code depends on [BrowserSurfaceBinding], not this implementation.
 */
internal class SurfaceBoundBrowserEngineFactory : BrowserEngineFactory {
    override suspend fun create(request: BrowserEngineCreateRequest): BrowserEngineContract =
        ComposeWebViewEngine(request)
}

internal class ComposeWebViewEngine(
    request: BrowserEngineCreateRequest,
) : BrowserEngineContract, BrowserSurfaceBinding {
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

    override val capabilities: BrowserCapabilities = composeWebViewPlatformCapabilities()

    private var surface: BrowserSurfacePort? = null
    private var pendingUrl: String? = null
    // History commands can arrive while Compose is replacing a tab's native surface. Preserve
    // their order and replay them only after the restored surface is attached.
    private val pendingHistoryCommands = mutableListOf<HistoryCommand>()

    private enum class HistoryCommand { BACK, FORWARD }
    private var initialNavigationPending = request.initialUrl != "about:blank"
    private var navigationCounter = 0L
    private var activeNavigationId: NavigationId? = null
    private var activeNavigationUrl: String? = null
    private var activeNavigationPreviousUrl: String? = null
    private var activeNavigationCommitted = false
    private var closed = false

    override fun attachSurface(surface: BrowserSurfacePort) {
        check(!closed) { "Cannot attach a surface to a closed browser engine." }
        this.surface = surface
        if (mutablePageState.value.rendererHealthy == false) {
            mutablePageState.value = mutablePageState.value.copy(
                rendererHealthy = true,
                error = null,
            )
        }
        if (initialNavigationPending) {
            initialNavigationPending = false
            val initialUrl = mutablePageState.value.url
            val navigationId = NavigationId("${tabId.value}-nav-${++navigationCounter}")
            activeNavigationId = navigationId
            activeNavigationUrl = initialUrl
            activeNavigationPreviousUrl = null
            activeNavigationCommitted = false
            mutablePageState.value = mutablePageState.value.copy(
                loadPhase = BrowserLoadPhase.STARTING,
                progress = 0,
                navigationId = navigationId,
            )
            mutableEvents.tryEmit(BrowserEvent.NavigationStarted(tabId, navigationId, initialUrl))
            // initialUrl is a real navigation request, not just initial page metadata.
            // Deliver it to the surface when the tab first attaches.
            surface.loadUrl(initialUrl)
        }
        pendingUrl?.let { url ->
            pendingUrl = null
            surface.loadUrl(url)
        }
        if (pendingHistoryCommands.isNotEmpty()) {
            val commands = pendingHistoryCommands.toList()
            pendingHistoryCommands.clear()
            commands.forEach { command ->
                when (command) {
                    HistoryCommand.BACK -> surface.goBack()
                    HistoryCommand.FORWARD -> surface.goForward()
                }
            }
        }
    }

    override fun detachSurface(surface: BrowserSurfacePort) {
        if (this.surface === surface) this.surface = null
    }

    override fun navigate(request: NavigationRequest) {
        if (closed || request.tabId != tabId) return
        val target = request.url.trim()
        if (target.isEmpty()) return

        initialNavigationPending = false
        val previousUrl = mutablePageState.value.url
        val navigationId = NavigationId("${tabId.value}-nav-${++navigationCounter}")
        activeNavigationId = navigationId
        activeNavigationUrl = target
        activeNavigationPreviousUrl = previousUrl
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
        if (closed) return
        val currentSurface = surface
        if (currentSurface == null) {
            pendingHistoryCommands += HistoryCommand.BACK
        } else {
            currentSurface.goBack()
        }
    }

    override fun goForward() {
        if (closed) return
        val currentSurface = surface
        if (currentSurface == null) {
            pendingHistoryCommands += HistoryCommand.FORWARD
        } else {
            currentSurface.goForward()
        }
    }

    override fun reload() {
        if (closed) return
        initialNavigationPending = false
        val currentUrl = mutablePageState.value.url
        val navigationId = NavigationId("${tabId.value}-nav-${++navigationCounter}")
        activeNavigationId = navigationId
        activeNavigationUrl = currentUrl
        activeNavigationPreviousUrl = currentUrl
        activeNavigationCommitted = false
        mutablePageState.value = mutablePageState.value.copy(
            loadPhase = BrowserLoadPhase.STARTING,
            progress = 0,
            navigationId = navigationId,
            error = null,
        )
        mutableEvents.tryEmit(BrowserEvent.NavigationStarted(tabId, navigationId, currentUrl))
        val currentSurface = surface
        if (currentSurface == null) pendingUrl = currentUrl else currentSurface.reload()
    }

    override fun stop() {
        if (closed) return
        surface?.stop()
        val navigationId = activeNavigationId
        if (navigationId != null) {
            mutableEvents.tryEmit(
                BrowserEvent.NavigationFailed(
                    tabId = tabId,
                    navigationId = navigationId,
                    error = BrowserError(
                        code = "navigation_cancelled",
                        message = "Navigation was stopped.",
                        category = BrowserErrorCategory.CANCELLED,
                        failingUrl = mutablePageState.value.url,
                    ),
                ),
            )
        }
        activeNavigationId = null
        activeNavigationUrl = null
        activeNavigationPreviousUrl = null
        activeNavigationCommitted = false
        mutablePageState.value = mutablePageState.value.copy(
            loadPhase = BrowserLoadPhase.STOPPED,
            progress = null,
            navigationId = null,
        )
    }

    override fun reportDownloadRequested(request: DownloadRequest) {
        if (closed || request.tabId != tabId || request.profileId != profileId) return
        mutableEvents.tryEmit(BrowserEvent.DownloadRequested(tabId, request))
    }

    override fun reportRendererCrashed(recoverable: Boolean) {
        if (closed) return
        val previous = mutablePageState.value
        val error = BrowserError(
            code = "content_process_crashed",
            message = "The page renderer stopped unexpectedly.",
            category = BrowserErrorCategory.CONTENT_PROCESS_CRASHED,
            failingUrl = previous.url,
            isRecoverable = recoverable,
        )
        val navigationId = activeNavigationId
        mutablePageState.value = previous.copy(
            loadPhase = BrowserLoadPhase.FAILED,
            progress = null,
            navigationId = null,
            error = error,
            rendererHealthy = false,
        )
        mutableEvents.tryEmit(BrowserEvent.NavigationFailed(tabId, navigationId, error))
        mutableEvents.tryEmit(BrowserEvent.RendererCrashed(tabId, recoverable))
        activeNavigationId = null
        activeNavigationUrl = null
        activeNavigationPreviousUrl = null
        activeNavigationCommitted = false
    }

    /** Called by the Compose host when native WebView observations change. */
    override fun observe(
        url: String?,
        title: String?,
        isLoading: Boolean,
        canGoBack: Boolean,
        canGoForward: Boolean,
        errorCode: String?,
        errorMessage: String?,
    ) {
        if (closed) return
        val previous = mutablePageState.value
        val observedUrl = url?.takeIf { it.isNotBlank() } ?: previous.url
        var navigationId = activeNavigationId

        // An idle callback with a stale URL can arrive after a failed main-frame load.
        // Do not reinterpret that callback as a new navigation: explicit navigate/reload
        // clears the failed state and is the authoritative start of the next request.
        if (
            observedUrl != previous.url &&
            navigationId == null &&
            !(previous.loadPhase == BrowserLoadPhase.FAILED && !isLoading)
        ) {
            navigationId = NavigationId("${tabId.value}-nav-${++navigationCounter}")
            activeNavigationId = navigationId
            activeNavigationUrl = observedUrl
            activeNavigationPreviousUrl = previous.url
            activeNavigationCommitted = false
            mutableEvents.tryEmit(BrowserEvent.NavigationStarted(tabId, navigationId, observedUrl))
        }

        val nativeError = if (errorCode != null || errorMessage != null) {
            BrowserError(
                code = errorCode ?: "webview_error",
                message = errorMessage ?: "The page could not be loaded.",
                category = BrowserErrorCategory.ENGINE_FAILURE,
                failingUrl = observedUrl,
            )
        } else {
            null
        }
        // WebView may deliver onPageFinished after onReceivedError. Keep the main-frame
        // failure visible for that same failed URL instead of letting the trailing idle
        // callback immediately erase it. A new navigation clears the error in navigate/reload.
        val error = nativeError ?: previous.error?.takeIf {
            previous.loadPhase == BrowserLoadPhase.FAILED &&
                activeNavigationId == null
        }
        // A redirect is a valid commit too. Ignore only the old page URL while a requested
        // navigation is still waiting for its target/redirect to appear.
        val observedUrlMatchesNavigation = activeNavigationPreviousUrl == null ||
            observedUrl != activeNavigationPreviousUrl || observedUrl == activeNavigationUrl
        val stateUrl = if (
            activeNavigationId != null && !activeNavigationCommitted && !observedUrlMatchesNavigation
        ) {
            previous.url
        } else {
            observedUrl
        }
        // WebView can dispatch a final progress callback after stopLoading() has already
        // published STOPPED. Ignore a stale same-URL callback; explicit navigate/reload starts anew.
        val trailingLoadingAfterStop = previous.loadPhase == BrowserLoadPhase.STOPPED &&
            activeNavigationId == null && observedUrl == previous.url && isLoading

        mutablePageState.value = previous.copy(
            url = stateUrl,
            displayUrl = stateUrl,
            title = title ?: previous.title,
            loadPhase = when {
                error != null -> BrowserLoadPhase.FAILED
                trailingLoadingAfterStop -> BrowserLoadPhase.STOPPED
                isLoading -> if (activeNavigationCommitted) BrowserLoadPhase.LOADING else BrowserLoadPhase.STARTING
                activeNavigationId != null ->
                    if (observedUrlMatchesNavigation) BrowserLoadPhase.SUCCEEDED else previous.loadPhase
                // WebChromeClient may report progress=100 after onPageFinished. Do not let
                // that trailing idle observation erase the completed navigation state.
                previous.loadPhase == BrowserLoadPhase.SUCCEEDED && observedUrl == previous.url ->
                    BrowserLoadPhase.SUCCEEDED
                else -> BrowserLoadPhase.IDLE
            },
            progress = when {
                error != null || trailingLoadingAfterStop -> null
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

        if (nativeError != null) {
            mutableEvents.tryEmit(BrowserEvent.NavigationFailed(tabId, navigationId, nativeError))
            activeNavigationId = null
            activeNavigationUrl = null
            activeNavigationPreviousUrl = null
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
            activeNavigationPreviousUrl = null
            activeNavigationCommitted = false
            mutablePageState.value = mutablePageState.value.copy(navigationId = null)
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        surface = null
        pendingUrl = null
        pendingHistoryCommands.clear()
        initialNavigationPending = false
        activeNavigationId = null
        activeNavigationUrl = null
        activeNavigationPreviousUrl = null
        activeNavigationCommitted = false
    }
}
