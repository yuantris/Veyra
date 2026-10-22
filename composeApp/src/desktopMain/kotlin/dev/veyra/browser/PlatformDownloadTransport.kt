package dev.veyra.browser

import dev.veyra.browser.download.DownloadTransport

/**
 * Desktop has no download detection or execution yet; the engine capability model reports the
 * same truth (downloads = false). Implement a transport here once KCEF download handling is
 * verified, not before.
 */
internal actual fun createPlatformDownloadTransport(): DownloadTransport? = null
