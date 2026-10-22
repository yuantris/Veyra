package dev.veyra.browser

import dev.veyra.browser.download.DownloadTransport

/**
 * iOS has no page download detection (WKWebView exposes no download API) and therefore no
 * transport; the engine capability model reports the same truth (downloads = false).
 */
internal actual fun createPlatformDownloadTransport(): DownloadTransport? = null
