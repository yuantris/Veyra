package dev.veyra.browser.core

/**
 * Normalized browser events emitted by engine adapters and fanned out by `BrowserEventHub`.
 *
 * Events are associated with `tabId` and `navigationId` where relevant. An event for an old
 * navigation must never overwrite state from a newer navigation.
 */
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

    /** A platform detected a download; policy and persistence are owned by the caller. */
    data class DownloadRequested(
        override val tabId: TabId,
        val request: DownloadRequest,
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
