package dev.veyra.browser.core

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
) {
    init {
        require(progress == null || progress in 0..100) {
            "Browser progress must be null or within 0..100."
        }
    }
}

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

/** Normalized error shared by all engine adapters. Native diagnostics remain in platform logs. */
data class BrowserError(
    val code: String,
    val message: String,
    val category: BrowserErrorCategory = BrowserErrorCategory.UNKNOWN,
    val nativeCode: String? = null,
    val failingUrl: String? = null,
    val isMainFrame: Boolean? = null,
    val isRecoverable: Boolean = true,
)

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
