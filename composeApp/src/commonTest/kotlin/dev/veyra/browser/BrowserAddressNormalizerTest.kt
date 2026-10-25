package dev.veyra.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrowserAddressNormalizerTest {

    @Test
    fun blankInputIsIgnored() {
        assertNull(normalizeBrowserAddress("  \n "))
    }

    @Test
    fun explicitHttpUrlsArePreserved() {
        assertEquals(
            "https://example.com/path?q=a&b=c",
            normalizeBrowserAddress("  https://example.com/path?q=a&b=c  "),
        )
        assertEquals("http://example.com", normalizeBrowserAddress("http://example.com"))
    }

    @Test
    fun bareDomainsReceiveHttpsScheme() {
        assertEquals("https://example.com", normalizeBrowserAddress("example.com"))
    }

    @Test
    fun searchTextIsEncodedAsOneQueryComponent() {
        assertEquals(
            "https://www.baidu.com/search?q=C%2B%2B%20%26%20Kotlin",
            normalizeBrowserAddress("C++ & Kotlin"),
        )
        assertEquals(
            "https://www.baidu.com/search?q=%E6%B5%8B%E8%AF%95%20%23%20Veyra",
            normalizeBrowserAddress("测试 # Veyra"),
        )
    }
}
