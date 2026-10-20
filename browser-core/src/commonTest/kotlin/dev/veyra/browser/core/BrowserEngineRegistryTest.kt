package dev.veyra.browser.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BrowserEngineRegistryTest {
    @Test
    fun detachingSurfaceRetainsEngineUntilTabIsClosed() {
        val registry = BrowserEngineRegistry()
        val tabId = TabId("tab-1")
        val engine = FakeBrowserEngine()

        registry.register(tabId, engine)
        registry.detachSurface(tabId)

        assertSame(engine, registry.get(tabId))
        assertEquals(0, engine.disposeCount)

        assertTrue(registry.closeTab(tabId))
        assertNull(registry.get(tabId))
        assertEquals(1, engine.disposeCount)
    }

    @Test
    fun registeringSameInstanceIsIdempotent() {
        val registry = BrowserEngineRegistry()
        val tabId = TabId("tab-1")
        val engine = FakeBrowserEngine()

        registry.register(tabId, engine)
        registry.register(tabId, engine)

        assertSame(engine, registry.get(tabId))
        assertEquals(0, engine.disposeCount)
        assertEquals(1, registry.size)
    }

    @Test
    fun replacingEngineDisposesPreviousInstance() {
        val registry = BrowserEngineRegistry()
        val tabId = TabId("tab-1")
        val first = FakeBrowserEngine()
        val second = FakeBrowserEngine()

        registry.register(tabId, first)
        registry.register(tabId, second)

        assertEquals(1, first.disposeCount)
        assertSame(second, registry.get(tabId))
        assertEquals(0, second.disposeCount)
    }

    @Test
    fun closingTabIsIdempotentAndDoesNotTouchOtherTabs() {
        val registry = BrowserEngineRegistry()
        val firstTab = TabId("tab-1")
        val secondTab = TabId("tab-2")
        val first = FakeBrowserEngine()
        val second = FakeBrowserEngine()
        registry.register(firstTab, first)
        registry.register(secondTab, second)

        assertTrue(registry.closeTab(firstTab))
        assertFalse(registry.closeTab(firstTab))
        assertEquals(1, first.disposeCount)
        assertEquals(0, second.disposeCount)
        assertSame(second, registry.get(secondTab))
    }

    @Test
    fun closeAllDisposesEveryRegisteredEngine() {
        val registry = BrowserEngineRegistry()
        val first = FakeBrowserEngine()
        val second = FakeBrowserEngine()
        registry.register(TabId("tab-1"), first)
        registry.register(TabId("tab-2"), second)

        registry.closeAll()

        assertEquals(0, registry.size)
        assertEquals(1, first.disposeCount)
        assertEquals(1, second.disposeCount)
    }

    private class FakeBrowserEngine : BrowserEngine {
        private val mutableState = MutableStateFlow(BrowserEngineState())
        override val state: StateFlow<BrowserEngineState> = mutableState
        var disposeCount: Int = 0
            private set

        override fun loadUrl(url: String) = Unit
        override fun goBack() = Unit
        override fun goForward() = Unit
        override fun reload() = Unit
        override fun stopLoading() = Unit
        override fun dispose() {
            disposeCount += 1
        }
    }
}
