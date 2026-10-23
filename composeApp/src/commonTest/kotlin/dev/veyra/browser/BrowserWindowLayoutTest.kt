package dev.veyra.browser

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserWindowLayoutTest {

    @Test
    fun compactWindowsUseCompactChrome() {
        assertFalse(isExpandedBrowserWindow(320.dp))
        assertFalse(isExpandedBrowserWindow(600.dp))
        assertFalse(isExpandedBrowserWindow(839.dp))
    }

    @Test
    fun breakpointAndLargerWindowsUseExpandedChrome() {
        assertTrue(isExpandedBrowserWindow(840.dp))
        assertTrue(isExpandedBrowserWindow(1024.dp))
        assertTrue(isExpandedBrowserWindow(1440.dp))
    }
}
