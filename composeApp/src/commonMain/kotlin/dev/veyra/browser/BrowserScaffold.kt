package dev.veyra.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Scaffold

/**
 * Responsive browser chrome shared by compact and expanded windows.
 *
 * This composable owns layout only. Session state, navigation, engine creation and tab lifecycle
 * remain outside and are supplied through slots. Window resizing therefore changes placement,
 * not the lifetime of the browser session or native rendering engine.
 */
@Composable
internal fun BrowserScaffold(
    addressBar: @Composable () -> Unit,
    content: @Composable (Modifier) -> Unit,
    bottomBar: @Composable () -> Unit,
    tabStrip: (@Composable () -> Unit)? = null,
    status: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.systemBars,
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            // Breakpoints use available window constraints rather than device models/orientation.
            val expanded = isExpandedBrowserWindow(maxWidth)
            val chromeHorizontalPadding = if (expanded) 24.dp else 12.dp

            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = chromeHorizontalPadding,
                                vertical = if (expanded) 12.dp else 8.dp,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.fillMaxWidth().widthIn(max = 1200.dp)) {
                            addressBar()
                        }
                    }

                    if (expanded && tabStrip != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = chromeHorizontalPadding)
                                .padding(bottom = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.fillMaxWidth().widthIn(max = 1200.dp)) {
                                tabStrip()
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .then(if (expanded) Modifier.padding(horizontal = 24.dp) else Modifier),
                    ) {
                        content(Modifier.fillMaxSize())
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = chromeHorizontalPadding,
                                vertical = if (expanded) 12.dp else 8.dp,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.fillMaxWidth().widthIn(max = if (expanded) 720.dp else 560.dp)) {
                            bottomBar()
                        }
                    }
                }

                if (status != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = if (expanded) 76.dp else 72.dp),
                    ) {
                        status()
                    }
                }
            }
        }
    }
}


/** True when the available window width should use the expanded browser chrome. */
internal fun isExpandedBrowserWindow(maxWidth: androidx.compose.ui.unit.Dp): Boolean =
    maxWidth >= 840.dp
