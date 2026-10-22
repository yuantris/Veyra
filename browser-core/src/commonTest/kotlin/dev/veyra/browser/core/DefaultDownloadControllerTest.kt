package dev.veyra.browser.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DefaultDownloadControllerTest {
    @Test
    fun reportsProgressAndCompletedDestination() = runBlocking {
        val controller = DefaultDownloadController(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            transport = DownloadTransport { _, progress ->
                progress(DownloadProgress(receivedBytes = 5, totalBytes = 10))
                progress(DownloadProgress(receivedBytes = 10, totalBytes = 10))
                DownloadTransportResult.Completed(
                    location = "file-token://download-1",
                    receivedBytes = 10,
                    totalBytes = 10,
                )
            },
        )

        val id = controller.start(request())
        val completed = controller.tasks.first { tasks ->
            tasks.firstOrNull { it.id == id }?.phase == DownloadPhase.COMPLETED
        }.first { it.id == id }

        assertEquals(10L, completed.receivedBytes)
        assertEquals(10L, completed.totalBytes)
        assertEquals("file-token://download-1", completed.destination)
        assertEquals(null, completed.errorCode)
        controller.close()
    }

    @Test
    fun transportFailureBecomesFailedTask() = runBlocking {
        val controller = DefaultDownloadController(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            transport = DownloadTransport { _, _ ->
                DownloadTransportResult.Failed("HTTP_503")
            },
        )

        val id = controller.start(request())
        val failed = controller.tasks.first { tasks ->
            tasks.firstOrNull { it.id == id }?.phase == DownloadPhase.FAILED
        }.first { it.id == id }

        assertEquals("HTTP_503", failed.errorCode)
        controller.close()
    }

    @Test
    fun cancelStopsRunningTransportAndMarksTaskCancelled() = runBlocking {
        val transportStarted = CompletableDeferred<Unit>()
        val controller = DefaultDownloadController(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            transport = DownloadTransport { _, _ ->
                transportStarted.complete(Unit)
                awaitCancellation()
            },
        )

        val id = controller.start(request())
        transportStarted.await()
        controller.cancel(id)

        val task = controller.tasks.value.first { it.id == id }
        assertEquals(DownloadPhase.CANCELLED, task.phase)
        assertFailsWith<IllegalArgumentException> { runBlocking { controller.cancel("missing") } }
        controller.close()
    }

    @Test
    fun duplicateRequestIdDoesNotStartAnotherTask() = runBlocking {
        var transportCalls = 0
        val controller = DefaultDownloadController(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            transport = DownloadTransport { _, _ ->
                transportCalls++
                DownloadTransportResult.Completed()
            },
        )

        val request = request()
        val first = controller.start(request)
        val duplicate = controller.start(request)

        assertEquals(first, duplicate)
        assertEquals(1, controller.tasks.value.size)
        assertEquals(1, transportCalls)
        assertTrue(controller.tasks.value.single().phase in setOf(DownloadPhase.COMPLETED, DownloadPhase.RUNNING))
        assertFailsWith<IllegalArgumentException> {
            runBlocking { controller.start(request.copy(url = "https://different.example/file")) }
        }
        assertNotEquals("", first)
        controller.close()
    }

    @Test
    fun rejectsNonHttpUrlsAndNegativeLengths() = runBlocking {
        val controller = DefaultDownloadController(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            transport = DownloadTransport { _, _ -> DownloadTransportResult.Completed() },
        )

        assertFailsWith<IllegalArgumentException> {
            controller.start(request().copy(url = "file:///private/data"))
        }
        assertFailsWith<IllegalArgumentException> {
            controller.start(request().copy(expectedBytes = -1))
        }
        assertTrue(controller.tasks.value.isEmpty())
        controller.close()
    }

    private fun request() = DownloadRequest(
        requestId = RequestId("request-1"),
        tabId = TabId("tab-1"),
        profileId = ProfileId("default"),
        url = "https://example.com/archive.zip",
        suggestedFileName = "archive.zip",
        expectedBytes = 10,
    )
}
