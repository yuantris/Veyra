package dev.veyra.browser.download

import dev.veyra.browser.core.DownloadPhase
import dev.veyra.browser.core.DownloadRequest
import dev.veyra.browser.core.ProfileId
import dev.veyra.browser.core.RequestId
import dev.veyra.browser.core.TabId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    private fun request() = DownloadRequest(
        requestId = RequestId("request-1"),
        tabId = TabId("tab-1"),
        profileId = ProfileId("default"),
        url = "https://example.com/file.zip",
        expectedBytes = 8L,
    )
}
