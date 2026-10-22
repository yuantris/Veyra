package dev.veyra.browser.core

import kotlin.jvm.JvmInline

/**
 * Stable identities shared by the browser domain and platform adapters.
 *
 * IDs are opaque: callers must not infer ordering or identity from their string value.
 *
 * `kotlin.jvm.JvmInline` must be imported explicitly in common code: the JVM target has it in
 * its default imports, but native/JS targets do not, which is why these declarations failed to
 * compile for the iOS framework targets before the import was added.
 */
@JvmInline
value class TabId(val value: String)

@JvmInline
value class SessionId(val value: String)

@JvmInline
value class NavigationId(val value: String)

@JvmInline
value class RequestId(val value: String)

@JvmInline
value class ProfileId(val value: String)
