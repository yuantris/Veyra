package dev.veyra.browser

import dev.veyra.browser.core.DownloadRequest
import kotlinx.coroutines.CancellationException

/**
 * Routes accepted download candidates into the core download API and reports every outcome.
 *
 * The app owns acceptance ([DownloadPolicy]); the core owns tab/profile ownership validation,
 * capability checks and task state. Failures are reported as outcomes instead of being swallowed,
 * so the composition root can surface them. Coroutine cancellation is never converted to a
 * failure outcome.
 */
class DownloadRequestHandler(
    private val startDownload: suspend (DownloadRequest) -> String?,
) {

    sealed interface Outcome {
        data class Started(val downloadId: String) : Outcome
        data class Rejected(val reason: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    suspend fun handle(request: DownloadRequest): Outcome =
        when (val decision = DownloadPolicy.decide(request)) {
            is DownloadPolicy.Decision.Reject -> Outcome.Rejected(decision.reason)
            is DownloadPolicy.Decision.Accept -> try {
                val downloadId = startDownload(decision.request)
                if (downloadId == null) {
                    Outcome.Failed("The browser session is closing.")
                } else {
                    Outcome.Started(downloadId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // The core rejected ownership, profile or capability for this candidate, or no
                // download controller is wired at the composition root.
                Outcome.Failed(failure.message ?: "The download could not be started.")
            }
        }
}
