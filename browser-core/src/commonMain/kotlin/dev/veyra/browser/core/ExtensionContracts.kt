package dev.veyra.browser.core

import kotlinx.coroutines.flow.StateFlow

/**
 * Extension contracts.
 *
 * Core defines extension metadata, permissions, install source and lifecycle without importing
 * engine-specific types. There is no extension runtime implementation yet; adapters must report
 * extension capability honestly until one exists and is tested.
 */

data class ExtensionInfo(
    val id: String,
    val name: String,
    val version: String,
    val enabled: Boolean,
    val permissions: Set<String> = emptySet(),
)

enum class ExtensionInstallSource {
    BUILT_IN,
    SIGNED_PACKAGE,
    PLATFORM_STORE,
}

sealed interface ExtensionOperationResult {
    data class Success(val extension: ExtensionInfo) : ExtensionOperationResult
    data class Failure(val code: ExtensionErrorCode, val message: String? = null) : ExtensionOperationResult
}

enum class ExtensionErrorCode {
    UNSUPPORTED,
    INVALID_PACKAGE,
    SIGNATURE_REQUIRED,
    PERMISSION_DENIED,
    NOT_FOUND,
    ENGINE_FAILURE,
}

interface WebExtensionController {
    val installedExtensions: StateFlow<List<ExtensionInfo>>
    suspend fun install(source: ExtensionInstallSource, location: String): ExtensionOperationResult
    suspend fun setEnabled(extensionId: String, enabled: Boolean): ExtensionOperationResult
    suspend fun uninstall(extensionId: String): ExtensionOperationResult
}
