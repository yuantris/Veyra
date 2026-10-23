package dev.veyra.browser

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.veyra.browser.core.NavigationDecision
import dev.veyra.browser.core.NavigationRequest
import dev.veyra.browser.core.ProfileId
import dev.veyra.browser.core.TabId
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the PRODUCTION Android navigation path: the real device/system WebView created by
 * [createWebView] and its [android.webkit.WebViewClient.shouldOverrideUrlLoading] interception,
 * which is exactly what [AndroidChromiumSurfaceRenderer] wires into the core navigation policy.
 *
 * This is intentionally NOT the compose-webview library's `WebViewNavigator` path. It drives the
 * same factory the shipping renderer uses, so a green run here means the in-app navigation policy
 * is genuinely enforced by the system WebView, not just by common unit tests.
 *
 * All assertions are network-free: `shouldOverrideUrlLoading` is invoked synchronously when a
 * top-level navigation is initiated, before any network request leaves the device — which is the
 * precise enforcement point the navigation-policy capability gate requires.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNavigationPolicyWebViewTest {

    private lateinit var context: android.content.Context
    private lateinit var scenario: ActivityScenario<DownloadTestActivity>
    private lateinit var webView: android.webkit.WebView

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scenario = ActivityScenario.launch(DownloadTestActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    private fun buildWebView(
        onNav: (NavigationRequest) -> NavigationDecision,
        committed: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue(),
    ) {
        scenario.onActivity { activity ->
            webView = createWebView(
                context = activity,
                tabId = TabId("nav-tab"),
                profileId = ProfileId("default"),
                onDownloadRequested = {},
                onNavigationRequest = onNav,
                onNavigationDecision = {},
                onRendererGone = {},
                observe = { _, url, _, isLoading, _, _ -> if (isLoading == true) url?.let { committed.add(it) } },
            )
            activity.setContentView(webView)
        }
    }

    private fun loadHtml(html: String) {
        scenario.onActivity {
            webView.loadDataWithBaseURL("https://veyra.invalid/", html, "text/html", "UTF-8", null)
        }
    }

    private fun runJs(script: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.evaluateJavascript(script, null)
        }
    }

    private fun waitForElement(id: String, timeoutMs: Long = 10_000): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                webView.evaluateJavascript("document.getElementById('$id') ? '1' : ''") { result ->
                    if (result.contains("1")) ready.countDown()
                }
            }
            if (ready.await(250, TimeUnit.MILLISECONDS)) return true
        }
        return false
    }

    private fun waitUntil(timeoutMs: Long = 5000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(100)
        }
        return false
    }

    @Test
    fun allowedMainFrameLinkLoadsOnce() {
        val navUrls = ConcurrentLinkedQueue<String>()
        val latch = CountDownLatch(1)
        val committed = ConcurrentLinkedQueue<String>()
        buildWebView(
            onNav = { req ->
                navUrls.add(req.url)
                latch.countDown()
                NavigationDecision.Allow
            },
            committed = committed,
        )
        loadHtml("""<!doctype html><html><body><a id="go" href="data:text/html,allowed">go</a></body></html>""")
        assertTrue("Test document did not become ready", waitForElement("go"))
        runJs("document.getElementById('go').click()")
        assertTrue("Navigation request was not intercepted", latch.await(10, TimeUnit.SECONDS))
        assertEquals("Allowed navigation must be seen exactly once", 1, navUrls.size)
        assertEquals("data:text/html,allowed", navUrls.first())
        assertTrue(
            "Allowed URL should have started loading in the WebView",
            waitUntil(5000) { committed.contains("data:text/html,allowed") },
        )
    }

    @Test
    fun rejectedMainFrameDoesNotLoad() {
        val navUrls = ConcurrentLinkedQueue<String>()
        val latch = CountDownLatch(1)
        val committed = ConcurrentLinkedQueue<String>()
        buildWebView(
            onNav = { req ->
                navUrls.add(req.url)
                latch.countDown()
                NavigationDecision.Cancel
            },
            committed = committed,
        )
        loadHtml("""<!doctype html><html><body><a id="b" href="data:text/html,blocked">b</a></body></html>""")
        assertTrue("Test document did not become ready", waitForElement("b"))
        runJs("document.getElementById('b').click()")
        assertTrue("Navigation request was not intercepted", latch.await(10, TimeUnit.SECONDS))
        assertEquals("data:text/html,blocked", navUrls.first())
        assertFalse(
            "Rejected URL must never start loading in the WebView",
            committed.contains("data:text/html,blocked"),
        )
    }

    @Test
    fun customSchemeIsRoutedToHostAndNotLoadedByWebView() {
        val navUrls = ConcurrentLinkedQueue<String>()
        val latch = CountDownLatch(1)
        val committed = ConcurrentLinkedQueue<String>()
        buildWebView(
            onNav = { req ->
                navUrls.add(req.url)
                latch.countDown()
                NavigationDecision.Cancel
            },
            committed = committed,
        )
        loadHtml("""<!doctype html><html><body><a id="ext" href="myapp://host/path?x=1">ext</a></body></html>""")
        assertTrue("Test document did not become ready", waitForElement("ext"))
        runJs("document.getElementById('ext').click()")
        assertTrue("Custom-scheme navigation was not intercepted", latch.await(10, TimeUnit.SECONDS))
        assertEquals("myapp://host/path?x=1", navUrls.first())
        assertFalse(
            "Custom scheme must not be loaded by the WebView; it is the host's decision",
            committed.contains("myapp://host/path?x=1"),
        )
    }

    @Test
    fun subresourcesDoNotEnterTopLevelNavigationPolicy() {
        val navUrls = ConcurrentLinkedQueue<String>()
        val mainLatch = CountDownLatch(1)
        buildWebView(
            onNav = { req ->
                navUrls.add(req.url)
                if (req.url.endsWith("/main")) mainLatch.countDown()
                NavigationDecision.Allow
            },
        )
        loadHtml(
            """
            <!doctype html>
            <html><body>
            <img src="https://veyra.invalid/img.png">
            <script src="https://veyra.invalid/s.js"></script>
            <iframe src="https://veyra.invalid/frame"></iframe>
            <a id="main" href="https://veyra.invalid/main">main</a>
            </body></html>
            """.trimIndent(),
        )
        assertTrue("Test document did not become ready", waitForElement("main"))
        // Positive control: a real main-frame link must reach the policy exactly once.
        runJs("document.getElementById('main').click()")
        assertTrue("Main-frame link was not intercepted", mainLatch.await(10, TimeUnit.SECONDS))
        // Let subresource requests (img/script/iframe) be attempted by the WebView.
        Thread.sleep(2_000)
        assertTrue(
            "Main-frame link should have reached the top-level policy",
            navUrls.contains("https://veyra.invalid/main"),
        )
        assertFalse(
            "Image subresource must not enter top-level navigation policy",
            navUrls.contains("https://veyra.invalid/img.png"),
        )
        assertFalse(
            "Script subresource must not enter top-level navigation policy",
            navUrls.contains("https://veyra.invalid/s.js"),
        )
        assertFalse(
            "Iframe subresource must not enter top-level navigation policy",
            navUrls.contains("https://veyra.invalid/frame"),
        )
    }

    @Test
    fun destroyedWebViewDoesNotDeliverLateNavigationToHost() {
        val navUrls = ConcurrentLinkedQueue<String>()
        val latch = CountDownLatch(1)
        buildWebView(
            onNav = { req ->
                navUrls.add(req.url)
                latch.countDown()
                NavigationDecision.Allow
            },
        )
        loadHtml("""<!doctype html><html><body><a id="go" href="https://veyra.invalid/allowed">go</a></body></html>""")
        assertTrue("Test document did not become ready", waitForElement("go"))
        runJs("document.getElementById('go').click()")
        assertTrue("Navigation request was not intercepted", latch.await(10, TimeUnit.SECONDS))
        val countBeforeClose = navUrls.size

        // Simulate closing the tab: tear down the WebView surface.
        scenario.onActivity { webView.destroy() }

        // A late navigation attempt after disposal must not reach the host policy.
        try {
            runJs("location.href='https://veyra.invalid/late'")
        } catch (_: Throwable) {
        }
        try {
            scenario.onActivity { webView.loadUrl("https://veyra.invalid/late") }
        } catch (_: Throwable) {
        }
        Thread.sleep(1_000)
        assertEquals(
            "No navigation may reach the host after the WebView is destroyed",
            countBeforeClose,
            navUrls.size,
        )
    }
}
