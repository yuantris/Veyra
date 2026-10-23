package dev.veyra.browser.core.security

import dev.veyra.browser.core.ProfileId
import dev.veyra.browser.core.RequestId
import dev.veyra.browser.core.TabId
import dev.veyra.browser.core.WebsitePermission
import dev.veyra.browser.core.WebsitePermissionDecision
import dev.veyra.browser.core.WebsitePermissionRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class WebsitePermissionPolicyTest {
    private val regularProfile = ProfileId("regular")
    private val privateProfile = ProfileId("private")
    private val exampleCom = requireNotNull(BrowserOrigin.parse("https://example.com"))
    private val otherCom = requireNotNull(BrowserOrigin.parse("https://other.com"))

    private class FakeOsLayer {
        var next: OsPermissionOutcome = OsPermissionOutcome.Granted
        var invocations = 0
    }

    private fun policy(
        store: WebsitePermissionStore = InMemoryWebsitePermissionStore(),
        os: FakeOsLayer = FakeOsLayer(),
    ): Pair<WebsitePermissionPolicy, FakeOsLayer> =
        WebsitePermissionPolicy(store) { os.invocations++; os.next } to os

    private fun request(
        origin: String = "https://example.com",
        permission: WebsitePermission = WebsitePermission.CAMERA,
        profileId: ProfileId = regularProfile,
    ) = WebsitePermissionRequest(
        requestId = RequestId("request-1"),
        tabId = TabId("tab-1"),
        profileId = profileId,
        origin = origin,
        permission = permission,
    )

    @Test
    fun storedDenyIsFinalEvenWhenTheOsPermissionIsGranted() = runBlocking {
        val (policy, os) = policy()
        // The user denies once through the OS prompt; the denial becomes a site decision.
        os.next = OsPermissionOutcome.Denied
        assertEquals(WebsitePermissionDecision.Deny, policy.request(request()))
        val invocationsBefore = os.invocations
        // The OS layer now reports Granted: the stored denial must still win, and the OS layer
        // must not even be consulted for a denied site.
        os.next = OsPermissionOutcome.Granted
        assertEquals(WebsitePermissionDecision.Deny, policy.request(request()))
        assertEquals(invocationsBefore, os.invocations)
    }

    @Test
    fun storedGrantWithOsGrantedGrantsAndOsDenialIsStillCaught() = runBlocking {
        val store = InMemoryWebsitePermissionStore()
        val (policy, os) = policy(store)
        os.next = OsPermissionOutcome.Granted
        assertEquals(WebsitePermissionDecision.Grant, policy.request(request()))
        assertEquals(
            WebsitePermissionDecision.Grant,
            store.find(regularProfile, exampleCom, WebsitePermission.CAMERA),
        )
        // Both layers must permit: an OS permission revoked after the site grant denies.
        os.next = OsPermissionOutcome.Denied
        assertEquals(WebsitePermissionDecision.Deny, policy.request(request()))
    }

    @Test
    fun promptWithOsGrantGrantsAndRecordsTheSiteDecision() = runBlocking {
        val store = InMemoryWebsitePermissionStore()
        val (policy, os) = policy(store)
        os.next = OsPermissionOutcome.Granted
        assertEquals(WebsitePermissionDecision.Grant, policy.request(request()))
        // The first-time grant is recorded as a site decision.
        assertEquals(
            WebsitePermissionDecision.Grant,
            store.find(regularProfile, exampleCom, WebsitePermission.CAMERA),
        )
    }

    @Test
    fun promptWithOsDenyDeniesAndRecordsTheSiteDecision() = runBlocking {
        val store = InMemoryWebsitePermissionStore()
        val (policy, os) = policy(store)
        os.next = OsPermissionOutcome.Denied
        assertEquals(WebsitePermissionDecision.Deny, policy.request(request()))
        // The denial is recorded, so a later OS grant cannot resurrect the site.
        assertEquals(
            WebsitePermissionDecision.Deny,
            store.find(regularProfile, exampleCom, WebsitePermission.CAMERA),
        )
        os.next = OsPermissionOutcome.Granted
        assertEquals(WebsitePermissionDecision.Deny, policy.request(request()))
    }

    @Test
    fun osCancelIsDistinctFromDenyAndLeavesTheSiteUndecided() = runBlocking {
        val store = InMemoryWebsitePermissionStore()
        val (policy, os) = policy(store)
        os.next = OsPermissionOutcome.Cancelled
        assertEquals(WebsitePermissionDecision.Cancel, policy.request(request()))
        // Nothing recorded: the site is still undecided and prompts again.
        assertNull(store.find(regularProfile, exampleCom, WebsitePermission.CAMERA))
        os.next = OsPermissionOutcome.Granted
        assertEquals(WebsitePermissionDecision.Grant, policy.request(request()))
    }

    @Test
    fun osTimeoutIsDistinctFromDenyAndLeavesTheSiteUndecided() = runBlocking {
        val store = InMemoryWebsitePermissionStore()
        val (policy, os) = policy(store)
        os.next = OsPermissionOutcome.TimedOut
        assertEquals(WebsitePermissionDecision.Timeout, policy.request(request()))
        assertNull(store.find(regularProfile, exampleCom, WebsitePermission.CAMERA))
        os.next = OsPermissionOutcome.Granted
        assertEquals(WebsitePermissionDecision.Grant, policy.request(request()))
    }

    @Test
    fun osUnsupportedIsDistinctAndLeavesTheSiteUndecided() = runBlocking {
        val store = InMemoryWebsitePermissionStore()
        val (policy, os) = policy(store)
        os.next = OsPermissionOutcome.Unsupported
        assertEquals(WebsitePermissionDecision.Unsupported, policy.request(request()))
        assertNull(store.find(regularProfile, exampleCom, WebsitePermission.CAMERA))
        os.next = OsPermissionOutcome.Granted
        assertEquals(WebsitePermissionDecision.Grant, policy.request(request()))
    }

    @Test
    fun resolveKeepsEveryOsOutcomeDistinct() {
        val (policy, _) = policy()
        assertEquals(WebsitePermissionDecision.Grant, policy.resolve(null, OsPermissionOutcome.Granted))
        assertEquals(WebsitePermissionDecision.Deny, policy.resolve(null, OsPermissionOutcome.Denied))
        assertEquals(WebsitePermissionDecision.Cancel, policy.resolve(null, OsPermissionOutcome.Cancelled))
        assertEquals(WebsitePermissionDecision.Timeout, policy.resolve(null, OsPermissionOutcome.TimedOut))
        assertEquals(
            WebsitePermissionDecision.Unsupported,
            policy.resolve(null, OsPermissionOutcome.Unsupported),
        )
        // A stored denial can never be overridden by the OS layer.
        assertEquals(
            WebsitePermissionDecision.Deny,
            policy.resolve(WebsitePermissionDecision.Deny, OsPermissionOutcome.Granted),
        )
        assertEquals(
            WebsitePermissionDecision.Grant,
            policy.resolve(WebsitePermissionDecision.Grant, OsPermissionOutcome.Granted),
        )
    }

    @Test
    fun privateProfileDecisionsNeverLeakIntoTheRegularProfile() = runBlocking {
        val store = InMemoryWebsitePermissionStore()
        val (policy, os) = policy(store)
        // Deny in the regular profile first.
        os.next = OsPermissionOutcome.Denied
        assertEquals(WebsitePermissionDecision.Deny, policy.request(request(profileId = regularProfile)))
        // The private profile must not inherit that denial: it prompts and grants.
        os.next = OsPermissionOutcome.Granted
        assertEquals(WebsitePermissionDecision.Grant, policy.request(request(profileId = privateProfile)))
        // Neither profile's site settings are visible to the other.
        assertEquals(
            WebsitePermissionDecision.Deny,
            store.find(regularProfile, exampleCom, WebsitePermission.CAMERA),
        )
        assertEquals(
            WebsitePermissionDecision.Grant,
            store.find(privateProfile, exampleCom, WebsitePermission.CAMERA),
        )
        // The private grant is not applied to the regular profile either: the regular denial
        // still short-circuits without consulting the OS layer.
        val invocationsBefore = os.invocations
        os.next = OsPermissionOutcome.Granted
        assertEquals(WebsitePermissionDecision.Deny, policy.request(request(profileId = regularProfile)))
        assertEquals(invocationsBefore, os.invocations)
    }

    @Test
    fun clearProfileDropsDecisionsForEphemeralTeardown() = runBlocking {
        val store = InMemoryWebsitePermissionStore()
        val (policy, os) = policy(store)
        os.next = OsPermissionOutcome.Granted
        policy.request(request(profileId = privateProfile))
        policy.request(request(profileId = regularProfile))

        policy.clearProfile(privateProfile)

        assertNull(store.find(privateProfile, exampleCom, WebsitePermission.CAMERA))
        assertTrue(store.decisionsFor(privateProfile).isEmpty())
        // The regular profile is untouched.
        assertEquals(
            WebsitePermissionDecision.Grant,
            store.find(regularProfile, exampleCom, WebsitePermission.CAMERA),
        )
    }

    @Test
    fun revokeMakesTheNextRequestPromptAgain() = runBlocking {
        val (policy, os) = policy()
        os.next = OsPermissionOutcome.Denied
        assertEquals(WebsitePermissionDecision.Deny, policy.request(request()))
        val invocationsBefore = os.invocations
        // A stored denial short-circuits: the OS layer is not consulted.
        os.next = OsPermissionOutcome.Granted
        assertEquals(WebsitePermissionDecision.Deny, policy.request(request()))
        assertEquals(invocationsBefore, os.invocations)
        // Revoking the decision makes the site prompt again.
        policy.revoke(request())
        os.next = OsPermissionOutcome.Granted
        assertEquals(WebsitePermissionDecision.Grant, policy.request(request()))
        assertEquals(invocationsBefore + 1, os.invocations)
    }

    @Test
    fun decisionsAreScopedPerOriginAndPermission() = runBlocking {
        val store = InMemoryWebsitePermissionStore()
        val (policy, os) = policy(store)
        os.next = OsPermissionOutcome.Granted
        assertEquals(WebsitePermissionDecision.Grant, policy.request(request()))
        // Only the exact origin/permission pair was stored.
        assertNull(store.find(regularProfile, otherCom, WebsitePermission.CAMERA))
        assertNull(store.find(regularProfile, exampleCom, WebsitePermission.MICROPHONE))
        // A different origin prompts again.
        os.next = OsPermissionOutcome.Denied
        assertEquals(WebsitePermissionDecision.Deny, policy.request(request(origin = "https://other.com")))
        // The granted pair keeps its grant.
        os.next = OsPermissionOutcome.Granted
        assertEquals(WebsitePermissionDecision.Grant, policy.request(request()))
    }

    @Test
    fun malformedOriginFailsClosedWithoutConsultingTheOsLayer() = runBlocking {
        val (policy, os) = policy()
        assertEquals(WebsitePermissionDecision.Deny, policy.request(request(origin = "not a url")))
        assertEquals(0, os.invocations)
    }

    @Test
    fun storeRejectsTransientOutcomesAsSiteDecisions() = runBlocking {
        val store = InMemoryWebsitePermissionStore()
        assertFailsWith<IllegalArgumentException> {
            store.record(regularProfile, exampleCom, WebsitePermission.CAMERA, WebsitePermissionDecision.Cancel)
        }
        assertFailsWith<IllegalArgumentException> {
            store.record(regularProfile, exampleCom, WebsitePermission.CAMERA, WebsitePermissionDecision.Timeout)
        }
        assertNull(store.find(regularProfile, exampleCom, WebsitePermission.CAMERA))
    }

    @Test
    fun canonicalOriginsShareOneDecision() = runBlocking {
        val store = InMemoryWebsitePermissionStore()
        val (policy, os) = policy(store)
        os.next = OsPermissionOutcome.Granted
        assertEquals(
            WebsitePermissionDecision.Grant,
            policy.request(request(origin = "https://example.com:443")),
        )
        // The explicit default port resolves to the same canonical origin, so there is one
        // decision shared by both spellings.
        assertEquals(
            WebsitePermissionDecision.Grant,
            store.find(regularProfile, exampleCom, WebsitePermission.CAMERA),
        )
        // The stored canonical decision applies to the plain-form origin as well.
        os.next = OsPermissionOutcome.Denied
        assertEquals(WebsitePermissionDecision.Deny, policy.request(request()))
    }
}
