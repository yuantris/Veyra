package dev.veyra.browser

import androidx.compose.runtime.Composable

/** Platform system-back integration for browser navigation. */
@Composable
internal expect fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
)
