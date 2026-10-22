package dev.veyra.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.rememberWebViewNavigator
import com.multiplatform.webview.web.rememberWebViewState
import dev.veyra.browser.core.BrowserEngineContract
import dev.veyra.browser.core.session.BrowserTab
import dev.veyra.browser.core.NavigationDecision
import dev.veyra.browser.core.NavigationRequest
import dev.veyra.browser.core.TabId

/**
 * UI-facing renderer boundary. App/session logic supplies a logical engine and never depends on
 * a concrete engine implementation or a WebView navigator.
 */
internal interface BrowserSurfaceRenderer {
    @Composable
    fun Render(
        tab: BrowserTab,
        engine: BrowserEngineContract,
        isVisible: Boolean,
        onNavigationRequest: (NavigationRequest) -> NavigationDecision,
        onNavigationDecision: (NavigationDecision) -> Unit,
    )
}

/** Platform source sets select the renderer; common UI never chooses a concrete engine surface. */
internal expect fun createBrowserSurfaceRenderer(): BrowserSurfaceRenderer

internal object ComposeWebViewSurfaceRenderer : BrowserSurfaceRenderer {
    @Composable
    override fun Render(
        tab: BrowserTab,
        engine: BrowserEngineContract,
        isVisible: Boolean,
        onNavigationRequest: (NavigationRequest) -> NavigationDecision,
        onNavigationDecision: (NavigationDecision) -> Unit,
    ) {
        ComposeWebViewSurface(tab, engine, isVisible, onNavigationRequest, onNavigationDecision)
    }
}

@Composable
private fun ComposeWebViewSurface(
    tab: BrowserTab,
    engine: BrowserEngineContract,
    isVisible: Boolean,
    onNavigationRequest: (NavigationRequest) -> NavigationDecision,
    onNavigationDecision: (NavigationDecision) -> Unit,
 ) {
    val surfaceEngine = engine as? BrowserSurfaceBinding ?: return
    val webState = rememberWebViewState(surfaceEngine.pageState.value.url)
    val currentOnNavigationRequest by rememberUpdatedState(onNavigationRequest)
    val currentOnNavigationDecision by rememberUpdatedState(onNavigationDecision)
    val requestInterceptor = remember(engine, tab.id) {
        BrowserNavigationRequestInterceptor(
            tabId = TabId(tab.id),
            onNavigationRequest = { currentOnNavigationRequest(it) },
            onNavigationDecision = { currentOnNavigationDecision(it) },
        )
    }
    val navigator = rememberWebViewNavigator(requestInterceptor = requestInterceptor)
    val surface = remember(navigator) {
        object : BrowserSurfacePort {
            override fun loadUrl(url: String) { navigator.loadUrl(url) }
            override fun goBack() { navigator.navigateBack() }
            override fun goForward() { navigator.navigateForward() }
            override fun reload() { navigator.reload() }
            override fun stop() { navigator.stopLoading() }
        }
    }

    DisposableEffect(engine, surface) {
        surfaceEngine.attachSurface(surface)
        onDispose { surfaceEngine.detachSurface(surface) }
    }

    LaunchedEffect(
        tab.id,
        webState.lastLoadedUrl,
        webState.pageTitle,
        webState.isLoading,
        navigator.canGoBack,
        navigator.canGoForward,
    ) {
        val url = webState.lastLoadedUrl ?: surfaceEngine.pageState.value.url
        surfaceEngine.observe(
            url = url,
            title = webState.pageTitle,
            isLoading = webState.isLoading,
            canGoBack = navigator.canGoBack,
            canGoForward = navigator.canGoForward,
        )
    }

    WebView(
        state = webState,
        navigator = navigator,
        modifier = Modifier.fillMaxSize(),
    )
}

