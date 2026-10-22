package dev.veyra.browser

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.WebViewNavigator
import com.multiplatform.webview.web.rememberWebViewNavigator
import com.multiplatform.webview.web.rememberWebViewState
import dev.veyra.browser.core.NavigationDecision
import dev.veyra.browser.core.NavigationRequest
import dev.veyra.browser.core.TabId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Exercises the production Compose WebView -> navigator RequestInterceptor -> Browser Core
 * decision adapter path on a real Android WebView. This test intentionally uses a data-only
 * document and rejects the destination before the WebView can make a network request.
 */
@RunWith(AndroidJUnit4::class)
class BrowserNavigationRequestInterceptorAndroidTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rejectedMainFrameLinkIsInterceptedAndCurrentDocumentRemainsLoaded() {
        val navigatorRef = AtomicReference<WebViewNavigator?>()
        val interceptedUrl = AtomicReference<String?>()
        val intercepted = CountDownLatch(1)
        val pageReady = CountDownLatch(1)
        val clickFinished = CountDownLatch(1)
        val currentUrl = AtomicReference<String?>()

        composeRule.setContent {
            val interceptor = remember {
                BrowserNavigationRequestInterceptor(
                    tabId = TabId("android-instrumentation"),
                    onNavigationRequest = { request: NavigationRequest ->
                        interceptedUrl.set(request.url)
                        intercepted.countDown()
                        if (request.url.endsWith("/blocked")) {
                            NavigationDecision.Cancel
                        } else {
                            NavigationDecision.Allow
                        }
                    },
                    onNavigationDecision = {},
                )
            }
            val navigator = rememberWebViewNavigator(requestInterceptor = interceptor)
            val state = rememberWebViewState("about:blank")
            SideEffect { navigatorRef.set(navigator) }
            WebView(
                state = state,
                navigator = navigator,
                modifier = Modifier,
            )
        }

        composeRule.waitUntil(timeoutMillis = 10_000) { navigatorRef.get() != null }
        composeRule.runOnIdle {
            navigatorRef.get()!!.loadHtml(
                html = """
                    <!doctype html>
                    <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
                    <body><p id="status">ready</p>
                    <a id="blocked" href="https://veyra.invalid/blocked">blocked link</a>
                    </body></html>
                """.trimIndent(),
                baseUrl = "https://veyra.invalid/start",
                historyUrl = "https://veyra.invalid/start",
            )
        }

        // loadHtml() is asynchronous: the navigation event is queued first, but the
        // WebView may not have committed the document when the test thread resumes.
        // Poll through the real navigator until the DOM is available instead of issuing
        // a single JavaScript evaluation that can race the initial page load.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.runOnIdle {
                navigatorRef.get()?.evaluateJavaScript(
                    "document.getElementById('status')?.textContent ?: ''",
                ) {
                    if (it.contains("ready")) pageReady.countDown()
                }
            }
            pageReady.count == 0L
        }
        assertTrue("Test document did not become ready", pageReady.await(1, TimeUnit.SECONDS))

        composeRule.runOnIdle {
            navigatorRef.get()!!.evaluateJavaScript(
                "document.getElementById('blocked').click(); " +
                    "location.href",
            ) {
                currentUrl.set(it.trim('"'))
                clickFinished.countDown()
            }
        }

        assertTrue("Production RequestInterceptor was not invoked", intercepted.await(10, TimeUnit.SECONDS))
        assertTrue("Could not read the current document URL", clickFinished.await(10, TimeUnit.SECONDS))
        assertEquals("https://veyra.invalid/blocked", interceptedUrl.get())
        assertEquals("https://veyra.invalid/start", currentUrl.get())
    }
}
