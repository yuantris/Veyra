package dev.veyra.browser.core.security

import dev.veyra.browser.core.ProfileId
import dev.veyra.browser.core.WebsitePermission
import dev.veyra.browser.core.WebsitePermissionBroker
import dev.veyra.browser.core.WebsitePermissionDecision
import dev.veyra.browser.core.WebsitePermissionRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Outcome of the OS runtime permission layer, as reported by the platform adapter.
 *
 * The adapter owns prompting, time-outs and capability detection; the core only classifies what
 * came back. These outcomes are deliberately distinct from [WebsitePermissionDecision] because an
 * OS-level denial is not a site decision and must never be recorded as one.
 */
sealed interface OsPermissionOutcome {
    /** The OS layer permits the request (already granted, or the user allowed the prompt). */
    data object Granted : OsPermissionOutcome

    /** The user explicitly denied the OS-level prompt. */
    data object Denied : OsPermissionOutcome

    /** The user dismissed the prompt without deciding; the request may be retried later. */
    data object Cancelled : OsPermissionOutcome

    /** The prompt never produced a decision; the request may be retried later. */
    data object TimedOut : OsPermissionOutcome

    /** The platform cannot grant this permission at all. */
    data object Unsupported : OsPermissionOutcome
}

/** Identity of one stored site decision. Decisions are scoped per profile, origin and permission. */
data class WebsitePermissionKey(
    val profileId: ProfileId,
    val origin: BrowserOrigin,
    val permission: WebsitePermission,
)

/**
 * Per-profile website permission site settings.
 *
 * Implementations decide persistence (platform settings store, database, ...). Private profiles
 * use the same interface but must be torn down with [clearProfile] when the session ends, so their
 * decisions cannot outlive the session or leak into another profile.
 */
interface WebsitePermissionStore {
    /** The stored site decision, or null when the site has never been decided (prompt). */
    suspend fun find(
        profileId: ProfileId,
        origin: BrowserOrigin,
        permission: WebsitePermission,
    ): WebsitePermissionDecision?

    /** Persists a user decision. Only [WebsitePermissionDecision.Grant] and Deny are storable. */
    suspend fun record(
        profileId: ProfileId,
        origin: BrowserOrigin,
        permission: WebsitePermission,
        decision: WebsitePermissionDecision,
    )

    /** Removes a stored decision so the next request prompts again. */
    suspend fun revoke(profileId: ProfileId, origin: BrowserOrigin, permission: WebsitePermission)

    /** Drops every decision of one profile; required for ephemeral (private) teardown. */
    suspend fun clearProfile(profileId: ProfileId)

    /** Snapshot of one profile's decisions, for settings surfaces and tests. */
    suspend fun decisionsFor(profileId: ProfileId): Map<WebsitePermissionKey, WebsitePermissionDecision>
}

/** Small in-memory store suitable for app bootstrap and deterministic tests. */
class InMemoryWebsitePermissionStore : WebsitePermissionStore {
    private val mutex = Mutex()
    private val decisions = mutableMapOf<WebsitePermissionKey, WebsitePermissionDecision>()

    override suspend fun find(
        profileId: ProfileId,
        origin: BrowserOrigin,
        permission: WebsitePermission,
    ): WebsitePermissionDecision? = mutex.withLock {
        decisions[WebsitePermissionKey(profileId, origin, permission)]
    }

    override suspend fun record(
        profileId: ProfileId,
        origin: BrowserOrigin,
        permission: WebsitePermission,
        decision: WebsitePermissionDecision,
    ) {
        require(decision == WebsitePermissionDecision.Grant || decision == WebsitePermissionDecision.Deny) {
            "Only Grant or Deny are site decisions; transient outcomes are never stored."
        }
        mutex.withLock {
            decisions[WebsitePermissionKey(profileId, origin, permission)] = decision
        }
    }

    override suspend fun revoke(
        profileId: ProfileId,
        origin: BrowserOrigin,
        permission: WebsitePermission,
    ) = mutex.withLock {
        decisions.remove(WebsitePermissionKey(profileId, origin, permission))
        Unit
    }

    override suspend fun clearProfile(profileId: ProfileId) = mutex.withLock {
        decisions.keys.removeAll { it.profileId == profileId }
        Unit
    }

    override suspend fun decisionsFor(
        profileId: ProfileId,
    ): Map<WebsitePermissionKey, WebsitePermissionDecision> = mutex.withLock {
        decisions.filterKeys { it.profileId == profileId }
    }
}

/**
 * Platform-neutral website permission decision point (architecture §5 permission model).
 *
 * The core owns the sequence and the rules; the platform owns prompting:
 * 1. site settings are consulted first — a stored [WebsitePermissionDecision.Deny] is final and the
 *    OS layer is never consulted, so an already-granted OS permission can never auto-grant a site;
 * 2. the platform adapter then requests OS permission if needed;
 * 3. the website request is granted only when *both* layers permit it.
 *
 * Denials, cancellations, time-outs and "not supported" stay distinct outcomes. Only explicit user
 * decisions (Grant/Deny) are recorded as site settings; cancel/time-out/unsupported leave the site
 * undecided so the next request can prompt again.
 *
 * This class authorizes a website permission request. It does not grant OS permissions, evaluate
 * extension capabilities (see [BridgeAuthorizationPolicy]) or touch the page payload.
 */
class WebsitePermissionPolicy(
    private val store: WebsitePermissionStore,
    private val requestOsPermission: suspend (WebsitePermissionRequest) -> OsPermissionOutcome,
) : WebsitePermissionBroker {

    override suspend fun request(request: WebsitePermissionRequest): WebsitePermissionDecision {
        val origin = BrowserOrigin.parse(request.origin)
            // A malformed origin cannot be matched against site settings; fail closed.
            ?: return WebsitePermissionDecision.Deny
        val stored = store.find(request.profileId, origin, request.permission)
        if (stored == WebsitePermissionDecision.Deny) {
            // Site denial wins over any OS state; never auto-grant from OS permission alone.
            return WebsitePermissionDecision.Deny
        }
        val decision = resolve(stored, requestOsPermission(request))
        if (decision == WebsitePermissionDecision.Grant || decision == WebsitePermissionDecision.Deny) {
            store.record(request.profileId, origin, request.permission, decision)
        }
        return decision
    }

    /** Removes the stored decision for one origin/permission; the next request prompts again. */
    suspend fun revoke(request: WebsitePermissionRequest) {
        val origin = BrowserOrigin.parse(request.origin) ?: return
        store.revoke(request.profileId, origin, request.permission)
    }

    /** Drops every decision of one profile; call at private-session teardown. */
    suspend fun clearProfile(profileId: ProfileId) = store.clearProfile(profileId)

    /**
     * Combines the stored site decision with the OS layer outcome.
     *
     * Pure rule, kept separate so the matrix is testable without any platform involvement.
     * A grant requires the OS layer to permit *and* no stored denial; every non-grant OS outcome
     * maps to its own distinct decision instead of collapsing into Deny.
     */
    fun resolve(
        stored: WebsitePermissionDecision?,
        os: OsPermissionOutcome,
    ): WebsitePermissionDecision = when (os) {
        is OsPermissionOutcome.Unsupported -> WebsitePermissionDecision.Unsupported
        is OsPermissionOutcome.Cancelled -> WebsitePermissionDecision.Cancel
        is OsPermissionOutcome.TimedOut -> WebsitePermissionDecision.Timeout
        is OsPermissionOutcome.Denied -> WebsitePermissionDecision.Deny
        is OsPermissionOutcome.Granted -> when (stored) {
            WebsitePermissionDecision.Grant -> WebsitePermissionDecision.Grant
            WebsitePermissionDecision.Deny -> WebsitePermissionDecision.Deny
            else -> WebsitePermissionDecision.Grant
        }
    }
}
