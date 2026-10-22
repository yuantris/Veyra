package dev.veyra.browser

import dev.veyra.browser.core.DownloadRequest

/**
 * Explicit product policy for download candidates.
 *
 * A platform download callback is a candidate, not consent: nothing starts a transfer until
 * this policy accepts the request. The core deliberately has no opinion about product policy;
 * ownership, profile and capability validation happen there after acceptance.
 *
 * These rules are the current product decision (HTTP/HTTPS only). Replace them, or add user
 * consent UI in front of [decide], when product behavior is defined.
 */
object DownloadPolicy {

    sealed interface Decision {
        data class Accept(val request: DownloadRequest) : Decision
        data class Reject(val reason: String) : Decision
    }

    fun decide(request: DownloadRequest): Decision {
        val url = request.url.trim()
        val isWeb = url.startsWith("https://", ignoreCase = true) ||
            url.startsWith("http://", ignoreCase = true)
        return if (isWeb) {
            Decision.Accept(request)
        } else {
            Decision.Reject("Only HTTP(S) downloads can start.")
        }
    }
}
