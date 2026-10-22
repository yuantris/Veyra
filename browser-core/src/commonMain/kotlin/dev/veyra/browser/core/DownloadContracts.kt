package dev.veyra.browser.core

import kotlinx.coroutines.flow.StateFlow

/**
 * Download contracts owned by browser-core.
 *
 * The task model is shared; execution is platform-specific and lives behind the
 * `:browser-download` module, which implements [DownloadController] with an injected transport.
 * Download state is intentionally independent from navigation/page state.
 */

data class DownloadRequest(
    val requestId: RequestId,
    val tabId: TabId,
    val profileId: ProfileId,
    val url: String,
    val suggestedFileName: String? = null,
    val mimeType: String? = null,
    val expectedBytes: Long? = null,
    val hasUserGesture: Boolean? = null,
)

enum class DownloadPhase {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class DownloadTask(
    val id: String,
    val request: DownloadRequest,
    val phase: DownloadPhase,
    val receivedBytes: Long = 0,
    val totalBytes: Long? = request.expectedBytes,
    val errorCode: String? = null,
    /** Opaque location returned by the platform transport after a successful download. */
    val destination: String? = null,
)

interface DownloadController {
    val tasks: StateFlow<List<DownloadTask>>
    suspend fun start(request: DownloadRequest): String
    suspend fun cancel(downloadId: String)
}
