package dev.veyra.browser.core.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared, platform-neutral tab/session state.
 *
 * Rendering objects and platform WebView instances stay outside this store.
 * A platform host can keep one BrowserEngine instance per tab and key it by [BrowserTab.id].
 */
class BrowserSessionStore(
    initialUrl: String = "https://www.baidu.com",
) {
    private var nextTabNumber = 2

    private val initialTab = BrowserTab(
        id = "tab-1",
        url = initialUrl,
        title = "New tab",
    )

    private val _state = MutableStateFlow(
        BrowserSessionState(
            tabs = listOf(initialTab),
            activeTabId = initialTab.id,
        ),
    )

    val state: StateFlow<BrowserSessionState> = _state.asStateFlow()

    val activeTab: BrowserTab?
        get() = _state.value.tabs.firstOrNull { it.id == _state.value.activeTabId }

    fun openTab(
        url: String = "about:blank",
        title: String = "New tab",
        isPrivate: Boolean = false,
    ): BrowserTab {
        val tab = BrowserTab(
            id = "tab-${nextTabNumber++}",
            url = url,
            title = title,
            isPrivate = isPrivate,
        )
        _state.value = _state.value.copy(
            tabs = _state.value.tabs + tab,
            activeTabId = tab.id,
        )
        return tab
    }

    fun selectTab(tabId: String): Boolean {
        val current = _state.value
        if (current.tabs.none { it.id == tabId }) return false
        _state.value = current.copy(activeTabId = tabId)
        return true
    }

    fun closeTab(tabId: String): Boolean {
        val current = _state.value
        val index = current.tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return false

        if (current.tabs.size == 1) {
            val replacement = BrowserTab(
                id = "tab-${nextTabNumber++}",
                url = "about:blank",
                title = "New tab",
            )
            _state.value = BrowserSessionState(
                tabs = listOf(replacement),
                activeTabId = replacement.id,
                history = current.history,
                bookmarks = current.bookmarks,
            )
            return true
        }

        val remaining = current.tabs.filterNot { it.id == tabId }
        val activeId = if (current.activeTabId == tabId) {
            remaining[(index - 1).coerceAtLeast(0)].id
        } else {
            current.activeTabId
        }
        _state.value = current.copy(tabs = remaining, activeTabId = activeId)
        return true
    }

    fun updateTab(
        tabId: String,
        url: String? = null,
        title: String? = null,
        isLoading: Boolean? = null,
        canGoBack: Boolean? = null,
        canGoForward: Boolean? = null,
    ): Boolean {
        val current = _state.value
        if (current.tabs.none { it.id == tabId }) return false
        _state.value = current.copy(
            tabs = current.tabs.map { tab ->
                if (tab.id != tabId) tab else tab.copy(
                    url = url ?: tab.url,
                    title = title ?: tab.title,
                    isLoading = isLoading ?: tab.isLoading,
                    canGoBack = canGoBack ?: tab.canGoBack,
                    canGoForward = canGoForward ?: tab.canGoForward,
                )
            },
        )
        return true
    }

    fun recordVisit(tabId: String, url: String, title: String = ""): Boolean {
        val current = _state.value
        val tab = current.tabs.firstOrNull { it.id == tabId } ?: return false
        updateTab(tabId, url = url, title = title.ifBlank { tab.title })
        if (tab.isPrivate || url == "about:blank") return true

        val updatedHistory = buildList {
            add(HistoryEntry(url = url, title = title.ifBlank { url }))
            addAll(current.history.filterNot { it.url == url })
        }
        _state.value = _state.value.copy(history = updatedHistory)
        return true
    }

    fun addBookmark(url: String, title: String = url): Boolean {
        if (url.isBlank() || url == "about:blank") return false
        val current = _state.value
        val bookmark = Bookmark(url = url, title = title.ifBlank { url })
        _state.value = current.copy(
            bookmarks = listOf(bookmark) + current.bookmarks.filterNot { it.url == url },
        )
        return true
    }

    fun removeBookmark(url: String): Boolean {
        val current = _state.value
        val remaining = current.bookmarks.filterNot { it.url == url }
        if (remaining.size == current.bookmarks.size) return false
        _state.value = current.copy(bookmarks = remaining)
        return true
    }

    fun clearHistory() {
        _state.value = _state.value.copy(history = emptyList())
    }
}

data class BrowserSessionState(
    val tabs: List<BrowserTab>,
    val activeTabId: String,
    val history: List<HistoryEntry> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
)

data class BrowserTab(
    val id: String,
    val url: String,
    val title: String,
    val isPrivate: Boolean = false,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
)

data class HistoryEntry(
    val url: String,
    val title: String,
)

data class Bookmark(
    val url: String,
    val title: String,
)
