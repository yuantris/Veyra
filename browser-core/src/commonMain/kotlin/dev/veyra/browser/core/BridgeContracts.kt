package dev.veyra.browser.core

/**
 * Versioned JS/native and extension/native message contracts.
 *
 * Payload is JSON text validated by the receiver. The authorization gate itself lives in
 * `dev.veyra.browser.core.security` (BridgeAuthorizationPolicy).
 */

/** Versioned JS/native or extension/native message. Payload is JSON text validated by the receiver. */
data class BridgeEnvelope(
    val protocol: String = "veyra.bridge",
    val version: Int = 1,
    val id: RequestId,
    val type: BridgeMessageType,
    val method: String,
    val payloadJson: String = "{}",
    val context: BridgeMessageContext,
)

enum class BridgeMessageType {
    REQUEST,
    RESPONSE,
    EVENT,
}

data class BridgeMessageContext(
    val tabId: TabId,
    /** Must be derived from trusted engine/frame metadata, never trusted from page JSON. */
    val origin: String,
    val extensionId: String? = null,
    val frameId: String? = null,
)

sealed interface BridgeResult {
    data class Success(val payloadJson: String = "{}") : BridgeResult
    data class Failure(val code: BridgeErrorCode) : BridgeResult
}

enum class BridgeErrorCode {
    INVALID_MESSAGE,
    UNSUPPORTED_VERSION,
    UNAUTHORIZED_ORIGIN,
    PERMISSION_DENIED,
    METHOD_NOT_FOUND,
    TIMEOUT,
    CANCELLED,
    INTERNAL_ERROR,
}

interface NativeMessageEndpoint {
    suspend fun send(message: BridgeEnvelope): BridgeResult
}
