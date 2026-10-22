package dev.veyra.browser

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.veyra.browser.core.BrowserEngineContract
import dev.veyra.browser.core.BrowserTab
import dev.veyra.browser.core.NavigationDecision
import dev.veyra.browser.core.NavigationRequest
import dev.veyra.browser.core.NavigationSource
import dev.veyra.browser.core.TabId

/**
 * Android Chromium-backed surface using the platform WebView provider.
 * A future bundled Chromium embedder can replace this implementation without changing core.
 */
internal object AndroidChromiumSurfaceRenderer : BrowserSurfaceRenderer {
    @Composable
    override fun Render(
        tab: BrowserTab,
        engine: BrowserEngineContract,
        onNavigationRequest: (NavigationRequest) -> NavigationDecision,
        onNavigationDecision: (NavigationDecision) -> Unit,
    ) {
        val binding = engine as? BrowserSurfaceBinding ?: return
        val context = LocalContext.current
        val initialUrl = binding.pageState.value.url
        val webView = remember(tab.id, context, binding) {
            createWebView(
                context = context,
                tabId = TabId(tab.id),
                onNavigationRequest = onNavigationRequest,
                onNavigationDecision = onNavigationDecision,
            ) { view, url, title, loading, errorCode, errorMessage ->
                binding.observe(
                    url = url,
                    title = title,
                    isLoading = loading,
                    canGoBack = view.canGoBack(),
                    canGoForward = view.canGoForward(),
                    errorCode = errorCode,
                    errorMessage = errorMessage,
                )
            }
        }
        val surface = remember(webView) {
            object : BrowserSurfacePort {
                override fun loadUrl(url: String) { webView.loadUrl(url) }
                override fun goBack() { if (webView.canGoBack()) webView.goBack() }
                override fun goForward() { if (webView.canGoForward()) webView.goForward() }
                override fun reload() { webView.reload() }
                override fun stop() { webView.stopLoading() }
            }
        }

        DisposableEffect(engine, surface) {
            binding.attachSurface(surface)
            onDispose {
                binding.detachSurface(surface)
                webView.stopLoading()
                webView.destroy()
            }
        }

        LaunchedEffect(webView, initialUrl) {
            if (initialUrl.isNotBlank() && initialUrl != "about:blank" && webView.url != initialUrl) {
                webView.loadUrl(initialUrl)
            }
        }

        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
            update = { },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    context: android.content.Context,
    tabId: TabId,
    onNavigationRequest: (NavigationRequest) -> NavigationDecision,
    onNavigationDecision: (NavigationDecision) -> Unit,
    observe: (WebView, String?, String?, Boolean, String?, String?) -> Unit,
): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true

    webChromeClient = object : WebChromeClient() {
        override fun onReceivedTitle(view: WebView, title: String?) {
            observe(view, view.url, title, view.progress < 100, null, null)
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            observe(view, view.url, view.title, newProgress < 100, null, null)
        }
    }

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            if (!request.isForMainFrame) return false
            val decision = onNavigationRequest(
                NavigationRequest(
                    tabId = tabId,
                    url = request.url.toString(),
                    source = NavigationSource.PAGE,
                ),
            )
            onNavigationDecision(decision)
            return decision != NavigationDecision.Allow
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            observe(view, url, view.title, true, null, null)
        }

        override fun onPageFinished(view: WebView, url: String) {
            observe(view, url, view.title, false, null, null)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (request.isForMainFrame) {
                observe(
                    view,
                    request.url.toString(),
                    view.title,
                    false,
                    "chromium_webview_" + error.errorCode,
                    error.description?.toString() ?: "Page load failed.",
                )
            }
        }
    }
}

internal actual fun createBrowserSurfaceRenderer(): BrowserSurfaceRenderer =
    AndroidChromiumSurfaceRenderer
