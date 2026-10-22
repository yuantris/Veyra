package dev.veyra.browser

import dev.veyra.browser.core.BrowserEngineFactory

internal actual fun createPlatformBrowserEngineFactory(): BrowserEngineFactory =
    SurfaceBoundBrowserEngineFactory()
