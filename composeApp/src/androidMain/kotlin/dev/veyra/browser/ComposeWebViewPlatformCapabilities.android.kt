package dev.veyra.browser

import dev.veyra.browser.core.BrowserCapabilities
import dev.veyra.browser.core.RequestInterceptionScope

internal actual fun composeWebViewPlatformCapabilities(): BrowserCapabilities =
    BrowserCapabilities(
        // Verified on the API 35 system-WebView emulator by AndroidNavigationPolicyWebViewTest
        // (connectedDebugAndroidTest in the CI android-emulator-integration job). The production
        // shouldOverrideUrlLoading path routes every main-frame navigation through the core policy
        // before the load happens, so this capability is now truthful.
        navigationPolicy = true,
        // DownloadPipelineAndroidTest passed on the API 35 emulator against the current native
        // System WebView validation adapter. This capability applies only to this implementation;
        // the future self-compiled Chromium adapter must pass its own integration test.
        downloads = true,
        requestInterception = RequestInterceptionScope.MAIN_FRAME_NAVIGATION,
        privateProfiles = false,
        sessionRestore = false,
    )
