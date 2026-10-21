package dev.veyra.browser

import com.multiplatform.webview.request.RequestInterceptor
import com.multiplatform.webview.request.WebRequest
import com.multiplatform.webview.request.WebRequestInterceptResult
import com.multiplatform.webview.web.WebViewNavigator
import dev.veyra.browser.core.NavigationDecision
import dev.veyra.browser.core.NavigationRequest
import dev.veyra.browser.core.NavigationSource
import dev.veyra.browser.core.TabId

/**
 * Adapts native URL interception callbacks to Browser Core's synchronous navigation policy.
 *
 * This adapter only governs main-frame document navigation. It intentionally leaves
 * subframes and subresources to the platform engine because they are not tab-level navigations.
 */
internal class BrowserNavigationRequestInterceptor(
    private val tabId: TabId,
    private val onNavigationRequest: (NavigationRequest) -> NavigationDecision,
    private val onNavigationDecision: (NavigationDecision) -> Unit,
) : RequestInterceptor {

    override fun onInterceptUrlRequest(
        request: WebRequest,
        navigator: WebViewNavigator,
    ): WebRequestInterceptResult = intercept(request)

    internal fun intercept(request: WebRequest): WebRequestInterceptResult {
        if (!request.isForMainFrame) return WebRequestInterceptResult.Allow

        val source = if (request.isRedirect) NavigationSource.REDIRECT else NavigationSource.PAGE
        return when (
            val decision = onNavigationRequest(
                NavigationRequest(
                    tabId = tabId,
                    url = request.url,
                    source = source,
                    isMainFrame = true,
                ),
            )
        ) {
            NavigationDecision.Allow -> WebRequestInterceptResult.Allow
            NavigationDecision.Cancel -> WebRequestInterceptResult.Reject
            is NavigationDecision.OpenExternal -> {
                onNavigationDecision(decision)
                WebRequestInterceptResult.Reject
            }
            is NavigationDecision.OpenInNewTab -> {
                onNavigationDecision(decision)
                WebRequestInterceptResult.Reject
            }
        }
    }
}
