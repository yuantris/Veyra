package dev.veyra.browser.core

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
    /** Synchronous by design so native callbacks can enforce the result without blocking. */
    fun decide(request: NavigationRequest): NavigationDecision {
        if (request.url.isBlank()) return NavigationDecision.Cancel

        val scheme = request.url.substringBefore(':', missingDelimiterValue = "")
            .lowercase()
        if (scheme.isBlank()) return NavigationDecision.Cancel

        val handler = policyHandler ?: return defaultDecision(request.url, scheme)
        return handler.decide(request)
    }

    private fun defaultDecision(url: String, scheme: String): NavigationDecision =
        when (scheme) {
            "http", "https", "about", "file", "data", "blob" -> NavigationDecision.Allow
            else -> NavigationDecision.OpenExternal(url)
        }
}
