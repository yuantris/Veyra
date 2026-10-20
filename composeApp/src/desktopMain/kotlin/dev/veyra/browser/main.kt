package dev.veyra.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.multiplatform.webview.util.addTempDirectoryRemovalHook
import dev.datlag.kcef.KCEF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun main() = application {
    addTempDirectoryRemovalHook()
    Window(onCloseRequest = ::exitApplication, title = "Veyra") {
        var initialized by remember { mutableStateOf(false) }
        var restartRequired by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf(0f) }
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                KCEF.init(builder = {
                    installDir(AppPaths.cefRuntime)
                    progress {
                        onDownloading { progress = it.coerceAtLeast(0f) }
                        onInitialized { initialized = true }
                    }
                    settings { cachePath = AppPaths.chromiumCache.absolutePath }
                }, onError = { it?.printStackTrace() }, onRestartRequired = { restartRequired = true })
            }
        }
        when {
            restartRequired -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Chromium initialization requires an application restart.")
            }
            initialized -> App()
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                    Text("Preparing Chromium: ${progress.toInt()}%")
                }
            }
        }
        DisposableEffect(Unit) { onDispose { KCEF.disposeBlocking() } }
    }
}
