package dev.veyra.browser

import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.veyra.browser.core.NavigationDecision
import dev.veyra.browser.core.NavigationRequest
import dev.veyra.browser.core.ProfileId
import dev.veyra.browser.core.TabId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * First Android native-engine smoke gate.
 *
 * Uses the production WebView factory that AndroidChromiumSurfaceRenderer installs, not a
 * separately configured test WebView. This validates basic document loading, native callbacks,
 * and secure defaults before more browser capabilities are enabled. The System WebView is a
 * validation runtime only; the adapter boundary must remain replaceable by the future
 * self-compiled Chromium runtime.
 */
@RunWith(AndroidJUnit4::class)
class AndroidWebViewRuntimeSmokeTest {
    private lateinit var scenario: ActivityScenario<DownloadTestActivity>
    private var webView: WebView? = null

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(DownloadTestActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.onActivity {
            webView?.let { view ->
                view.stopLoading()
                view.destroy()
            }
            webView = null
        }
        scenario.close()
    }

    @Test
    fun productionWebViewLoadsDocumentReportsTitleAndKeepsSecureDefaults() {
        val titleObserved = CountDownLatch(1)
        val observedTitle = AtomicReference<String?>()
        val observedUrl = AtomicReference<String?>()

        scenario.onActivity { activity ->
            val view = createWebView(
                context = activity,
                tabId = TabId("runtime-smoke"),
                profileId = ProfileId("default"),
                onDownloadRequested = {},
                onNavigationRequest = { _: NavigationRequest -> NavigationDecision.Allow },
                onNavigationDecision = {},
                onRendererGone = {},
                observe = { _, url, title, isLoading, _, _ ->
                    if (!isLoading && title == "Veyra native WebView smoke") {
                        observedTitle.set(title)
                        observedUrl.set(url)
                        titleObserved.countDown()
                    }
                },
            )
            webView = view
            activity.setContentView(view)

            assertTrue("JavaScript should be enabled for the current browser prototype", view.settings.javaScriptEnabled)
            assertFalse("Local file access must remain disabled", view.settings.allowFileAccess)
            assertFalse("Content-provider access must remain disabled", view.settings.allowContentAccess)
            assertFalse("File URL JavaScript access must remain disabled", view.settings.allowFileAccessFromFileURLs)
            assertFalse("Universal file URL access must remain disabled", view.settings.allowUniversalAccessFromFileURLs)
            assertEquals(
                "Mixed content must be blocked",
                android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW,
                view.settings.mixedContentMode,
            )

            view.loadDataWithBaseURL(
                "https://veyra.invalid/",
                """
                <!doctype html>
                <html>
                  <head><title>Veyra native WebView smoke</title></head>
                  <body><main id="ready">Android native WebView is alive</main></body>
                </html>
                """.trimIndent(),
                "text/html",
                "UTF-8",
                null,
            )
        }

        assertTrue(
            "Production WebView did not finish loading the deterministic smoke document",
            titleObserved.await(10, TimeUnit.SECONDS),
        )
        assertEquals("Veyra native WebView smoke", observedTitle.get())
        assertNotNull("A document URL should be observed from the native callback", observedUrl.get())
        assertTrue(
            "The loaded document should retain its Veyra test origin",
            observedUrl.get().orEmpty().startsWith("https://veyra.invalid/"),
        )
    }
}
