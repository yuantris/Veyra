package dev.veyra.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import dev.veyra.browser.core.DownloadTask
import dev.veyra.browser.core.TabId
import dev.veyra.browser.core.session.BrowserSessionCoordinator
import dev.veyra.browser.download.DefaultDownloadController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Everything the composition root wires together, so UI code stays pure presentation.
 *
 * The core owns session/engine lifecycle; this holder only owns the owner scope and the platform
 * pieces injected at assembly time (engine factory, download transport). Shutdown closes the
 * coordinator first, then the download controller, then cancels the owner scope.
 */
class BrowserSession(
    val coordinator: BrowserSessionCoordinator,
    val downloadHandler: DownloadRequestHandler,
    val downloadTasks: State<List<DownloadTask>>,
    private val controllerScope: CoroutineScope,
    private val downloadController: DefaultDownloadController?,
) {

    /** Requests shutdown from a non-suspend context such as Compose dispose. */
    fun requestShutdown() {
        controllerScope.launch {
            try {
                coordinator.close()
            } finally {
                downloadController?.close()
                controllerScope.cancel()
            }
        }
    }

    /**
     * Runs a UI-issued command on the owner scope so it survives recomposition and is cancelled
     * only at shutdown.
     */
    fun launchCommand(block: suspend CoroutineScope.() -> Unit) {
        controllerScope.launch(block = block)
    }

    /** Routes find-in-page through the active tab's surface adapter, never through UI-owned WebView state. */
    fun findInPage(query: String) {
        val tab = coordinator.activeTab ?: return
        val surface = coordinator.engines.value[TabId(tab.id)] as? BrowserSurfaceBinding ?: return
        if (query.isBlank()) surface.clearFindInPage() else surface.findInPage(query)
    }

    fun clearFindInPage() {
        val tab = coordinator.activeTab ?: return
        (coordinator.engines.value[TabId(tab.id)] as? BrowserSurfaceBinding)?.clearFindInPage()
    }
}

/**
 * Assembles the browser session graph for one composition. Platform source sets provide the
 * engine factory and the download transport; core creates and owns the store/controller/
 * coordinator graph so UI code never becomes a lifecycle broker.
 */
@Composable
fun rememberBrowserSession(): BrowserSession {
    // This owner scope outlives the Compose subtree long enough to close native sessions on dispose.
    val controllerScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    // Platform download execution: present only where the platform can actually download.
    val downloadTransport = remember { createPlatformDownloadTransport() }
    val downloadController = remember(downloadTransport) {
        downloadTransport?.let { transport -> DefaultDownloadController(controllerScope, transport) }
    }
    val coordinator = remember(downloadController) {
        BrowserSessionCoordinator.create(
            engineFactory = createPlatformBrowserEngineFactory(),
            scope = controllerScope,
            downloadController = downloadController,
        )
    }
    // The app owns download acceptance; the core owns ownership validation and task state.
    val downloadHandler = remember(coordinator) {
        DownloadRequestHandler { request -> coordinator.startDownload(request) }
    }
    val downloadTasksFlow = coordinator.downloadTasks
    val downloadTasks = remember(downloadTasksFlow) {
        downloadTasksFlow ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    return remember(coordinator) {
        BrowserSession(coordinator, downloadHandler, downloadTasks, controllerScope, downloadController)
    }
}
