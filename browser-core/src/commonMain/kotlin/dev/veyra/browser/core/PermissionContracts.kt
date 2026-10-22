package dev.veyra.browser.core

/**
 * Website permission contracts.
 *
 * Website permission, OS runtime permission and Veyra/extension capability permission are
 * distinct layers; see `dev.veyra.browser.core.security` for bridge-side authorization.
 */

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
