package dev.veyra.browser

import dev.veyra.browser.core.BrowserCapabilities
import dev.veyra.browser.core.RequestInterceptionScope

internal actual fun composeWebViewPlatformCapabilities(): BrowserCapabilities =
    BrowserCapabilities(
        // Keep the public capability conservative until real-WebView integration tests pass.
        navigationPolicy = false,
        requestInterception = RequestInterceptionScope.MAIN_FRAME_NAVIGATION,
        privateProfiles = false,
        sessionRestore = false,
    )
