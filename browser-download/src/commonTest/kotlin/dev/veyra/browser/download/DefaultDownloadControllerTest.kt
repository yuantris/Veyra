package dev.veyra.browser.download

import dev.veyra.browser.core.DownloadPhase
import dev.veyra.browser.core.DownloadRequest
import dev.veyra.browser.core.ProfileId
import dev.veyra.browser.core.RequestId
import dev.veyra.browser.core.TabId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultDownloadControllerTest {
    @Test
    fun completesAndPublishesProgress() = runBlocking {
        val controller = DefaultDownloadController(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            DownloadTransport { _, progress ->
                progress(DownloadProgress(4L, 8L))
                DownloadTransportResult.Completed("opaque-location", 8L, 8L)
            },
        )
        val id = controller.start(request())
        val task = controller.tasks.first { list -> list.any { it.id == id && it.phase == DownloadPhase.COMPLETED } }
            .first { it.id == id }
        assertEquals(8L, task.receivedBytes)
        assertEquals("opaque-location", task.destination)
        controller.close()
    }

    @Test
    fun failedTransportPublishesFailure() = runBlocking {
        val controller = DefaultDownloadController(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            DownloadTransport { _, _ -> DownloadTransportResult.Failed("HTTP_503") },
        )
        val id = controller.start(request())
        val task = controller.tasks.first { list -> list.any { it.id == id && it.phase == DownloadPhase.FAILED } }
            .first { it.id == id }
        assertEquals("HTTP_503", task.errorCode)
        controller.close()
    }

    @Test
    fun rejectsUnsupportedSchemes() = runBlocking {
        val controller = DefaultDownloadController(
            CoroutineScope(SupervisorJob()),
            DownloadTransport { _, _ -> DownloadTransportResult.Completed() },
        )
        assertFailsWith<IllegalArgumentException> {
            runBlocking { controller.start(request().copy(url = "file:///private/data")) }
        }
        assertEquals(emptyList(), controller.tasks.value)
        controller.close()
    }

    @Test
    fun duplicateRequestIdReusesTaskWithoutRunningTransportAgain() = runBlocking {
        var transportRuns = 0
        val controller = DefaultDownloadController(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            DownloadTransport { _, _ ->
                transportRuns++
                DownloadTransportResult.Completed()
            },
        )
        val first = controller.start(request())
        val second = controller.start(request())
        assertEquals(first, second)
        assertEquals(1, transportRuns)
        // A different payload under the same request ID is a caller bug, not a duplicate.
        assertFailsWith<IllegalArgumentException> {
            runBlocking { controller.start(request().copy(url = "https://example.com/other.zip")) }
        }
        controller.close()
    }

    @Test
    fun cancelRunningTaskStopsTransportAndMarksTaskCancelled() = runBlocking {
        val transportEntered = CompletableDeferred<Unit>()
        val releaseTransport = CompletableDeferred<Unit>()
        val controller = DefaultDownloadController(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            DownloadTransport { _, _ ->
                transportEntered.complete(Unit)
                releaseTransport.await()
                DownloadTransportResult.Completed()
            },
        )
        val id = controller.start(request())
        transportEntered.await()

        controller.cancel(id)
        assertEquals(DownloadPhase.CANCELLED, controller.tasks.value.single().phase)
        // Cancelling a terminal task is a no-op; an unknown ID is a caller error.
        controller.cancel(id)
        assertFailsWith<IllegalArgumentException> {
            runBlocking { controller.cancel("download-999") }
        }
        releaseTransport.complete(Unit)
        controller.close()
    }

    @Test
    fun cancelQueuedTaskPreventsTransportFromRunning() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var transportRuns = 0
        val controller = DefaultDownloadController(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            DownloadTransport { _, _ ->
                transportRuns++
                firstEntered.complete(Unit)
                releaseFirst.await()
                DownloadTransportResult.Completed()
            },
            maxConcurrentDownloads = 1,
        )
        val first = controller.start(request())
        firstEntered.await()
        // The single slot is held by the first download, so this task stays queued.
        val queued = controller.start(request().copy(requestId = RequestId("request-2")))
        controller.cancel(queued)
        releaseFirst.complete(Unit)
        controller.tasks.first { tasks -> tasks.first { it.id == first }.phase == DownloadPhase.COMPLETED }

        assertEquals(1, transportRuns)
        assertEquals(DownloadPhase.CANCELLED, controller.tasks.value.first { it.id == queued }.phase)
        controller.close()
    }

    @Test
    fun closeWhileTransferIsInFlightLeavesTaskCancelledAndRejectsLaterStarts() = runBlocking {
        val transportEntered = CompletableDeferred<Unit>()
        // The controller shares the test scope so the in-flight transfer parks on the test event
        // loop; that makes "close waits for the transfer" deterministically observable.
        val controller = DefaultDownloadController(
            this,
            DownloadTransport { _, _ ->
                transportEntered.complete(Unit)
                // Never completes on its own: only controller close/cancel can end this transfer.
                awaitCancellation()
            },
        )
        val id = controller.start(request())
        transportEntered.await()

        val closing = async { controller.close() }
        yield()
        // Close must wait for the in-flight transfer instead of abandoning it silently.
        assertFalse(closing.isCompleted)

        closing.await()
        assertEquals(DownloadPhase.CANCELLED, controller.tasks.value.single { it.id == id }.phase)
        val rejected = assertFailsWith<IllegalStateException> {
            runBlocking { controller.start(request().copy(requestId = RequestId("request-2"))) }
        }
        assertTrue(rejected.message!!.contains("Download controller is closed"))
    }

    @Test
    fun transportCancellationMarksTaskCancelled() = runBlocking {
        val controller = DefaultDownloadController(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            DownloadTransport { _, _ -> throw CancellationException("transport cancelled") },
        )
        val id = controller.start(request())

        // Cancellation is not a transport failure: the task ends cancelled and stays observable.
        assertEquals(DownloadPhase.CANCELLED, controller.tasks.value.first { it.id == id }.phase)
        controller.close()
    }

    private fun request() = DownloadRequest(
        requestId = RequestId("request-1"),
        tabId = TabId("tab-1"),
        profileId = ProfileId("default"),
        url = "https://example.com/file.zip",
        expectedBytes = 8L,
    )
}
