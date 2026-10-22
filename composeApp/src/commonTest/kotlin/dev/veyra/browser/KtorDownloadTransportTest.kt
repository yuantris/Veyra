package dev.veyra.browser

import dev.veyra.browser.core.DownloadRequest
import dev.veyra.browser.core.ProfileId
import dev.veyra.browser.core.RequestId
import dev.veyra.browser.core.TabId
import dev.veyra.browser.download.DownloadProgress
import dev.veyra.browser.download.DownloadTransportResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KtorDownloadTransportTest {

    @Test
    fun streamsBodyToSinkAndReportsProgress() = runBlocking {
        val payload = "veyra download payload"
        val client = HttpClient(MockEngine { request ->
            assertEquals("https://example.com/file.zip", request.url.toString())
            respond(
                content = ByteReadChannel(payload.encodeToByteArray()),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentLength, payload.length.toString()),
            )
        })
        val sinkFactory = RecordingSinkFactory()
        val transport = KtorDownloadTransport(client, sinkFactory)
        val progress = mutableListOf<Long>()

        val result = transport.download(request()) { progress += it.receivedBytes }

        assertIs<DownloadTransportResult.Completed>(result)
        assertEquals(payload.length.toLong(), result.receivedBytes)
        assertEquals(payload.length.toLong(), progress.last())
        assertEquals(payload, sinkFactory.written.toString())
        assertEquals("memory://$payload", result.location)
    }

    @Test
    fun nonOkStatusIsAFailedTaskWithoutCreatingAFile() = runBlocking {
        val client = HttpClient(MockEngine {
            respond(
                content = ByteReadChannel(ByteArray(0)),
                status = HttpStatusCode.NotFound,
            )
        })
        val sinkFactory = RecordingSinkFactory()
        val transport = KtorDownloadTransport(client, sinkFactory)

        val result = transport.download(request()) { }

        assertIs<DownloadTransportResult.Failed>(result)
        assertEquals("HTTP_404", result.errorCode)
        assertTrue(sinkFactory.created.isEmpty())
    }

    @Test
    fun writeFailureAbortsThePartialFile() = runBlocking {
        val client = HttpClient(MockEngine {
            respond(content = ByteReadChannel("partial".encodeToByteArray()), status = HttpStatusCode.OK)
        })
        val sinkFactory = RecordingSinkFactory(failOnWrite = true)
        val transport = KtorDownloadTransport(client, sinkFactory)

        val result = transport.download(request()) { }

        assertIs<DownloadTransportResult.Failed>(result)
        assertTrue(sinkFactory.aborted)
    }

    @Test
    fun cancellationAbortsThePartialFile() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val sinkFactory = ParkingSinkFactory(entered)
        val client = HttpClient(MockEngine {
            respond(content = ByteReadChannel("partial".encodeToByteArray()), status = HttpStatusCode.OK)
        })
        val transport = KtorDownloadTransport(client, sinkFactory)

        val transfer = launch { transport.download(request()) { } }
        entered.await()
        transfer.cancelAndJoin()

        assertTrue(sinkFactory.aborted)
        assertTrue(transfer.isCancelled)
    }

    private fun request() = DownloadRequest(
        requestId = RequestId("request-1"),
        tabId = TabId("tab-1"),
        profileId = ProfileId("default"),
        url = "https://example.com/file.zip",
        suggestedFileName = "file.zip",
        expectedBytes = 20L,
    )
}

private class RecordingSinkFactory(private val failOnWrite: Boolean = false) : DownloadFileSinkFactory {
    val created = mutableListOf<String>()
    val written = StringBuilder()
    var aborted = false
        private set

    override fun create(suggestedFileName: String?): DownloadFileSink {
        created += (suggestedFileName ?: "download")
        return object : DownloadFileSink {
            override suspend fun write(bytes: ByteArray) {
                if (failOnWrite) throw IOException("disk full")
                written.append(bytes.decodeToString())
            }

            override suspend fun finish(): String = "memory://$written"

            override suspend fun abort() {
                aborted = true
            }
        }
    }
}

/** A sink that parks inside write() so a test can cancel the transfer mid-stream. */
private class ParkingSinkFactory(private val entered: CompletableDeferred<Unit>) : DownloadFileSinkFactory {
    var aborted = false
        private set

    override fun create(suggestedFileName: String?): DownloadFileSink = object : DownloadFileSink {
        override suspend fun write(bytes: ByteArray) {
            entered.complete(Unit)
            awaitCancellation()
        }

        override suspend fun finish(): String = "unused"

        override suspend fun abort() {
            aborted = true
        }
    }
}
