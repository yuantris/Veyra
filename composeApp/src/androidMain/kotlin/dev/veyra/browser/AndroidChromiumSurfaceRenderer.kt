package dev.veyra.browser

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.viewinterop.AndroidView
import dev.veyra.browser.core.BrowserEngineContract
import dev.veyra.browser.core.BrowserTab
import dev.veyra.browser.core.NavigationDecision
import dev.veyra.browser.core.NavigationRequest

/**
 * Android's Chromium-backed WebView surface. The engine contract stays platform-neutral;
 * this renderer owns only the Android view and translates its callbacks into engine observations.
 *
 * Note: this uses the device's Chromium WebView provider, not a bundled standalone Chromium build.
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
        val initialUrl = binding.pageState.value.url
        val webView = remember(tab.id) { createWebView() }
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
private fun createWebView(): WebView {
    lateinit var view: WebView
    view = WebViewHolder.create { webView ->
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                view.reportObservation()
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                view.reportObservation(isLoading = newProgress < 100)
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                view.reportObservation(url = url, isLoading = true)
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.reportObservation(url = url, isLoading = false)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    view.reportObservation(
                        url = request.url.toString(),
                        isLoading = false,
                        errorCode = "chromium_webview_${error.errorCode}",
                        errorMessage = error.description?.toString() ?: "Page load failed.",
                    )
                }
            }
        }
    }
    return view
}

/** Kept as a helper so creation can be swapped for a future bundled Chromium embedder. */
private object WebViewHolder {
    fun create(configure: (WebView) -> Unit): WebView {
        val context = AndroidBrowserContext.current
            ?: error("Android Chromium surface must be created inside a Compose Android view context.")
        return WebView(context).also(configure)
    }
}

private object AndroidBrowserContext {
    var current: android.content.Context? = null
}

private fun WebView.reportObservation(
    url: String? = this.url,
    isLoading: Boolean = progress < 100,
    errorCode: String? = null,
    errorMessage: String? = null,
) {
    // The renderer keeps a weak association to the active logical binding through its view tag.
    val binding = getTag(R.id.veyra_browser_binding) as? BrowserSurfaceBinding ?: return
    binding.observe(
        url = url,
        title = title,
        isLoading = isLoading,
        canGoBack = canGoBack(),
        canGoForward = canGoForward(),
        errorCode = errorCode,
        errorMessage = errorMessage,
    )
}

internal actual fun createBrowserSurfaceRenderer(): BrowserSurfaceRenderer =
    AndroidChromiumSurfaceRenderer
