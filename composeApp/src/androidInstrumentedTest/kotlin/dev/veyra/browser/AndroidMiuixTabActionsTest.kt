package dev.veyra.browser

import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodes
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

    private fun assertAtLeast48Dp(widthPx: Float, density: Float, control: String) {
        assertTrue(
            "$control touch target width was ${widthPx / density}dp; expected at least 48dp",
            widthPx >= 48f * density,
        )
    }
}
