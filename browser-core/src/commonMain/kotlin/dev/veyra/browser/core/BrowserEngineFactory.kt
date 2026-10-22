package dev.veyra.browser.core

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
