package dev.veyra.browser

import androidx.compose.ui.test.hasContentDescription
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A1 phone-interaction regression for the real app composition:
 * primary tab actions must expose >=48dp accessible targets, and closing a newly-created tab
 * must remove that tab without removing the pre-existing tab.
 */
@RunWith(AndroidJUnit4::class)
class AndroidMiuixTabActionsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun tabActionsHaveAccessibleTouchTargetsAndNewTabCanBeClosed() {
        val density = composeRule.activity.resources.displayMetrics.density
        val newTab = composeRule.onNodeWithContentDescription("新建标签页")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching { newTab.fetchSemanticsNode(); true }.getOrDefault(false)
        }
        assertAtLeast48Dp(newTab.fetchSemanticsNode().boundsInRoot.width, density, "new-tab")

        // Bottom navigation controls must also meet the same touch-target baseline.
        listOf("标签页", "收藏夹", "菜单").forEach { description ->
            val node = composeRule.onNodeWithContentDescription(description)
            assertAtLeast48Dp(
                node.fetchSemanticsNode().boundsInRoot.width,
                density,
                description,
            )
        }

        val closeTabs = composeRule.onAllNodes(hasContentDescription("关闭", substring = true))
        composeRule.waitUntil(timeoutMillis = 10_000) {
            closeTabs.fetchSemanticsNodes().isNotEmpty()
        }
        val originalTabCount = closeTabs.fetchSemanticsNodes().size

        newTab.performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            closeTabs.fetchSemanticsNodes().size == originalTabCount + 1
        }

        // Re-query after composition settles; the last close affordance belongs to the new tab.
        composeRule.waitForIdle()
        val closeNodes = closeTabs.fetchSemanticsNodes()
        assertTrue("Expected one close control for each tab", closeNodes.size == originalTabCount + 1)
        val newestClose = composeRule.onAllNodes(hasContentDescription("关闭", substring = true))
            .get(originalTabCount)
        assertAtLeast48Dp(
            newestClose.fetchSemanticsNode().boundsInRoot.width,
            density,
            "tab-close",
        )
        newestClose.performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            closeTabs.fetchSemanticsNodes().size == originalTabCount
        }
        assertTrue("Closing the new tab must leave the original tab present", originalTabCount >= 1)
    }

    @Test
    fun closingSelectedTabReleasesItsRealWebViewAndRestoresPreviousTabSurface() {
        val newTab = composeRule.onNodeWithContentDescription("新建标签页")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching { newTab.fetchSemanticsNode(); true }.getOrDefault(false)
        }

        val originalSurface = findAttachedWebView()
            ?: throw AssertionError("Expected the initial tab's system WebView to be attached")

        newTab.performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                val nodes = composeRule.onAllNodes(hasContentDescription("关闭", substring = true))
                    .fetchSemanticsNodes()
                nodes.size >= 2 && findAttachedWebView() != null &&
                    findAttachedWebView() !== originalSurface
            }.getOrDefault(false)
        }

        val selectedTabSurface = findAttachedWebView()
            ?: throw AssertionError("New tab did not attach its system WebView")
        assertTrue(
            "Selecting a new tab should replace the attached renderer surface",
            selectedTabSurface !== originalSurface,
        )

        val closeNodes = composeRule.onAllNodes(hasContentDescription("关闭", substring = true))
        val closeNewestTab = closeNodes.get(closeNodes.fetchSemanticsNodes().size - 1)
        closeNewestTab.performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            val attached = findAttachedWebView()
            attached != null && attached !== selectedTabSurface
        }
        composeRule.waitForIdle()

        assertTrue(
            "Closing the selected tab must detach its WebView and restore the previous tab's surface",
            findAttachedWebView() !== selectedTabSurface,
        )
    }

    @Test
    fun switchingTabsRestoresTheRealWebViewsBackForwardHistory() {
        val newTab = composeRule.onNodeWithContentDescription("新建标签页")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching { newTab.fetchSemanticsNode(); true }.getOrDefault(false)
        }

        val originalSurface = findAttachedWebView()
            ?: throw AssertionError("Expected the initial tab's system WebView to be attached")
        // Use explicit data: history entries, matching the already-verified native
        // saveState/restoreState test. loadDataWithBaseURL synthesizes a data: history URL,
        // so the supplied base URL is not a reliable value for WebView.url assertions.
        val firstUrl = "data:text/html,%3C!doctype%20html%3E%3Ctitle%3EVeyra%20history%20first%3C/title%3E%3Cp%3Efirst%3C/p%3E"
        val secondUrl = "data:text/html,%3C!doctype%20html%3E%3Ctitle%3EVeyra%20history%20second%3C/title%3E%3Cp%3Esecond%3C/p%3E"
        composeRule.activity.runOnUiThread { originalSurface.loadUrl(firstUrl) }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            attachedWebViewState()?.let {
                it.url == firstUrl && it.title == "Veyra history first"
            } == true
        }

        composeRule.activity.runOnUiThread { originalSurface.loadUrl(secondUrl) }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            attachedWebViewState()?.let {
                it.url == secondUrl &&
                    it.title == "Veyra history second" &&
                    it.canGoBack
            } == true
        }

        newTab.performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            val current = findAttachedWebView()
            current != null && current !== originalSurface
        }
        val selectedTabSurface = findAttachedWebView()
            ?: throw AssertionError("New tab did not attach its system WebView")

        val closeNodes = composeRule.onAllNodes(hasContentDescription("关闭", substring = true))
        composeRule.waitUntil(timeoutMillis = 10_000) {
            closeNodes.fetchSemanticsNodes().size >= 2
        }
        closeNodes.get(closeNodes.fetchSemanticsNodes().size - 1).performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            val current = findAttachedWebView()
            current != null && current !== selectedTabSurface &&
                attachedWebViewState()?.let {
                    it.url == secondUrl &&
                        it.title == "Veyra history second" &&
                        it.canGoBack
                } == true
        }
        assertTrue(
            "Restored tab should keep its real WebView back/forward history after switching and closing another tab",
            attachedWebViewState()?.canGoBack == true,
        )
    }

    private data class AttachedWebViewState(
        val url: String?,
        val title: String?,
        val canGoBack: Boolean,
    )

    private fun attachedWebViewState(): AttachedWebViewState? {
        var state: AttachedWebViewState? = null
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = findAttachedWebViewOnMainThread()
            if (view != null) {
                state = AttachedWebViewState(view.url, view.title, view.canGoBack())
            }
        }
        return state
    }

    private fun findAttachedWebViewOnMainThread(): WebView? {
        var found: WebView? = null
        fun visit(view: View) {
            if (found != null) return
            if (view is WebView) {
                found = view
            } else if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    visit(view.getChildAt(index))
                    if (found != null) return
                }
            }
        }
        visit(composeRule.activity.window.decorView)
        return found
    }

    private fun findAttachedWebView(): WebView? {
        var found: WebView? = null
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            found = findAttachedWebViewOnMainThread()
        }
        return found
    }

    private fun assertAtLeast48Dp(widthPx: Float, density: Float, control: String) {
        assertTrue(
            "$control touch target width was ${widthPx / density}dp; expected at least 48dp",
            widthPx >= 48f * density,
        )
    }
}
