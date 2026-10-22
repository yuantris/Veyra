package dev.veyra.browser.download

import dev.veyra.browser.core.DownloadController
import dev.veyra.browser.core.DownloadPhase
import dev.veyra.browser.core.DownloadRequest
import dev.veyra.browser.core.DownloadTask

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Progress reported by a transport. A null total means the server did not provide a length. */
data class DownloadProgress(
    val receivedBytes: Long,
    val totalBytes: Long? = null,
) {
    init {
        require(receivedBytes >= 0) { "Received byte count cannot be negative." }
        require(totalBytes == null || totalBytes >= 0) { "Total byte count cannot be negative." }
    }
}

/** Result of a single transport attempt. The location is an opaque platform-owned identifier. */
sealed interface DownloadTransportResult {
    data class Completed(
        val location: String? = null,
        val receivedBytes: Long? = null,
        val totalBytes: Long? = null,
    ) : DownloadTransportResult

    data class Failed(val errorCode: String) : DownloadTransportResult
}

/**
 * Platform-specific I/O boundary. Implementations own HTTP, redirects, cookies, credentials,
 * file creation and cleanup. They must cooperate with coroutine cancellation.
 */
fun interface DownloadTransport {
    suspend fun download(
        request: DownloadRequest,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): DownloadTransportResult
}

/**
 * Default platform-independent task coordinator.
 *
 * It owns task identity, queue/concurrency, progress snapshots and terminal states; it does not
 * know about browser views or files. Inject a platform DownloadTransport to perform actual I/O.
 */
class DefaultDownloadController(
    private val scope: CoroutineScope,
    private val transport: DownloadTransport,
    maxConcurrentDownloads: Int = 3,
) : DownloadController {
    init {
        require(maxConcurrentDownloads > 0) {
            "At least one concurrent download slot is required."
        }
    }

    private val mutex = Mutex()
    private val slots = Semaphore(maxConcurrentDownloads)
    private val mutableTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    private val jobs = mutableMapOf<String, Job>()
    private var nextId = 1L
    private var closed = false

    override val tasks: StateFlow<List<DownloadTask>> = mutableTasks.asStateFlow()

    override suspend fun start(request: DownloadRequest): String {
        validate(request)
        val pair = mutex.withLock {
            check(!closed) { "Download controller is closed." }

            val duplicate = mutableTasks.value.firstOrNull {
                it.request.requestId == request.requestId
            }
            if (duplicate != null) {
                require(duplicate.request == request) {
                    "A different download already uses request ID ${request.requestId.value}."
                }
                return duplicate.id
            }

            val id = "download-${nextId++}"
            val task = DownloadTask(
                id = id,
                request = request,
                phase = DownloadPhase.QUEUED,
            )
            mutableTasks.value = mutableTasks.value + task
            val job = scope.launch(start = CoroutineStart.LAZY) {
                execute(id, request)
            }
            jobs[id] = job
            id to job
        }
        pair.second.start()
        return pair.first
    }

    override suspend fun cancel(downloadId: String) {
        val job = mutex.withLock {
            val task = mutableTasks.value.firstOrNull { it.id == downloadId }
                ?: throw IllegalArgumentException("Unknown download: $downloadId")
            if (task.phase in terminalPhases) return
            replaceTask(task.copy(phase = DownloadPhase.CANCELLED, errorCode = null))
            jobs.remove(downloadId)
        }
        job?.cancel()
    }

    /** Cancels active work and prevents any further tasks from being accepted. */
    suspend fun close() {
        val activeJobs = mutex.withLock {
            if (closed) return
            closed = true
            mutableTasks.value = mutableTasks.value.map { task ->
                if (task.phase in terminalPhases) task
                else task.copy(phase = DownloadPhase.CANCELLED, errorCode = null)
            }
            jobs.values.toList().also { jobs.clear() }
        }
        activeJobs.forEach(Job::cancel)
        activeJobs.forEach { it.cancelAndJoin() }
    }

    private suspend fun execute(id: String, request: DownloadRequest) {
        try {
            slots.withPermit {
                val shouldRun = mutex.withLock {
                    val task = findTask(id) ?: return@withLock false
                    if (task.phase != DownloadPhase.QUEUED || closed) {
                        false
                    } else {
                        replaceTask(task.copy(phase = DownloadPhase.RUNNING))
                        true
                    }
                }
                if (!shouldRun) return@withPermit

                when (val result = transport.download(request) { progress ->
                    mutex.withLock {
                        val task = findTask(id) ?: return@withLock
                        if (task.phase == DownloadPhase.RUNNING) {
                            replaceTask(
                                task.copy(
                                    receivedBytes = progress.receivedBytes,
                                    totalBytes = progress.totalBytes ?: task.totalBytes,
                                ),
                            )
                        }
                    }
                }) {
                    is DownloadTransportResult.Completed -> mutex.withLock {
                        val task = findTask(id) ?: return@withLock
                        if (task.phase == DownloadPhase.RUNNING) {
                            replaceTask(
                                task.copy(
                                    phase = DownloadPhase.COMPLETED,
                                    receivedBytes = result.receivedBytes ?: task.receivedBytes,
                                    totalBytes = result.totalBytes ?: task.totalBytes,
                                    destination = result.location,
                                    errorCode = null,
                                ),
                            )
                        }
                    }
                    is DownloadTransportResult.Failed -> mutex.withLock {
                        val task = findTask(id) ?: return@withLock
                        if (task.phase == DownloadPhase.RUNNING) {
                            replaceTask(task.copy(phase = DownloadPhase.FAILED, errorCode = result.errorCode))
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            mutex.withLock {
                val task = findTask(id)
                if (task != null && task.phase !in terminalPhases) {
                    replaceTask(task.copy(phase = DownloadPhase.CANCELLED, errorCode = null))
                }
            }
            throw cancelled
        } catch (_: Exception) {
            mutex.withLock {
                val task = findTask(id)
                if (task != null && task.phase == DownloadPhase.RUNNING) {
                    replaceTask(task.copy(phase = DownloadPhase.FAILED, errorCode = "TRANSPORT_ERROR"))
                }
            }
        } finally {
            mutex.withLock { jobs.remove(id) }
        }
    }

    private fun validate(request: DownloadRequest) {
        require(request.requestId.value.isNotBlank()) { "Download request ID must not be blank." }
        require(request.tabId.value.isNotBlank()) { "Download tab ID must not be blank." }
        require(request.profileId.value.isNotBlank()) { "Download profile ID must not be blank." }
        require(request.url.isNotBlank()) { "Download URL must not be blank." }
        val normalizedUrl = request.url.trim().lowercase()
        require(normalizedUrl.startsWith("https://") || normalizedUrl.startsWith("http://")) {
            "Only HTTP and HTTPS download URLs are supported."
        }
        val expectedBytes = request.expectedBytes
        require(expectedBytes == null || expectedBytes >= 0) {
            "Expected byte count cannot be negative."
        }
    }

    private fun findTask(id: String): DownloadTask? = mutableTasks.value.firstOrNull { it.id == id }

    private fun replaceTask(updated: DownloadTask) {
        mutableTasks.value = mutableTasks.value.map { if (it.id == updated.id) updated else it }
    }

    private companion object {
        val terminalPhases = setOf(
            DownloadPhase.COMPLETED,
            DownloadPhase.FAILED,
            DownloadPhase.CANCELLED,
        )
    }
}
