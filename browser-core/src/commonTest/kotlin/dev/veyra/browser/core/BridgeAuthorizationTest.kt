package dev.veyra.browser.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BridgeAuthorizationTest {
    private val origin = requireNotNull(BrowserOrigin.parse("https://example.com"))
    private val profile = ProfileId("regular")
    private val policy = BridgeAuthorizationPolicy(
        allowedOrigins = setOf(origin),
        allowedMethods = setOf("storage.read", "tabs.get"),
        allowedProfiles = setOf(profile),
        allowedExtensionIds = setOf("trusted-extension"),
        maxPayloadBytes = 16,
    )

    private fun caller(
        documentOrigin: BrowserOrigin? = origin,
        frameOrigin: BrowserOrigin? = origin,
        isMainFrame: Boolean = true,
        profileId: ProfileId = profile,
        extensionId: String? = null,
    ) = BridgeCallerContext(
        tabId = TabId("tab-1"),
        profileId = profileId,
        documentOrigin = documentOrigin,
        frameOrigin = frameOrigin,
        isMainFrame = isMainFrame,
        extensionId = extensionId,
    )

    private fun message(
        protocolVersion: Int = 1,
        requestId: String = "request-1",
        method: String = "storage.read",
        payload: String = "{}",
    ) = BridgeMessage(protocolVersion, requestId, method, payload)

    @Test
    fun allowsOnlyConfiguredOriginProfileAndMethod() {
        assertEquals(BridgeAuthorizationResult.Allowed, policy.authorize(caller(), message()))
        assertEquals(
            BridgeAuthorizationResult.Rejected(BridgeRejection.ORIGIN_NOT_ALLOWED),
            policy.authorize(
                caller(
                    documentOrigin = requireNotNull(BrowserOrigin.parse("https://sub.example.com")),
                    frameOrigin = requireNotNull(BrowserOrigin.parse("https://sub.example.com")),
                ),
                message(),
            ),
        )
        assertEquals(
            BridgeAuthorizationResult.Rejected(BridgeRejection.PROFILE_NOT_ALLOWED),
            policy.authorize(caller(profileId = ProfileId("private")), message()),
        )
        assertEquals(
            BridgeAuthorizationResult.Rejected(BridgeRejection.INVALID_METHOD),
            policy.authorize(caller(), message(method = "storage.delete")),
        )
    }

    @Test
    fun rejectsForgedOrUnavailableFrameOriginAndSubframes() {
        assertEquals(
            BridgeAuthorizationResult.Rejected(BridgeRejection.MISSING_ORIGIN),
            policy.authorize(caller(documentOrigin = null), message()),
        )
        assertEquals(
            BridgeAuthorizationResult.Rejected(BridgeRejection.ORIGIN_MISMATCH),
            policy.authorize(caller(frameOrigin = requireNotNull(BrowserOrigin.parse("https://evil.example"))), message()),
        )
        assertEquals(
            BridgeAuthorizationResult.Rejected(BridgeRejection.SUBFRAME_NOT_ALLOWED),
            policy.authorize(caller(isMainFrame = false), message()),
        )
    }

    @Test
    fun rejectsBadProtocolRequestIdsAndOversizedPayloads() {
        assertEquals(
            BridgeAuthorizationResult.Rejected(BridgeRejection.UNSUPPORTED_PROTOCOL),
            policy.authorize(caller(), message(protocolVersion = 2)),
        )
        assertEquals(
            BridgeAuthorizationResult.Rejected(BridgeRejection.INVALID_REQUEST_ID),
            policy.authorize(caller(), message(requestId = "bad\nrequest")),
        )
        assertEquals(
            BridgeAuthorizationResult.Rejected(BridgeRejection.PAYLOAD_TOO_LARGE),
            policy.authorize(caller(), message(payload = "x".repeat(17))),
        )
    }

    @Test
    fun rejectsUnapprovedExtensionIdentity() {
        assertEquals(
            BridgeAuthorizationResult.Rejected(BridgeRejection.EXTENSION_NOT_ALLOWED),
            policy.authorize(caller(extensionId = "untrusted-extension"), message()),
        )
        assertEquals(
            BridgeAuthorizationResult.Allowed,
            policy.authorize(caller(extensionId = "trusted-extension"), message()),
        )
    }

    @Test
    fun originParsingNormalizesDefaultPortsAndRejectsNonWebOrCredentialOrigins() {
        assertEquals(origin, BrowserOrigin.parse("HTTPS://Example.Com:443"))
        assertEquals(
            BrowserOrigin("http", "example.com", 80),
            BrowserOrigin.parse("http://example.com"),
        )
        listOf(
            "javascript:alert(1)",
            "https://user@example.com",
            "https://example.com/path",
            "https://example.com?query",
            "https://example.com#fragment",
            "https://example.com:",
            "https:///missing-host",
            "https://example.com:99999",
        ).forEach { value ->
            assertEquals(null, BrowserOrigin.parse(value), value)
        }
    }
}
