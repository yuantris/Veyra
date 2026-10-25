package dev.veyra.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.veyra.browser.core.BrowserEvent
import dev.veyra.browser.core.BrowserErrorCategory
import dev.veyra.browser.core.NavigationDecision
import dev.veyra.browser.core.NavigationRequest
import dev.veyra.browser.core.NavigationSource
import dev.veyra.browser.core.TabId
import dev.veyra.browser.core.session.BrowserTab
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@Composable
fun App() {
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MiuixTheme(colors = colors) {
        val c = MiuixTheme.colorScheme
        val uriHandler = LocalUriHandler.current
        val surfaceRenderer = remember { createBrowserSurfaceRenderer() }
        // The composition root assembles the session graph; this file stays pure UI.
        val session = rememberBrowserSession()
        val coordinator = session.coordinator
        val downloadHandler = session.downloadHandler
        var downloadStatus by remember { mutableStateOf<String?>(null) }
        val sessionState by coordinator.state.collectAsState()
        val engines by coordinator.engines.collectAsState()
        val pageStates by coordinator.pageStates.collectAsState()
        var address by remember { mutableStateOf(coordinator.activeTab?.url.orEmpty()) }
        var showBrowser by remember { mutableStateOf(false) }
        val activeTab = sessionState.tabs.firstOrNull { it.id == sessionState.activeTabId }
        val activePageState = activeTab?.let { pageStates[TabId(it.id)] }
        val currentTabIds = sessionState.tabs.map { it.id }.toSet()
        val knownTabIds = remember { mutableSetOf<String>() }

        // Release adapter-owned snapshots when a tab is explicitly closed. Surface detachment
        // alone must not erase its restorable state.
        LaunchedEffect(currentTabIds) {
            (knownTabIds - currentTabIds).forEach(surfaceRenderer::onTabClosed)
            knownTabIds.clear()
            knownTabIds.addAll(currentTabIds)
        }

        // The core coordinator owns startup, tab metadata, engine creation/closure and page projection.
        // Compose only observes state and forwards user actions.
        LaunchedEffect(coordinator) {
            coordinator.start()
        }

        // A platform download callback is a candidate: nothing starts until the app policy accepts it.
        LaunchedEffect(coordinator, downloadHandler) {
            coordinator.events.collect { event ->
                if (event is BrowserEvent.DownloadRequested) {
                    downloadStatus = when (val outcome = downloadHandler.handle(event.request)) {
                        is DownloadRequestHandler.Outcome.Started -> null
                        is DownloadRequestHandler.Outcome.Rejected -> "Download rejected: ${outcome.reason}"
                        is DownloadRequestHandler.Outcome.Failed -> "Download failed: ${outcome.reason}"
                    }
                }
            }
        }

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
            address = target
            session.launchCommand {
                val current = coordinator.activeTab ?: coordinator.openTab(url = "about:blank")
                // The engine/page-state projection is the source of truth for URL, title and
                // loading state. Do not publish the requested URL as if it were already accepted:
                // navigation policy may cancel or route it elsewhere.
                when (coordinator.navigate(NavigationRequest(TabId(current.id), target, NavigationSource.USER))) {
                    NavigationDecision.Allow -> Unit
                    NavigationDecision.Cancel -> coordinator.updateTab(current.id, isLoading = false)
                    is NavigationDecision.OpenExternal,
                    is NavigationDecision.OpenInNewTab -> Unit
                }
            }
        }

        // Android system Back follows browser history first, then returns to the home surface.
        // The platform handler is a no-op on targets without an established system-back contract.
        PlatformBackHandler(enabled = showBrowser) {
            val tab = activeTab
            if (tab?.canGoBack == true) {
                coordinator.goBack(TabId(tab.id))
            } else {
                showBrowser = false
            }
        }

        BrowserScaffold(
            addressBar = {
                AddressBar(
                    address = address,
                    onAddressChange = { address = it },
                    onGo = { openAddress(address) },
                    isBrowsing = showBrowser,
                    isLoading = activeTab?.isLoading == true,
                    onStopOrReload = {
                        activeTab?.let { tab ->
                            if (tab.isLoading) coordinator.stop(TabId(tab.id))
                            else coordinator.reload(TabId(tab.id))
                        }
                    },
                )
            },
            content = { contentModifier ->
                Box(contentModifier.fillMaxSize()) {
                    // A selected surface is presented here. Renderers that can preserve native
                    // navigation state on detach may release background views; renderers without
                    // a verified restoration path opt into the conservative keep-composed fallback.
                    // Surface lifecycle remains separate from BrowserSession/engine ownership.
                    sessionState.tabs.forEach { tab ->
                        val selected = tab.id == sessionState.activeTabId
                        val shouldComposeSurface = selected || !surfaceRenderer.supportsBackgroundSurfaceDetachment
                        val engine = engines[TabId(tab.id)]
                        if (engine != null && shouldComposeSurface) {
                            key(tab.id) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .zIndex(if (selected && showBrowser) 1f else 0f),
                                ) {
                                    surfaceRenderer.Render(
                                        tab = tab,
                                        engine = engine,
                                        isVisible = selected && showBrowser,
                                        onNavigationRequest = coordinator::decideSurfaceNavigation,
                                        onNavigationDecision = { decision ->
                                            when (decision) {
                                                is NavigationDecision.OpenExternal -> {
                                                    runCatching { uriHandler.openUri(decision.url) }
                                                }
                                                is NavigationDecision.OpenInNewTab -> {
                                                    showBrowser = true
                                                    session.launchCommand {
                                                        val newTab = coordinator.openTab(url = "about:blank")
                                                        coordinator.navigate(
                                                            NavigationRequest(
                                                                TabId(newTab.id),
                                                                decision.url,
                                                                NavigationSource.PAGE,
                                                            ),
                                                        )
                                                    }
                                                }
                                                NavigationDecision.Allow,
                                                NavigationDecision.Cancel -> Unit
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (!showBrowser) {
                        Box(Modifier.fillMaxSize().zIndex(2f)) {
                            HomeScreen(onOpen = ::openAddress)
                        }
                    }
                }
            },
            tabStrip = {
                BrowserTabStrip(
                    tabs = sessionState.tabs,
                    activeTabId = sessionState.activeTabId,
                    onSelect = { tabId ->
                        coordinator.selectTab(tabId)
                        showBrowser = true
                    },
                    onNewTab = {
                        showBrowser = true
                        session.launchCommand { coordinator.openTab(url = "about:blank") }
                    },
                    onCloseTab = { tabId ->
                        showBrowser = true
                        session.launchCommand { coordinator.closeTab(tabId) }
                    },
                )
            },
            bottomBar = {
                BottomBar(
                    canGoBack = activeTab?.canGoBack == true,
                    canGoForward = activeTab?.canGoForward == true,
                    tabCount = sessionState.tabs.size,
                    isBrowsing = showBrowser,
                    onBack = { activeTab?.let { coordinator.goBack(TabId(it.id)) } },
                    onForward = { activeTab?.let { coordinator.goForward(TabId(it.id)) } },
                    onTabs = { showBrowser = !showBrowser },
                )
            },
            status = {
                val pageError = activePageState?.error
                if (pageError != null || downloadStatus != null) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (pageError != null) {
                            val message = when (pageError.category) {
                                BrowserErrorCategory.DNS -> "无法解析网站地址"
                                BrowserErrorCategory.CONNECTION,
                                BrowserErrorCategory.OFFLINE -> "无法连接到网站"
                                BrowserErrorCategory.TLS -> "安全连接验证失败"
                                BrowserErrorCategory.TIMEOUT -> "网页连接超时"
                                BrowserErrorCategory.NAVIGATION_BLOCKED,
                                BrowserErrorCategory.UNSUPPORTED_SCHEME -> "此网页地址无法打开"
                                BrowserErrorCategory.CONTENT_PROCESS_CRASHED -> "网页进程意外退出，可尝试重新加载"
                                else -> "网页加载失败"
                            }
                            Text(
                                message,
                                fontSize = 12.sp,
                                color = c.error,
                                modifier = Modifier
                                    .semantics { contentDescription = "网页加载失败" }
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(c.surfaceContainer)
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                        downloadStatus?.let { status ->
                            Text(
                                status,
                                fontSize = 12.sp,
                                color = c.error,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(c.surfaceContainer)
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            },
        )

        DisposableEffect(session) {
            onDispose {
                // Closes the coordinator, then the download controller, then cancels the owner scope.
                session.requestShutdown()
            }
        }
    }
}

@Composable
private fun BrowserTabStrip(
    tabs: List<BrowserTab>,
    activeTabId: String?,
    onSelect: (String) -> Unit,
    onNewTab: () -> Unit,
    onCloseTab: (String) -> Unit,
) {
    val c = MiuixTheme.colorScheme
    val tabScrollState = rememberScrollState()
    // Keep the newest tab's close action visible after opening a tab. Otherwise the
    // semantics node exists off-screen and has zero visible bounds on narrow phones.
    LaunchedEffect(tabs.size) {
        tabScrollState.animateScrollTo(tabScrollState.maxValue)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(tabScrollState),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val selected = tab.id == activeTabId
                val label = tab.title.takeIf { it.isNotBlank() } ?: tab.url.ifBlank { "新标签页" }
                Row(
                    modifier = Modifier
                        .widthIn(min = 128.dp, max = 260.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) c.surfaceContainer else c.surface)
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (selected) c.primary else c.onBackground.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelect(tab.id) }
                            .padding(vertical = 6.dp),
                    )
                    Box(
                        modifier = Modifier
                            .requiredSize(48.dp)
                            .clip(CircleShape)
                            .semantics {
                                contentDescription = "关闭 $label 标签页"
                                role = Role.Button
                            }
                            .clickable(role = Role.Button) { onCloseTab(tab.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "×",
                            color = c.onBackground.copy(alpha = 0.6f),
                            fontSize = 16.sp,
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .requiredSize(48.dp)
                .clip(CircleShape)
                .semantics {
                    contentDescription = "新建标签页"
                    role = Role.Button
                }
                .clickable(role = Role.Button, onClick = onNewTab),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "＋",
                color = c.onBackground,
                fontSize = 18.sp,
            )
        }
    }
}

@Composable
private fun AddressBar(
    address: String,
    onAddressChange: (String) -> Unit,
    onGo: () -> Unit,
    isBrowsing: Boolean,
    isLoading: Boolean,
    onStopOrReload: () -> Unit,
) {
    val c = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🔒", fontSize = 15.sp)
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = address,
            onValueChange = onAddressChange,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "地址栏" },
            singleLine = true,
            textStyle = TextStyle(color = c.onBackground, fontSize = 14.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
            decorationBox = { inner ->
                if (address.isEmpty()) {
                    Text(
                        if (isBrowsing) "Search or enter address" else "搜索或输入网址",
                        color = c.onBackground.copy(alpha = 0.45f),
                        fontSize = 14.sp,
                    )
                }
                inner()
            },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (isLoading) "■" else "↻",
            color = c.onBackground.copy(alpha = if (isBrowsing) 1f else 0.35f),
            fontSize = 18.sp,
            modifier = Modifier
                .semantics {
                    contentDescription = if (isLoading) "停止加载" else "重新加载"
                    role = Role.Button
                }
                .clickable(enabled = isBrowsing, role = Role.Button, onClick = onStopOrReload)
                .padding(4.dp),
        )
    }
}

@Composable
private fun BottomBar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    tabCount: Int,
    isBrowsing: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onTabs: () -> Unit,
) {
    val c = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        BarGlyph("‹", "后退", canGoBack, onBack)
        BarGlyph("›", "前进", canGoForward, onForward)
        BarGlyph(
            text = if (isBrowsing) tabCount.toString() else "＋",
            description = "标签页",
            enabled = true,
            onClick = onTabs,
        )
        BarGlyph("☆", "收藏夹（暂未开放）", enabled = false, onClick = {})
        BarGlyph("≡", "菜单（暂未开放）", enabled = false, onClick = {})
    }
}

@Composable
private fun BarGlyph(
    text: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) c.onBackground else c.onBackground.copy(alpha = 0.3f),
            fontSize = 20.sp,
        )
    }
}

@Composable
private fun HomeScreen(onOpen: (String) -> Unit) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 900.dp)
                .align(Alignment.TopCenter)
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
        Spacer(Modifier.height(8.dp))
        SearchPill(onOpen)
        Spacer(Modifier.height(26.dp))
        SectionTitle("收藏")
        FavoritesRow(onOpen)
        Spacer(Modifier.height(22.dp))
        SectionTitle("建议")
        SuggestionsRow(onOpen)
        Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SearchPill(onOpen: (String) -> Unit) {
    val c = MiuixTheme.colorScheme
    var query by remember { mutableStateOf("") }
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⌕", fontSize = 22.sp, color = c.onBackground.copy(alpha = 0.6f))
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = TextStyle(color = c.onBackground, fontSize = 15.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onOpen(query) }),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text("搜索或输入网址", color = c.onBackground.copy(alpha = 0.45f), fontSize = 15.sp)
                }
                inner()
            },
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    val c = MiuixTheme.colorScheme
    Text(
        title,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = c.onBackground,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

@Composable
private fun FavoritesRow(onOpen: (String) -> Unit) {
    val c = MiuixTheme.colorScheme
    val favorites = listOf(
        Favorite("百", "百度", "https://www.baidu.com"),
        Favorite("G", "Google", "https://www.google.com"),
        Favorite("B", "Bing", "https://www.bing.com"),
        Favorite("Gh", "GitHub", "https://github.com"),
    )
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        favorites.forEach { fav ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onOpen(fav.url) }
                    .padding(8.dp),
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(fav.monogram, color = c.primary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(fav.name, fontSize = 12.sp, color = c.onBackground, maxLines = 1)
            }
        }
    }
}

private data class Favorite(val monogram: String, val name: String, val url: String)

@Composable
private fun SuggestionsRow(onOpen: (String) -> Unit) {
    val c = MiuixTheme.colorScheme
    val suggestions = listOf(
        Suggestion("百", "百度", "https://www.baidu.com"),
        Suggestion("必", "必应", "https://www.bing.com"),
        Suggestion("G", "GitHub", "https://github.com"),
    )
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        suggestions.forEach { s ->
            Column(
                Modifier
                    .widthIn(max = 200.dp)
                    .width(190.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.surface)
                    .clickable { onOpen(s.url) }
                    .padding(14.dp),
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(s.monogram, color = c.primary, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Text(s.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.onBackground, maxLines = 1)
                Text(s.url, fontSize = 11.sp, color = c.onBackground.copy(alpha = 0.5f), maxLines = 1)
            }
        }
    }
}

private data class Suggestion(val monogram: String, val title: String, val url: String)

@Composable
private fun PrivacyReportCard() {
    val c = MiuixTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.surfaceContainer)
            .clickable { /* open privacy report: placeholder */ }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(c.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("🛡", fontSize = 20.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("隐私报告", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.onBackground)
            Spacer(Modifier.height(2.dp))
            Text("最近 7 天已阻止 19 个跟踪器", fontSize = 12.sp, color = c.onBackground.copy(alpha = 0.55f))
        }
        Text("›", fontSize = 18.sp, color = c.onBackground.copy(alpha = 0.5f))
    }
}

@Composable
private fun CustomizeButton() {
    val c = MiuixTheme.colorScheme
    Row(
        Modifier
            .wrapContentWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(c.surface)
            .clickable { /* customize: placeholder */ }
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⚙", fontSize = 15.sp, color = c.onBackground.copy(alpha = 0.7f))
        Spacer(Modifier.width(8.dp))
        Text("自定义", fontSize = 13.sp, color = c.onBackground.copy(alpha = 0.8f))
    }
}
