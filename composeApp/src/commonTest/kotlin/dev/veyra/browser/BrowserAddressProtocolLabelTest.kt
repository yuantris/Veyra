package dev.veyra.browser

import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserAddressProtocolLabelTest {

    @Test
    fun labelsOnlyTheUrlSchemeAndDoesNotClaimTlsWasVerified() {
        assertEquals("HTTPS", browserAddressProtocolLabel("https://example.com"))
        assertEquals("HTTPS", browserAddressProtocolLabel("HTTPS://example.com/path"))
        assertEquals("HTTP", browserAddressProtocolLabel("http://example.com"))
        assertEquals("", browserAddressProtocolLabel(""))
        assertEquals("", browserAddressProtocolLabel("about:blank"))
        assertEquals("", browserAddressProtocolLabel("data:text/html,test"))
    }
}
