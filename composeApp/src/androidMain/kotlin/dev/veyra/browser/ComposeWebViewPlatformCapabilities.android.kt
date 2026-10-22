package dev.veyra.browser

import dev.veyra.browser.core.BrowserCapabilities
import dev.veyra.browser.core.RequestInterceptionScope

internal actual fun composeWebViewPlatformCapabilities(): BrowserCapabilities =
    BrowserCapabilities(
        // Keep the public capability conservative until real-WebView integration tests pass.
        navigationPolicy = false,
        // The download pipeline is wired end-to-end (DownloadListener -> app policy -> core task
        // model -> Ktor transport) and unit-tested at the component level, but no emulator/device
        // test has proven the full path yet. Flip to true together with that integration test;
        // see the downloads verification gate in docs/PLATFORM_CAPABILITY_MATRIX.md.
        downloads = false,
        requestInterception = RequestInterceptionScope.MAIN_FRAME_NAVIGATION,
        privateProfiles = false,
        sessionRestore = false,
    )
