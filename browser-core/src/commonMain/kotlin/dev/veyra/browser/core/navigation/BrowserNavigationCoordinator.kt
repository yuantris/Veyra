package dev.veyra.browser.core.navigation

import dev.veyra.browser.core.NavigationDecision
import dev.veyra.browser.core.NavigationPolicyHandler
import dev.veyra.browser.core.NavigationRequest

/**
 * Centralizes navigation-policy evaluation before a platform adapter starts a navigation.
 *
 * Native engines must invoke this for navigations they can intercept. A platform that cannot
 * intercept a navigation source must report that limitation through BrowserCapabilities rather
 * than claiming that this coordinator protects that source.
 */
class BrowserNavigationCoordinator(
    private val policyHandler: NavigationPolicyHandler? = null,
) {
    /**
     * Synchronous by design so native callbacks can enforce the result without blocking.
     * Basic syntax and script-URL checks run before custom policy to prevent a permissive host
     * policy from accidentally turning malformed or executable URL input into a navigation.
     */
    fun decide(request: NavigationRequest): NavigationDecision {
        val url = request.url
        if (url.isBlank() || url != url.trim() || url.any { it.isWhitespace() || it.isISOControl() }) {
            return NavigationDecision.Cancel
        }

        val scheme = url.substringBefore(':', missingDelimiterValue = "")
            .lowercase()
        if (!SCHEME_PATTERN.matches(scheme)) return NavigationDecision.Cancel

        // Never route script URLs to the host or allow them as top-level navigations.
        if (scheme == "javascript" || scheme == "vbscript") return NavigationDecision.Cancel

        if (scheme == "http" || scheme == "https") {
            if (!url.startsWith("$scheme://", ignoreCase = true)) {
                return NavigationDecision.Cancel
            }
            val authority = url.substringAfter("://").substringBeforeAny('/', '?', '#')
            if (authority.isBlank() || authority.startsWith(":")) {
                return NavigationDecision.Cancel
            }
        }

        if (scheme == "about" && !url.equals("about:blank", ignoreCase = true)) {
            return NavigationDecision.Cancel
        }

        val handler = policyHandler
        if (handler != null) return handler.decide(request)
        return defaultDecision(url, scheme)
    }

    private fun defaultDecision(url: String, scheme: String): NavigationDecision =
        when (scheme) {
            "http", "https", "about" -> NavigationDecision.Allow
            // These schemes require explicit product/profile policy; they are not safe defaults.
            "file", "data", "blob", "content", "intent" -> NavigationDecision.Cancel
            else -> NavigationDecision.OpenExternal(url)
        }

    private fun String.substringBeforeAny(vararg delimiters: Char): String {
        val index = indexOfAny(delimiters)
        return if (index < 0) this else substring(0, index)
    }

    private companion object {
        val SCHEME_PATTERN = Regex("[a-z][a-z0-9+.-]*")
    }
}
