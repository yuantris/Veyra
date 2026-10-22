package dev.veyra.browser

import dev.veyra.browser.core.DownloadRequest
import dev.veyra.browser.download.DownloadProgress
import dev.veyra.browser.download.DownloadTransport
import dev.veyra.browser.download.DownloadTransportResult
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

/**
 * Platform file destination for one download.
 *
 * Implementations own the actual file I/O and must release the file in every terminal case:
 * [finish] after a complete transfer, [abort] after a failure or cancellation.
 */
interface DownloadFileSink {
    suspend fun write(bytes: ByteArray)

    /** Releases the completed file and returns an opaque platform location. */
    suspend fun finish(): String

    /** Discards a partial file so cancelled or failed downloads leave nothing behind. */
    suspend fun abort()
}

/** Creates the destination file for a download candidate. */
fun interface DownloadFileSinkFactory {
    fun create(suggestedFileName: String?): DownloadFileSink
}

/**
 * Ktor-based [DownloadTransport]: the transfer logic is shared, the HTTP engine and the file
 * destination stay platform-specific.
 *
 * Only a 200 response is a successful transfer; every other status becomes a failed task.
 * Coroutine cancellation aborts the partial file and propagates unchanged so the owning task
 * ends as cancelled rather than failed.
 */
class KtorDownloadTransport(
    private val client: HttpClient,
    private val sinkFactory: DownloadFileSinkFactory,
    private val bufferSize: Long = DEFAULT_BUFFER_SIZE,
) : DownloadTransport {

    override suspend fun download(
        request: DownloadRequest,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): DownloadTransportResult {
        var sink: DownloadFileSink? = null
        return try {
            client.prepareGet(request.url).execute { response ->
                if (response.status != HttpStatusCode.OK) {
                    return@execute DownloadTransportResult.Failed("HTTP_${response.status.value}")
                }
                val totalBytes = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    ?: request.expectedBytes
                val channel: ByteReadChannel = response.bodyAsChannel()
                val fileSink = sinkFactory.create(request.suggestedFileName).also { sink = it }
                var receivedBytes = 0L
                val buffer = ByteArray(bufferSize.toInt())
                while (true) {
                    // Suspends until bytes are available; returns a non-positive count at EOF.
                    val count = channel.readAvailable(buffer)
                    if (count <= 0) break
                    fileSink.write(buffer.copyOf(count))
                    receivedBytes += count
                    onProgress(DownloadProgress(receivedBytes, totalBytes))
                }
                DownloadTransportResult.Completed(fileSink.finish(), receivedBytes, totalBytes)
            }
        } catch (cancelled: CancellationException) {
            sink?.let { runCatching { it.abort() } }
            throw cancelled
        } catch (failure: Exception) {
            sink?.let { runCatching { it.abort() } }
            DownloadTransportResult.Failed(failure.toErrorCode())
        }
    }

    private fun Exception.toErrorCode(): String = when (this) {
        is IOException -> "NETWORK_ERROR"
        else -> "TRANSPORT_ERROR"
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 64L * 1024L
    }
}

/**
 * Platform download execution boundary.
 *
 * Returns null on platforms where downloads are not supported yet; the engine capability model
 * must report the same truth for those platforms.
 */
internal expect fun createPlatformDownloadTransport(): DownloadTransport?
