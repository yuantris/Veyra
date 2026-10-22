package dev.veyra.browser

internal actual fun createBrowserSurfaceRenderer(): BrowserSurfaceRenderer =
    ComposeWebViewSurfaceRenderer
