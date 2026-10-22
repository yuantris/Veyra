package dev.veyra.browser.core

/**
 * Navigation, resource-interception and popup contracts.
 *
 * These are policy request/result types: the platform-neutral decision rules live in
 * `dev.veyra.browser.core.navigation`, and adapters map native callbacks onto them.
 */

data class NavigationRequest(
    val tabId: TabId,
    val url: String,
    val source: NavigationSource = NavigationSource.UNKNOWN,
    val isMainFrame: Boolean = true,
    val hasUserGesture: Boolean? = null,
)

enum class NavigationSource {
    USER,
    PAGE,
    REDIRECT,
    RESTORE,
    UNKNOWN,
}

sealed interface NavigationDecision {
    data object Allow : NavigationDecision
    data object Cancel : NavigationDecision
    data class OpenExternal(val url: String) : NavigationDecision
    data class OpenInNewTab(val url: String) : NavigationDecision
}

interface NavigationPolicyHandler {
    /** Must be fast and non-blocking: native navigation callbacks may be synchronous. */
    fun decide(request: NavigationRequest): NavigationDecision
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

interface RequestInterceptor {
    val supportedScope: RequestInterceptionScope
    suspend fun intercept(request: ResourceRequest): ResourceDecision
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
