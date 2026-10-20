package dev.veyra.browser

import dev.veyra.browser.core.BrowserEngine
import dev.veyra.browser.core.BrowserEngineState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridges the shared BrowserEngine contract to compose-webview-multiplatform's
 * navigator. The native rendering surface remains owned by WebView(state, navigator).
 *
 * Keep this adapter per WebView/tab; do not share it between tabs.
 */
internal class WebViewBrowserEngine(
    private val load: (String) -> Unit,
    private val back: () -> Unit,
    private val forward: () -> Unit,
    private val refresh: () -> Unit,
    private val stop: () -> Unit,
) : BrowserEngine {
    private val mutableState = MutableStateFlow(BrowserEngineState())
    override val state: StateFlow<BrowserEngineState> = mutableState.asStateFlow()

    override fun loadUrl(url: String) {
        val target = url.trim()
        if (target.isEmpty()) return
        mutableState.value = mutableState.value.copy(
            url = target,
            isLoading = true,
            error = null,
        )
        load(target)
    }

    override fun goBack() {
        if (mutableState.value.canGoBack) back()
    }

    override fun goForward() {
        if (mutableState.value.canGoForward) forward()
    }

    override fun reload() {
        mutableState.value = mutableState.value.copy(isLoading = true, error = null)
        refresh()
    }

    override fun stopLoading() = stop()

    /** Publish observations from the platform WebView state into the shared contract. */
    fun publish(
        url: String? = null,
        title: String? = null,
        isLoading: Boolean? = null,
        canGoBack: Boolean? = null,
        canGoForward: Boolean? = null,
        errorCode: String? = null,
        errorMessage: String? = null,
    ) {
        val previous = mutableState.value
        mutableState.value = previous.copy(
            url = url ?: previous.url,
            title = title ?: previous.title,
            isLoading = isLoading ?: previous.isLoading,
            canGoBack = canGoBack ?: previous.canGoBack,
            canGoForward = canGoForward ?: previous.canGoForward,
            error = if (errorCode != null || errorMessage != null) {
                dev.veyra.browser.core.BrowserError(
                    code = errorCode ?: "webview_error",
                    message = errorMessage ?: "The page could not be loaded.",
                )
            } else {
                previous.error
            },
        )
    }

    override fun dispose() {
        mutableState.value = BrowserEngineState()
    }
}
