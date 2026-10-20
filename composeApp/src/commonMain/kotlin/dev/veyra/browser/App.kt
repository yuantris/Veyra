package dev.veyra.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.veyra.browser.core.BrowserEngine
import dev.veyra.browser.core.BrowserSessionStore
import dev.veyra.browser.core.BrowserTab
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.rememberWebViewNavigator
import com.multiplatform.webview.web.rememberWebViewState

private val Ink = Color(0xFF17202A)
private val Muted = Color(0xFF77818D)
private val Canvas = Color(0xFFF5F7FA)
private val Accent = Color(0xFF496BEB)

@Composable
fun App() {
    val session = remember { BrowserSessionStore() }
    val sessionState by session.state.collectAsState()
    val engines = remember { mutableStateMapOf<String, BrowserEngine>() }
    var address by remember { mutableStateOf(session.activeTab?.url.orEmpty()) }
    var showBrowser by remember { mutableStateOf(false) }
    val activeTab = sessionState.tabs.firstOrNull { it.id == sessionState.activeTabId }

    LaunchedEffect(activeTab?.id, activeTab?.url) {
        if (activeTab != null) address = activeTab.url
    }

    fun normalizeAddress(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        return when {
            value.startsWith("https://", true) || value.startsWith("http://", true) -> value
            value.contains(" ") || !value.contains(".") ->
                "https://www.baidu.com/search?q=" + value.replace(" ", "+")
            else -> "https://$value"
        }
    }

    fun openAddress(raw: String) {
        val target = normalizeAddress(raw) ?: return
        showBrowser = true
        val current = session.activeTab
        if (current == null) {
            session.openTab(url = target, title = target)
        } else if (!engines.containsKey(current.id)) {
            session.updateTab(current.id, url = target, title = target)
        } else {
            engines[current.id]?.loadUrl(target)
            session.updateTab(current.id, url = target, title = target, isLoading = true)
        }
        address = target
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Canvas) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).background(Accent, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Text("V", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Veyra", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        Text("A calmer way to browse", fontSize = 12.sp, color = Muted)
                    }
                    Text("BETA", fontSize = 10.sp, color = Accent, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("⌕", fontSize = 23.sp, color = Muted)
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = address, onValueChange = { address = it }, modifier = Modifier.weight(1f),
                        singleLine = true, textStyle = TextStyle(color = Ink, fontSize = 14.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { openAddress(address) }),
                        decorationBox = { inner ->
                            Box {
                                if (address.isEmpty()) Text("Search or enter address", color = Muted, fontSize = 14.sp)
                                inner()
                            }
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Go", color = Accent, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { openAddress(address) }.padding(4.dp))
                }

                if (showBrowser) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ToolText("‹", activeTab?.canGoBack == true) { engines[sessionState.activeTabId]?.goBack() }
                        ToolText("›", activeTab?.canGoForward == true) { engines[sessionState.activeTabId]?.goForward() }
                        ToolText("↻", true) { engines[sessionState.activeTabId]?.reload() }
                        Spacer(Modifier.weight(1f))
                        ToolText("+", true) {
                            session.openTab()
                            address = "about:blank"
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        sessionState.tabs.forEach { tab ->
                            val selected = tab.id == sessionState.activeTabId
                            Row(
                                Modifier.weight(1f).background(
                                    if (selected) Color.White else Color(0xFFE9EDF3),
                                    RoundedCornerShape(10.dp),
                                ).clickable {
                                    session.selectTab(tab.id)
                                    address = tab.url
                                }.padding(horizontal = 8.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    tab.title.ifBlank { "New tab" }.take(14),
                                    modifier = Modifier.weight(1f),
                                    fontSize = 11.sp,
                                    color = if (selected) Ink else Muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "×",
                                    modifier = Modifier.clickable {
                                        engines.remove(tab.id)
                                        session.closeTab(tab.id)
                                    }.padding(start = 4.dp),
                                    color = Muted,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }

                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        activeTab?.let { tab ->
                            key(tab.id) {
                                BrowserTabHost(
                                    tab = tab,
                                    onEngineReady = { id, engine -> engines[id] = engine },
                                    onEngineDisposed = { id, engine ->
                                        if (engines[id] === engine) engines.remove(id)
                                    },
                                    onStateChanged = { id, url, title, loading, back, forward ->
                                        session.updateTab(
                                            tabId = id,
                                            url = url,
                                            title = title,
                                            isLoading = loading,
                                            canGoBack = back,
                                            canGoForward = forward,
                                        )
                                        if (id == session.state.value.activeTabId && !url.isNullOrBlank()) {
                                            address = url
                                        }
                                        if (!url.isNullOrBlank() && url != "about:blank") {
                                            session.recordVisit(id, url, title.orEmpty())
                                        }
                                    },
                                )
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.height(30.dp))
                    Text("YOUR SPACE ON THE WEB", fontSize = 10.sp, color = Muted, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        QuickLink("G", "Google", "google.com", Modifier.weight(1f)) { openAddress("https://www.google.com") }
                        QuickLink("W", "Wikipedia", "wikipedia.org", Modifier.weight(1f)) { openAddress("https://www.wikipedia.org") }
                        QuickLink("D", "DuckDuckGo", "duckduckgo.com", Modifier.weight(1f)) { openAddress("https://duckduckgo.com") }
                    }
                    Spacer(Modifier.height(28.dp))
                    Text("Built for focus", fontSize = 18.sp, color = Ink, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Veyra is an early cross-platform browser experiment. One shared CMP shell, with a rendering engine chosen per platform.",
                        fontSize = 13.sp, color = Muted, lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(20.dp))
                    Text("Enter a URL or open a site to start browsing.", fontSize = 12.sp, color = Muted)
                }
            }
        }
    }
}

@Composable
private fun BrowserTabHost(
    tab: BrowserTab,
    onEngineReady: (String, BrowserEngine) -> Unit,
    onEngineDisposed: (String, BrowserEngine) -> Unit,
    onStateChanged: (String, String?, String?, Boolean?, Boolean?, Boolean?) -> Unit,
) {
    val webState = rememberWebViewState(tab.url)
    val navigator = rememberWebViewNavigator()
    val engine = remember(navigator) {
        WebViewBrowserEngine(
            load = { navigator.loadUrl(it) },
            back = { navigator.navigateBack() },
            forward = { navigator.navigateForward() },
            refresh = { navigator.reload() },
            stop = { navigator.stopLoading() },
        )
    }
    val engineState by engine.state.collectAsState()

    SideEffect { onEngineReady(tab.id, engine) }
    LaunchedEffect(
        tab.id,
        webState.lastLoadedUrl,
        webState.pageTitle,
        webState.isLoading,
        navigator.canGoBack,
        navigator.canGoForward,
    ) {
        val url = webState.lastLoadedUrl ?: tab.url
        engine.publish(
            url = url,
            title = webState.pageTitle,
            isLoading = webState.isLoading,
            canGoBack = navigator.canGoBack,
            canGoForward = navigator.canGoForward,
        )
        onStateChanged(
            tab.id,
            url,
            webState.pageTitle,
            webState.isLoading,
            navigator.canGoBack,
            navigator.canGoForward,
        )
    }
    DisposableEffect(tab.id, engine) {
        onDispose {
            onEngineDisposed(tab.id, engine)
            engine.dispose()
        }
    }

    WebView(
        state = webState,
        navigator = navigator,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun ToolText(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        Modifier.background(Color.White, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        color = if (enabled) Ink else Muted.copy(alpha = 0.4f),
        fontSize = 20.sp,
    )
}

@Composable
private fun QuickLink(monogram: String, title: String, host: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.background(Color.White, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(12.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Box(Modifier.size(34.dp).background(Accent.copy(alpha = 0.1f), CircleShape), contentAlignment = Alignment.Center) {
            Text(monogram, color = Accent, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Text(title, fontSize = 13.sp, color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(host, fontSize = 10.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
