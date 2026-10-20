package dev.veyra.browser.core

import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-neutral contract for a browser rendering engine.
 *
 * Keep browser policy and tab/session state above this interface. Platform WebView
 * objects must never escape into shared business logic.
 */
interface BrowserEngine {
    val state: StateFlow<BrowserEngineState>

    fun loadUrl(url: String)
    fun goBack()
    fun goForward()
    fun reload()
    fun stopLoading()
    fun dispose()
}

data class BrowserEngineState(
    val url: String = "about:blank",
    val title: String = "",
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val error: BrowserError? = null,
)

data class BrowserError(
    val code: String,
    val message: String,
)
