package dev.veyra.browser

// Temporary bridge until the WKWebView native adapter is introduced.
internal actual fun createBrowserSurfaceRenderer(): BrowserSurfaceRenderer =
    ComposeWebViewSurfaceRenderer
