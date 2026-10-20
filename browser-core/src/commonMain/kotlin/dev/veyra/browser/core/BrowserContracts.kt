package dev.veyra.browser.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stable identities shared by the browser domain and platform adapters.
 *
 * IDs are opaque: callers must not infer ordering or identity from their string value.
 */
@JvmInline
value class TabId(val value: String)

@JvmInline
value class SessionId(val value: String)

@JvmInline
value class NavigationId(val value: String)

@JvmInline
value class RequestId(val value: String)

@JvmInline
value class ProfileId(val value: String)

/** Current loading lifecycle for a page. */
enum class BrowserLoadPhase {
    IDLE,
    STARTING,
    LOADING,
    SUCCEEDED,
    FAILED,
    STOPPED,
}

/** Information that can be shown for the current top-level document. */
data class BrowserPageState(
    val tabId: TabId,
    val url: String = "about:blank",
    val displayUrl: String = url,
    val title: String = "",
    val loadPhase: BrowserLoadPhase = BrowserLoadPhase.IDLE,
    /** Normalized progress in the inclusive range 0..100; null means unavailable. */
    val progress: Int? = null,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val navigationId: NavigationId? = null,
    val error: BrowserError? = null,
    val isSecureConnection: Boolean? = null,
    val rendererHealthy: Boolean? = null,
)

/** Cross-platform error categories. Native diagnostics remain in platform logs. */
enum class BrowserErrorCategory {
    INVALID_URL,
    UNSUPPORTED_SCHEME,
    NAVIGATION_BLOCKED,
    DNS,
    CONNECTION,
    TLS,
    TIMEOUT,
    OFFLINE,
    HTTP,
    CONTENT_PROCESS_CRASHED,
    RESOURCE,
    PERMISSION_DENIED,
    CANCELLED,
    ENGINE_FAILURE,
    UNKNOWN,
}

/** High-level engine capabilities; false means unavailable, not merely unimplemented in UI. */
data class BrowserCapabilities(
    val navigationPolicy: Boolean = false,
    val requestInterception: RequestInterceptionScope = RequestInterceptionScope.NONE,
    val downloads: Boolean = false,
    val websitePermissions: Boolean = false,
    val popups: Boolean = false,
    val nativeMessaging: Boolean = false,
    val webExtensions: Boolean = false,
    val privateProfiles: Boolean = false,
    val certificateCallbacks: Boolean = false,
    val sessionRestore: Boolean = false,
)

enum class RequestInterceptionScope {
    NONE,
    MAIN_FRAME_NAVIGATION,
    SELECTED_RESOURCE_TYPES,
    BROAD_RESOURCE_INTERCEPTION,
}

enum class NavigationSource {
    USER,
    PAGE,
    REDIRECT,
    RESTORE,
    UNKNOWN,
}

data class NavigationRequest(
    val tabId: TabId,
    val url: String,
    val source: NavigationSource = NavigationSource.UNKNOWN,
    val isMainFrame: Boolean = true,
    val hasUserGesture: Boolean? = null,
)

sealed interface BrowserEvent {
    val tabId: TabId

    data class NavigationStarted(
        override val tabId: TabId,
        val navigationId: NavigationId,
        val url: String,
    ) : BrowserEvent

    data class NavigationCommitted(
        override val tabId: TabId,
        val navigationId: NavigationId,
        val url: String,
    ) : BrowserEvent

    data class TitleChanged(
        override val tabId: TabId,
        val title: String,
    ) : BrowserEvent

    data class ProgressChanged(
        override val tabId: TabId,
        val progress: Int,
    ) : BrowserEvent

    data class NavigationCompleted(
        override val tabId: TabId,
        val navigationId: NavigationId,
        val url: String,
    ) : BrowserEvent

    data class NavigationFailed(
        override val tabId: TabId,
        val navigationId: NavigationId?,
        val error: BrowserError,
    ) : BrowserEvent

    data class NewWindowRequested(
        override val tabId: TabId,
        val url: String?,
        val hasUserGesture: Boolean?,
    ) : BrowserEvent

    data class RendererCrashed(
        override val tabId: TabId,
        val recoverable: Boolean,
    ) : BrowserEvent

    data class SessionLost(
        override val tabId: TabId,
        val reason: String,
    ) : BrowserEvent
}

/**
 * Next-generation engine contract.
 *
 * Kept separate from the existing BrowserEngine during this migration step so the current
 * prototype remains buildable while adapters move over incrementally.
 */
interface BrowserEngineContract {
    val tabId: TabId
    val sessionId: SessionId
    val profileId: ProfileId
    val pageState: StateFlow<BrowserPageState>
    val events: Flow<BrowserEvent>
    val capabilities: BrowserCapabilities

    fun navigate(request: NavigationRequest)
    fun goBack()
    fun goForward()
    fun reload()
    fun stop()

    /** Must be safe to call more than once. */
    suspend fun close()
}

data class ResourceRequest(
    val tabId: TabId,
    val url: String,
    val method: String = "GET",
    val resourceType: ResourceType = ResourceType.UNKNOWN,
    val isMainFrame: Boolean = false,
)

enum class ResourceType {
    DOCUMENT,
    SCRIPT,
    STYLESHEET,
    IMAGE,
    MEDIA,
    FONT,
    XHR,
    FETCH,
    OTHER,
    UNKNOWN,
}

sealed interface ResourceDecision {
    data object Allow : ResourceDecision
    data object Block : ResourceDecision
    data class RewriteUrl(val url: String) : ResourceDecision
}

interface NavigationPolicyHandler {
    suspend fun decide(request: NavigationRequest): NavigationDecision
}

sealed interface NavigationDecision {
    data object Allow : NavigationDecision
    data object Cancel : NavigationDecision
    data class OpenExternal(val url: String) : NavigationDecision
    data class OpenInNewTab(val url: String) : NavigationDecision
}

interface RequestInterceptor {
    val supportedScope: RequestInterceptionScope
    suspend fun intercept(request: ResourceRequest): ResourceDecision
}

data class WebsitePermissionRequest(
    val requestId: RequestId,
    val tabId: TabId,
    /** Origin must be derived from trusted engine metadata by the platform adapter. */
    val origin: String,
    val permission: WebsitePermission,
)

enum class WebsitePermission {
    LOCATION,
    CAMERA,
    MICROPHONE,
    NOTIFICATIONS,
    CLIPBOARD_READ,
    CLIPBOARD_WRITE,
    MIDI,
    UNKNOWN,
}

sealed interface WebsitePermissionDecision {
    data object Grant : WebsitePermissionDecision
    data object Deny : WebsitePermissionDecision
    data object Cancel : WebsitePermissionDecision
    data object Unsupported : WebsitePermissionDecision
}

interface WebsitePermissionBroker {
    suspend fun request(request: WebsitePermissionRequest): WebsitePermissionDecision
}

data class PopupRequest(
    val requestId: RequestId,
    val sourceTabId: TabId,
    val targetUrl: String?,
    val hasUserGesture: Boolean?,
)

sealed interface PopupDecision {
    data object Block : PopupDecision
    data class OpenInNewTab(val initialUrl: String?) : PopupDecision
    data class OpenInCurrentTab(val url: String) : PopupDecision
    data class OpenExternal(val url: String) : PopupDecision
}

interface PopupHandler {
    suspend fun onPopup(request: PopupRequest): PopupDecision
}

/** Download state is intentionally independent from navigation/page state. */
data class DownloadRequest(
    val requestId: RequestId,
    val tabId: TabId,
    val profileId: ProfileId,
    val url: String,
    val suggestedFileName: String? = null,
    val mimeType: String? = null,
    val expectedBytes: Long? = null,
    val hasUserGesture: Boolean? = null,
)

enum class DownloadPhase {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class DownloadTask(
    val id: String,
    val request: DownloadRequest,
    val phase: DownloadPhase,
    val receivedBytes: Long = 0,
    val totalBytes: Long? = request.expectedBytes,
    val errorCode: String? = null,
)

interface DownloadController {
    val tasks: StateFlow<List<DownloadTask>>
    suspend fun start(request: DownloadRequest): String
    suspend fun cancel(downloadId: String)
}
