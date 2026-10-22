package dev.veyra.browser

import dev.veyra.browser.core.BrowserCapabilities
import dev.veyra.browser.core.RequestInterceptionScope

internal actual fun composeWebViewPlatformCapabilities(): BrowserCapabilities =
    BrowserCapabilities(
        // Keep the public capability conservative until real-WebView integration tests pass.
        navigationPolicy = false,
        // DownloadPipelineAndroidTest passed on the Android Emulator against the current
        // compose-webview prototype. This advertises only this adapter's verified download path;
        // it does not imply GeckoView integration is complete.
        downloads = true,
        requestInterception = RequestInterceptionScope.MAIN_FRAME_NAVIGATION,
        privateProfiles = false,
        sessionRestore = false,
    )
