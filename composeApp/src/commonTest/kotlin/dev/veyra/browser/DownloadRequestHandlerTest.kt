package dev.veyra.browser

import dev.veyra.browser.core.DownloadRequest
import dev.veyra.browser.core.ProfileId
import dev.veyra.browser.core.RequestId
import dev.veyra.browser.core.TabId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DownloadRequestHandlerTest {

    @Test
    fun acceptedWebCandidateIsStartedThroughTheCoreApi() = runBlocking {
        val started = mutableListOf<DownloadRequest>()
        val handler = DownloadRequestHandler { request ->
            started += request
            "download-1"
        }

        val outcome = handler.handle(request())

        assertIs<DownloadRequestHandler.Outcome.Started>(outcome)
        assertEquals("download-1", outcome.downloadId)
        assertEquals(listOf(request()), started)
    }

    @Test
    fun nonWebCandidateIsRejectedWithoutCallingTheCoreApi() = runBlocking {
        var calls = 0
        val handler = DownloadRequestHandler { _ ->
            calls++
            "download-1"
        }

        val outcome = handler.handle(request().copy(url = "file:///private/data"))

        assertIs<DownloadRequestHandler.Outcome.Rejected>(outcome)
        assertEquals(0, calls)
    }

    @Test
    fun coreRejectionIsReportedAsFailure() = runBlocking {
        val handler = DownloadRequestHandler { _ ->
            throw IllegalArgumentException("Unknown browser tab: tab-1")
        }

        val outcome = handler.handle(request())

        assertIs<DownloadRequestHandler.Outcome.Failed>(outcome)
        assertTrue(outcome.reason.contains("Unknown browser tab"))
    }

    @Test
    fun closingSessionIsReportedAsFailure() = runBlocking {
        val handler = DownloadRequestHandler { null }

        val outcome = handler.handle(request())

        assertIs<DownloadRequestHandler.Outcome.Failed>(outcome)
        assertTrue(outcome.reason.contains("closing"))
    }

    @Test
    fun cancellationIsNeverConvertedToAFailureOutcome() = runBlocking {
        val handler = DownloadRequestHandler { _ ->
            throw CancellationException("session cancelled")
        }

        var thrown: CancellationException? = null
        try {
            handler.handle(request())
        } catch (cancelled: CancellationException) {
            thrown = cancelled
        }
        assertEquals("session cancelled", thrown?.message)
    }

    private fun request() = DownloadRequest(
        requestId = RequestId("request-1"),
        tabId = TabId("tab-1"),
        profileId = ProfileId("default"),
        url = "https://example.com/file.zip",
    )
}
