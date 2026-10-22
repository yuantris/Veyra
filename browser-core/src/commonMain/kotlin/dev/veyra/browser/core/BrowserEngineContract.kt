package dev.veyra.browser.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The single platform-neutral engine/session contract used by the controller and every adapter.
 *
 * Platform implementations must report their actual capabilities and keep native engine objects
 * outside common code. Session closure is idempotent and independent from surface detachment.
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

/**
 * Input to a platform engine factory. The factory owns native-engine construction and must not
 * expose platform objects to common code.
 */
data class BrowserEngineCreateRequest(
    val tabId: TabId,
    val profileId: ProfileId,
    val isPrivate: Boolean = false,
    val initialUrl: String = "about:blank",
)

/**
 * Creates a platform engine/session for a browser tab.
 *
 * Implementations may create a Chromium-backed session, WKWebView-backed state, ArkWeb
 * controllers, or a KCEF session. Whether private profiles and other optional features are
 * supported must be reported through [BrowserEngineContract.capabilities].
 */
interface BrowserEngineFactory {
    suspend fun create(request: BrowserEngineCreateRequest): BrowserEngineContract
}
