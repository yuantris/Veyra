package dev.veyra.browser

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.webkit.WebChromeClient
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.webkit.RenderProcessGoneDetail
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.veyra.browser.core.BrowserEngineContract
import dev.veyra.browser.core.session.BrowserTab
import dev.veyra.browser.core.NavigationDecision
import dev.veyra.browser.core.NavigationRequest
import dev.veyra.browser.core.NavigationSource
import dev.veyra.browser.core.DownloadRequest
import dev.veyra.browser.core.RequestId
import dev.veyra.browser.core.ProfileId
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
        isVisible: Boolean,
        onNavigationRequest: (NavigationRequest) -> NavigationDecision,
        onNavigationDecision: (NavigationDecision) -> Unit,
    ) {
        val binding = engine as? BrowserSurfaceBinding ?: return
        val context = LocalContext.current
        val initialUrl = binding.pageState.value.url
        val currentOnNavigationRequest by rememberUpdatedState(onNavigationRequest)
        val currentOnNavigationDecision by rememberUpdatedState(onNavigationDecision)
        val rendererGenerationState = remember(tab.id, binding) { mutableIntStateOf(0) }
        val rendererGeneration = rendererGenerationState.intValue
        val webView = remember(tab.id, context, binding, rendererGeneration) {
            createWebView(
                context = context,
                tabId = TabId(tab.id),
                profileId = engine.profileId,
                onDownloadRequested = binding::reportDownloadRequested,
                onNavigationRequest = { currentOnNavigationRequest(it) },
                onNavigationDecision = { currentOnNavigationDecision(it) },
                onRendererGone = {
                    binding.reportRendererCrashed(recoverable = true)
                    // Changing the key replaces the dead WebView while preserving the tab engine.
                    if (rendererGenerationState.intValue == rendererGeneration) {
                        rendererGenerationState.intValue += 1
                    }
                },
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

        LaunchedEffect(webView, isVisible) {
            if (isVisible) webView.onResume() else webView.onPause()
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

/**
 * Production DownloadListener wiring: normalizes the platform callback into a core
 * [DownloadRequest] without starting any transfer. The candidate is only announced; the app
 * acceptance policy decides what happens next.
 */
internal fun createDownloadListener(
    tabId: TabId,
    profileId: ProfileId,
    onDownloadRequested: (DownloadRequest) -> Unit,
): DownloadListener = DownloadListener { url, _, contentDisposition, mimeType, contentLength ->
    onDownloadRequested(
        DownloadRequest(
            requestId = RequestId("download-${java.util.UUID.randomUUID()}"),
            tabId = tabId,
            profileId = profileId,
            url = url,
            suggestedFileName = URLUtil.guessFileName(url, contentDisposition, mimeType),
            mimeType = mimeType,
            expectedBytes = contentLength.takeIf { it >= 0L },
        ),
    )
}

@SuppressLint("SetJavaScriptEnabled")
internal fun createWebView(
    context: android.content.Context,
    tabId: TabId,
    profileId: ProfileId,
    onDownloadRequested: (DownloadRequest) -> Unit,
    onNavigationRequest: (NavigationRequest) -> NavigationDecision,
    onNavigationDecision: (NavigationDecision) -> Unit,
    onRendererGone: () -> Unit,
    observe: (WebView, String?, String?, Boolean, String?, String?) -> Unit,
): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true

    // Secure defaults: web content must not be able to read local files or mix HTTP into HTTPS.
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.allowFileAccessFromFileURLs = false
    settings.allowUniversalAccessFromFileURLs = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        settings.safeBrowsingEnabled = true
    }

    setDownloadListener(
        createDownloadListener(
            tabId = tabId,
            profileId = profileId,
            onDownloadRequested = onDownloadRequested,
        ),
    )

    webChromeClient = object : WebChromeClient() {
        override fun onReceivedTitle(view: WebView, title: String?) {
            observe(view, view.url, title, view.progress < 100, null, null)
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            observe(view, view.url, view.title, newProgress < 100, null, null)
        }
    }

    webViewClient = object : WebViewClient() {
        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            // A WebView whose renderer is gone must not be reused. Returning true marks the
            // failure handled; the Compose host creates a replacement surface for this tab.
            onRendererGone()
            return true
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError,
        ) {
            // Never let a page bypass certificate validation through an interactive proceed().
            handler.cancel()
            observe(
                view,
                error.url,
                view.title,
                false,
                "tls_certificate_error",
                "The secure connection could not be verified.",
            )
        }

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
