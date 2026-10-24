package dev.veyra.browser

import android.os.Bundle

/**
 * Owns restorable native surface snapshots independently from logical Browser Core sessions.
 *
 * A snapshot is single-use: the next surface creation consumes it. Explicit tab closure removes
 * any snapshot that has not yet been consumed, preventing a later tab reusing the same ID from
 * inheriting stale history.
 *
 * All calls are expected on the Android main thread, matching WebView save/restore ownership.
 */
internal class AndroidWebViewStateStore {
    private val snapshots = mutableMapOf<String, Bundle>()

    fun save(tabId: String, state: Bundle) { snapshots[tabId] = state }

    fun takeForRestore(tabId: String): Bundle? = snapshots.remove(tabId)

    fun onTabClosed(tabId: String) { snapshots.remove(tabId) }
}
