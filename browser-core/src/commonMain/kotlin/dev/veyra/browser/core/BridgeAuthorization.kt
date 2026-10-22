package dev.veyra.browser.core

/**
 * Canonical HTTP(S) origin. It deliberately excludes path, query, fragment and credentials.
 * Instances must be created from the engine-provided security origin, not from page JSON.
 */
data class BrowserOrigin(
    val scheme: String,
    val host: String,
    val port: Int,
) {
    init {
        require(scheme == "http" || scheme == "https")
        require(host == host.lowercase()) { "Origin host must be canonical lowercase." }
        require(isValidHost(host)) { "Origin host is malformed." }
        require(port in 1..65535)
    }

    companion object {
        private val DNS_HOST_PATTERN = Regex(
            "(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(?:\\.(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?))*",
        )
        private val IPV6_HOST_PATTERN = Regex("\\[[0-9a-f:.]+]")

        private fun isValidHost(host: String): Boolean =
            if (host.startsWith("[") || host.endsWith("]")) {
                IPV6_HOST_PATTERN.matches(host) && host.contains(':')
            } else {
                host.length <= 253 && DNS_HOST_PATTERN.matches(host)
            }

        fun parse(value: String): BrowserOrigin? {
            if (value.isBlank() || value != value.trim() || value.any { it.isWhitespace() || it.isISOControl() }) {
                return null
            }
            val separator = value.indexOf("://")
            if (separator <= 0) return null
            val scheme = value.substring(0, separator).lowercase()
            if (scheme != "http" && scheme != "https") return null

            val authority = value.substring(separator + 3)
            if (authority.isBlank() || authority.any { it in "/?#@" }) return null

            val host: String
            val explicitPort: String?
            if (authority.startsWith("[")) {
                val closingBracket = authority.indexOf(']')
                if (closingBracket <= 1) return null
                host = authority.substring(0, closingBracket + 1).lowercase()
                val suffix = authority.substring(closingBracket + 1)
                if (suffix.isEmpty()) {
                    explicitPort = null
                } else {
                    if (!suffix.startsWith(":") || suffix.length == 1) return null
                    explicitPort = suffix.substring(1)
                }
            } else {
                if (authority.count { it == ':' } > 1) return null
                val colon = authority.lastIndexOf(':')
                if (colon >= 0) {
                    host = authority.substring(0, colon).lowercase()
                    explicitPort = authority.substring(colon + 1)
                } else {
                    host = authority.lowercase()
                    explicitPort = null
                }
            }

            if (!isValidHost(host)) return null

            val port = if (explicitPort == null) {
                if (scheme == "https") 443 else 80
            } else {
                if (explicitPort.any { !it.isDigit() }) return null
                explicitPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            }
            return BrowserOrigin(scheme, host, port)
        }
    }
}

/** The identity and security origin reported by the native engine adapter for one bridge call. */
data class BridgeCallerContext(
    val tabId: TabId,
    val profileId: ProfileId,
    val documentOrigin: BrowserOrigin?,
    val frameOrigin: BrowserOrigin?,
    val isMainFrame: Boolean,
    val extensionId: String? = null,
)

/** Untrusted payload supplied by JavaScript. It intentionally contains no trusted origin field. */
data class BridgeMessage(
    val protocolVersion: Int,
    val requestId: String,
    val method: String,
    val payload: String,
)

/** Explicit, machine-readable rejection reasons; rejected calls must never reach a native handler. */
enum class BridgeRejection {
    UNSUPPORTED_PROTOCOL,
    INVALID_REQUEST_ID,
    INVALID_METHOD,
    PAYLOAD_TOO_LARGE,
    SUBFRAME_NOT_ALLOWED,
    MISSING_ORIGIN,
    ORIGIN_MISMATCH,
    ORIGIN_NOT_ALLOWED,
    PROFILE_NOT_ALLOWED,
    EXTENSION_NOT_ALLOWED,
}

sealed interface BridgeAuthorizationResult {
    data object Allowed : BridgeAuthorizationResult
    data class Rejected(val reason: BridgeRejection) : BridgeAuthorizationResult
}

/**
 * Platform-neutral pre-dispatch gate for native messaging.
 *
 * The adapter must obtain caller origins from the engine's security callback and pass them in
 * [BridgeCallerContext]. Never construct that context from fields inside [BridgeMessage].
 * This class authorizes a call; it does not parse/execute the payload or grant website permissions.
 */
class BridgeAuthorizationPolicy(
    private val allowedOrigins: Set<BrowserOrigin>,
    private val allowedMethods: Set<String>,
    private val allowedProfiles: Set<ProfileId>,
    private val allowedExtensionIds: Set<String> = emptySet(),
    private val protocolVersion: Int = 1,
    private val maxPayloadBytes: Int = 64 * 1024,
) {
    init {
        require(protocolVersion > 0)
        require(maxPayloadBytes > 0)
        require(allowedMethods.all(::isValidMethodName))
        require(allowedExtensionIds.none(String::isBlank))
    }

    fun authorize(
        caller: BridgeCallerContext,
        message: BridgeMessage,
    ): BridgeAuthorizationResult {
        if (message.protocolVersion != protocolVersion) {
            return BridgeAuthorizationResult.Rejected(BridgeRejection.UNSUPPORTED_PROTOCOL)
        }
        if (message.requestId.isBlank() || message.requestId.length > 128 ||
            message.requestId.any { it.isISOControl() }
        ) {
            return BridgeAuthorizationResult.Rejected(BridgeRejection.INVALID_REQUEST_ID)
        }
        if (!isValidMethodName(message.method) || message.method !in allowedMethods) {
            return BridgeAuthorizationResult.Rejected(BridgeRejection.INVALID_METHOD)
        }
        if (message.payload.encodeToByteArray().size > maxPayloadBytes) {
            return BridgeAuthorizationResult.Rejected(BridgeRejection.PAYLOAD_TOO_LARGE)
        }
        if (!caller.isMainFrame) {
            return BridgeAuthorizationResult.Rejected(BridgeRejection.SUBFRAME_NOT_ALLOWED)
        }

        val documentOrigin = caller.documentOrigin
            ?: return BridgeAuthorizationResult.Rejected(BridgeRejection.MISSING_ORIGIN)
        val frameOrigin = caller.frameOrigin
            ?: return BridgeAuthorizationResult.Rejected(BridgeRejection.MISSING_ORIGIN)
        if (documentOrigin != frameOrigin) {
            return BridgeAuthorizationResult.Rejected(BridgeRejection.ORIGIN_MISMATCH)
        }
        if (documentOrigin !in allowedOrigins) {
            return BridgeAuthorizationResult.Rejected(BridgeRejection.ORIGIN_NOT_ALLOWED)
        }
        if (caller.profileId !in allowedProfiles) {
            return BridgeAuthorizationResult.Rejected(BridgeRejection.PROFILE_NOT_ALLOWED)
        }
        if (caller.extensionId != null && caller.extensionId !in allowedExtensionIds) {
            return BridgeAuthorizationResult.Rejected(BridgeRejection.EXTENSION_NOT_ALLOWED)
        }
        return BridgeAuthorizationResult.Allowed
    }

    private companion object {
        val METHOD_PATTERN = Regex("[a-zA-Z][a-zA-Z0-9_.:-]{0,127}")

        fun isValidMethodName(value: String): Boolean = METHOD_PATTERN.matches(value)
    }
}
