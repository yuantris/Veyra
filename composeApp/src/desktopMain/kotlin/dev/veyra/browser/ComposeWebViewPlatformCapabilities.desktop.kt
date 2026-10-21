package dev.veyra.browser

import dev.veyra.browser.core.BrowserCapabilities
import dev.veyra.browser.core.RequestInterceptionScope

internal actual fun composeWebViewPlatformCapabilities(): BrowserCapabilities =
    BrowserCapabilities(
        navigationPolicy = false,
        requestInterception = RequestInterceptionScope.NONE,
        privateProfiles = false,
        sessionRestore = false,
    )
