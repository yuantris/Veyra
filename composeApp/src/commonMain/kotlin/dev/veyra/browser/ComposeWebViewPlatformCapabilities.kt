package dev.veyra.browser

import dev.veyra.browser.core.BrowserCapabilities

/**
 * Returns the capabilities of the active Compose WebView implementation on this target.
 *
 * Keep this target-specific: the same common WebView API does not guarantee that every
 * platform invokes its request interceptor.
 */
internal expect fun composeWebViewPlatformCapabilities(): BrowserCapabilities
