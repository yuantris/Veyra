package dev.veyra.browser

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.veyra.browser.core.DownloadPhase
import dev.veyra.browser.core.DownloadRequest
import dev.veyra.browser.core.NavigationDecision
import dev.veyra.browser.core.ProfileId
import dev.veyra.browser.core.RequestId
import dev.veyra.browser.core.TabId
import dev.veyra.browser.download.DefaultDownloadController
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device verification for the download pipeline described in
 * `docs/PLATFORM_CAPABILITY_MATRIX.md` (downloads verification gate).
 *
 * Everything here runs against production code: the real WebView DownloadListener wiring, the
 * acceptance policy, the core task controller, the Ktor transport and the Android file sink.
 * MockWebServer provides the transfer endpoint so no external network is involved.
 */
@RunWith(AndroidJUnit4::class)
class DownloadPipelineAndroidTest {

    private lateinit var context: Context
    private lateinit var downloadsRoot: File
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AndroidAppContext.initialize(context)
        downloadsRoot = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "veyra")
        downloadsRoot.deleteRecursively()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        downloadsRoot.deleteRecursively()
    }

    @Test
    fun productionDownloadListenerProducesNormalizedRequest() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val received = AtomicReference<DownloadRequest?>()
        val invoked = CountDownLatch(1)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var webView: android.webkit.WebView

        try {
            scenario.onActivity { activity ->
                webView = createWebView(
                    context = activity,
                    tabId = TabId("download-tab"),
                    profileId = ProfileId("default"),
                    onDownloadRequested = { request ->
                        received.set(request)
                        invoked.countDown()
                    },
                    onNavigationRequest = { NavigationDecision.Allow },
                    onNavigationDecision = {},
                    onRendererGone = {},
                    observe = { _, _, _, _, _, _ -> },
                )
                activity.setContentView(webView)
                webView.loadDataWithBaseURL(
                    "https://veyra.invalid/start",
                    DOWNLOAD_PAGE_HTML,
                    "text/html",
                    null,
                    null,
                )
            }

            // Wait for a real attached WebView document before sending a physical-style tap.
            val documentReady = CountDownLatch(1)
            val deadline = System.currentTimeMillis() + 10_000
            while (documentReady.count > 0L && System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    webView.evaluateJavascript("document.getElementById('file') ? 'ready' : ''") { result ->
                        if (result.contains("ready")) documentReady.countDown()
                    }
                }
                documentReady.await(250, TimeUnit.MILLISECONDS)
            }
            assertTrue("Test document did not become ready", documentReady.count == 0L)

            instrumentation.runOnMainSync {
                val location = IntArray(2)
                webView.getLocationOnScreen(location)
                val x = location[0] + 40f
                val y = location[1] + 28f
                val downTime = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(
                    downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0,
                )
                val up = MotionEvent.obtain(
                    downTime, downTime + 80, MotionEvent.ACTION_UP, x, y, 0,
                )
                instrumentation.sendPointerSync(down)
                instrumentation.sendPointerSync(up)
                down.recycle()
                up.recycle()
            }

            assertTrue(
                "Production DownloadListener was not invoked after a real WebView tap",
                invoked.await(10, TimeUnit.SECONDS),
            )
            val request = received.get()
            assertTrue("Download callback did not provide a request", request != null)
            assertEquals("https://veyra.invalid/report.pdf", request?.url)
            assertEquals("download-tab", request?.tabId?.value)
            assertEquals("default", request?.profileId?.value)
            assertEquals("report.pdf", request?.suggestedFileName)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun acceptedCandidateCompletesAndLandsInTheDownloadsDirectory() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setBody("veyra download payload")
                .setHeader("Content-Length", "veyra download payload".length.toString()),
        )
        server.start()
        try {
            val controller = newController()
            val handler = DownloadRequestHandler { request -> controller.start(request) }

            val outcome = handler.handle(
                candidate(server.url("/report.pdf").toString(), profileId = ProfileId("private")),
            )
            assertTrue("Expected a started download, got $outcome", outcome is DownloadRequestHandler.Outcome.Started)

            val task = withTimeout(20_000) {
                controller.tasks.first { list -> list.any { it.phase == DownloadPhase.COMPLETED } }
                    .first { it.phase == DownloadPhase.COMPLETED }
            }

            // Private attribution survives the whole pipeline.
            assertEquals("private", task.request.profileId.value)
            val file = File(task.destination!!)
            assertTrue("Downloaded file is missing: ${file.absolutePath}", file.isFile)
            assertEquals("veyra download payload", file.readText())
            assertTrue(
                "File must land inside the app downloads directory",
                file.parentFile?.absolutePath == downloadsRoot.absolutePath,
            )
            controller.close()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun cancellingATransferStopsItAndLeavesNoPartialFile() = runBlocking {
        val server = MockWebServer()
        // Throttle the body so the transfer is reliably in flight when it is cancelled.
        server.enqueue(
            MockResponse()
                .setBody("x".repeat(64 * 1024))
                .throttleBody(8 * 1024, 100, TimeUnit.MILLISECONDS),
        )
        server.start()
        try {
            val controller = newController()
            val handler = DownloadRequestHandler { request -> controller.start(request) }
            handler.handle(candidate(server.url("/big.bin").toString()))

            val running = withTimeout(20_000) {
                controller.tasks.first { list -> list.any { it.phase == DownloadPhase.RUNNING } }
                    .first { it.phase == DownloadPhase.RUNNING }
            }
            controller.cancel(running.id)

            val cancelled = withTimeout(20_000) {
                controller.tasks.first { list -> list.any { it.phase == DownloadPhase.CANCELLED } }
                    .first { it.phase == DownloadPhase.CANCELLED }
            }
            assertEquals(running.id, cancelled.id)
            assertEquals(
                "Cancelled download must not leave a partial file",
                emptyList<File>(),
                downloadsRoot.listFiles()?.toList().orEmpty(),
            )
            controller.close()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun closingTheControllerDuringATransferEndsCancelledWithoutHanging() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setBody("x".repeat(64 * 1024))
                .throttleBody(8 * 1024, 100, TimeUnit.MILLISECONDS),
        )
        server.start()
        try {
            val controller = newController()
            val handler = DownloadRequestHandler { request -> controller.start(request) }
            handler.handle(candidate(server.url("/big.bin").toString()))

            withTimeout(20_000) {
                controller.tasks.first { list -> list.any { it.phase == DownloadPhase.RUNNING } }
            }
            // A hang here fails the test through withTimeout instead of blocking forever.
            withTimeout(20_000) { controller.close() }

            val task = controller.tasks.value.single()
            assertEquals(DownloadPhase.CANCELLED, task.phase)
            assertEquals(
                "Closed transfer must not leave a partial file",
                emptyList<File>(),
                downloadsRoot.listFiles()?.toList().orEmpty(),
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun fileSinkSanitizesNamesAndAbortsLeaveNothingBehind() = runBlocking {
        val factory = AndroidDownloadFileSinkFactory(downloadsRoot)

        val completed = factory.create("report.pdf")
        completed.write("hello".encodeToByteArray())
        val completedLocation = completed.finish()
        assertEquals("hello", File(completedLocation).readText())

        val traversal = factory.create("../../evil name?.zip")
        val traversalLocation = traversal.finish()
        val traversalFile = File(traversalLocation)
        assertEquals("evil name.zip", traversalFile.name)
        assertEquals(downloadsRoot.absolutePath, traversalFile.parentFile?.absolutePath)

        // A repeated name must not overwrite the earlier file.
        val second = factory.create("report.pdf")
        val secondLocation = second.finish()
        assertTrue("Collision suffix missing", secondLocation != completedLocation)

        val aborted = factory.create("partial.bin")
        aborted.write(byteArrayOf(1, 2, 3))
        aborted.abort()
        assertEquals(
            "Aborted download must not leave a file",
            3,
            downloadsRoot.listFiles()!!.size,
        )
    }

    private fun newController(): DefaultDownloadController = DefaultDownloadController(
        scope = scope,
        transport = createPlatformDownloadTransport()!!,
    )

    private fun candidate(
        url: String,
        profileId: ProfileId = ProfileId("default"),
    ) = DownloadRequest(
        requestId = RequestId("request-1"),
        tabId = TabId("tab-1"),
        profileId = profileId,
        url = url,
        suggestedFileName = url.substringAfterLast('/'),
        mimeType = "application/octet-stream",
        expectedBytes = null,
    )

    private companion object {
        val DOWNLOAD_PAGE_HTML = """
            <!doctype html>
            <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
            <body style="margin: 8px">
            <a id="file" href="https://veyra.invalid/report.pdf" download
               style="display: block; min-height: 48px; padding: 8px">Download report</a>
            </body></html>
        """.trimIndent()
    }
}
