package dev.veyra.browser

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
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
 * Temporary Android native-WebView validation surface. The device provider is Chromium-based,
 * but this is not Veyra's future self-compiled kernel. Replace this platform renderer when the
 * self-compiled Chromium embedder is ready; keep Browser Core and its single engine contract stable.
 */
internal object AndroidChromiumSurfaceRenderer : BrowserSurfaceRenderer {
    // Saved WebView navigation bundles are kept only while the logical tab remains open.
    // Access occurs on the UI thread from renderer composition/disposal callbacks.
    private val savedSurfaceStates = AndroidWebViewStateStore()

    override val supportsBackgroundSurfaceDetachment: Boolean = true

    override fun onTabClosed(tabId: String) {
        savedSurfaceStates.onTabClosed(tabId)
    }

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
        val currentOnNavigationRequest by rememberUpdatedState(onNavigationRequest)
        val currentOnNavigationDecision by rememberUpdatedState(onNavigationDecision)
        val rendererGenerationState = remember(tab.id, binding) { mutableIntStateOf(0) }
        val rendererGeneration = rendererGenerationState.intValue
        val rendererCrashed = remember(tab.id, binding, rendererGeneration) { AtomicBoolean(false) }
        // Consume the saved snapshot once for this surface generation. Apply it in AndroidView's
        // factory (rather than while merely remembering the WebView) so restoration is part of
        // native-surface creation and precedes attachSurface command delivery.
        val savedState = remember(tab.id, context, binding, rendererGeneration) {
            savedSurfaceStates.takeForRestore(tab.id)
        }
        val webView = remember(tab.id, context, binding, rendererGeneration) {
            createWebView(
                context = context,
                tabId = TabId(tab.id),
                profileId = engine.profileId,
                onDownloadRequested = binding::reportDownloadRequested,
                onNavigationRequest = { currentOnNavigationRequest(it) },
                onNavigationDecision = { currentOnNavigationDecision(it) },
                onRendererGone = {
                    rendererCrashed.set(true)
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
        // All WebView method calls must occur on the thread that created the WebView (main).
        // The engine (core, platform-neutral) may invoke these surface commands from any coroutine
        // dispatcher, so marshal every call onto the main thread. surfaceDestroyed drops late
        // calls that race with disposal.
        val mainHandler = remember { Handler(Looper.getMainLooper()) }
        val surfaceDestroyed = remember(webView) { AtomicBoolean(false) }
        val surface = remember(webView) {
            object : BrowserSurfacePort {
                override fun loadUrl(url: String) {
                    mainHandler.post { if (!surfaceDestroyed.get()) webView.loadUrl(url) }
                }
                override fun goBack() {
                    mainHandler.post { if (!surfaceDestroyed.get() && webView.canGoBack()) webView.goBack() }
                }
                override fun goForward() {
                    mainHandler.post { if (!surfaceDestroyed.get() && webView.canGoForward()) webView.goForward() }
                }
                override fun reload() {
                    mainHandler.post { if (!surfaceDestroyed.get()) webView.reload() }
                }
                override fun stop() {
                    mainHandler.post { if (!surfaceDestroyed.get()) webView.stopLoading() }
                }
            }
        }

        DisposableEffect(engine, surface) {
            binding.attachSurface(surface)
            onDispose {
                surfaceDestroyed.set(true)
                binding.detachSurface(surface)
                if (!rendererCrashed.get() && !webView.url.isNullOrBlank()) {
                    val state = Bundle()
                    if (webView.saveState(state) != null) {
                        savedSurfaceStates.save(tab.id, state)
                    }
                }
                webView.stopLoading()
                webView.destroy()
            }
        }

        LaunchedEffect(webView, isVisible) {
            if (isVisible) webView.onResume() else webView.onPause()
        }


        AndroidView(
            factory = {
                // restoreState restores the native back/forward list and scroll position where
                // supported. Do this at native view creation, before the surface is attached to
                // the engine and can receive browser commands. A crashed renderer is never restored.
                if (!rendererCrashed.get()) savedState?.let(webView::restoreState)
                webView
            },
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
