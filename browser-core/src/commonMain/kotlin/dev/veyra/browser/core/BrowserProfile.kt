package dev.veyra.browser.core

/**
 * A profile is a storage/security boundary, not just a UI mode.
 *
 * Platform adapters must map each profile to the strongest native isolation primitive available.
 * A private profile must never silently fall back to the regular profile's persistent store.
 */
data class BrowserProfile(
    val id: ProfileId,
    val kind: BrowserProfileKind,
    val storagePolicy: BrowserStoragePolicy,
    val allowsCookies: Boolean = true,
    val allowsCache: Boolean = true,
    val allowsHistory: Boolean = true,
) {
    init {
        if (kind == BrowserProfileKind.PRIVATE) {
            require(storagePolicy == BrowserStoragePolicy.EPHEMERAL) {
                "Private profiles must use ephemeral storage."
            }
            require(!allowsHistory) {
                "Private profiles must not persist browsing history."
            }
        }
    }

    val isPrivate: Boolean
        get() = kind == BrowserProfileKind.PRIVATE
}

enum class BrowserProfileKind {
    REGULAR,
    PRIVATE,
}

enum class BrowserStoragePolicy {
    PERSISTENT,
    EPHEMERAL,
}

/**
 * Resolves profile identities before an engine is created.
 * Implementations may later persist regular profile metadata, but private profiles are ephemeral.
 */
interface BrowserProfileProvider {
    fun get(profileId: ProfileId): BrowserProfile?
    fun defaultProfile(): BrowserProfile
}

/** Small in-memory provider suitable for app bootstrap and deterministic tests. */
class InMemoryBrowserProfileProvider(
    profiles: List<BrowserProfile> = listOf(
        BrowserProfile(
            id = ProfileId("default"),
            kind = BrowserProfileKind.REGULAR,
            storagePolicy = BrowserStoragePolicy.PERSISTENT,
        ),
    ),
    private val defaultProfileId: ProfileId = ProfileId("default"),
) : BrowserProfileProvider {
    private val profilesById = profiles.associateBy { it.id }

    init {
        require(profilesById.size == profiles.size) { "Profile IDs must be unique." }
        require(defaultProfileId in profilesById) { "The default profile must be registered." }
        require(profilesById.getValue(defaultProfileId).kind == BrowserProfileKind.REGULAR) {
            "The default profile must be regular."
        }
    }

    override fun get(profileId: ProfileId): BrowserProfile? = profilesById[profileId]

    override fun defaultProfile(): BrowserProfile = profilesById.getValue(defaultProfileId)
}
