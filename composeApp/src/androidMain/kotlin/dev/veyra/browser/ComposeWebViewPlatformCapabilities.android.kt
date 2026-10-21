package dev.veyra.browser

import dev.veyra.browser.core.BrowserCapabilities
import dev.veyra.browser.core.RequestInterceptionScope

internal actual fun composeWebViewPlatformCapabilities(): BrowserCapabilities =
    BrowserCapabilities(
        navigationPolicy = true,
        requestInterception = RequestInterceptionScope.MAIN_FRAME_NAVIGATION,
        privateProfiles = false,
        sessionRestore = false,
    )
