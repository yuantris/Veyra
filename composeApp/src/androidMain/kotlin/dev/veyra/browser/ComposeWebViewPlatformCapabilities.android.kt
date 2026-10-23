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
        // DownloadPipelineAndroidTest passed on the Android Emulator against the current
        // compose-webview prototype. This advertises only this adapter's verified download path;
        // it does not imply GeckoView integration is complete.
        downloads = true,
        requestInterception = RequestInterceptionScope.MAIN_FRAME_NAVIGATION,
        privateProfiles = false,
        sessionRestore = false,
    )
