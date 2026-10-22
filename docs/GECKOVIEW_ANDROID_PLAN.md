> **Superseded:** This document records the previous GeckoView proposal and is retained only as decision history. The current Android direction is Chromium-based; see [CHROMIUM_ANDROID_PLAN.md](CHROMIUM_ANDROID_PLAN.md). Do not implement this plan unless the engine decision is explicitly reversed.

# Android GeckoView implementation plan

This plan is the first implementation stage for Veyra's Android-first architecture. It records decisions and gates; it does not claim that GeckoView has already been integrated.

## Historical decision record (no longer current)

- **Previous Android engine proposal:** GeckoView.
- **Shared UI:** Compose Multiplatform.
- **Shared contract:** `:browser-core`.
- **Native ownership:** Android source sets own `GeckoRuntime`, `GeckoSession`, `GeckoView` and all Gecko delegates.
- **Extension direction:** Mozilla WebExtensions first on Android, with a Veyra-level management and permission model.
- **Other platforms:** separate native adapters; no promise of identical extension support.

## Proposed Android package boundaries

Keep these as packages/source-set boundaries initially; do not create a module for every interface.

```text
composeApp/src/androidMain/kotlin/dev/veyra/browser/
  engine/
    GeckoRuntimeOwner.kt       # process-scoped runtime and shutdown policy
    GeckoBrowserEngine.kt      # BrowserEngine implementation for one tab/session
    GeckoBrowserHost.kt        # attaches/detaches GeckoView without owning tab metadata
    GeckoEngineFactory.kt      # creates engines and exposes capabilities
    GeckoNavigationDelegate.kt # native navigation/load/error callbacks
    GeckoContentDelegate.kt    # prompts, file chooser, fullscreen and other content UI
  permissions/
    AndroidPermissionBroker.kt # OS permission checks and request coordination
  extensions/
    GeckoExtensionManager.kt   # WebExtensionController adapter
    GeckoNativeMessaging.kt    # typed/versioned extension-to-host protocol
```

Names are proposed, not existing files.

## Core contract changes to evaluate before implementation

1. Add a platform-neutral capability model (for example, navigation interception, request interception, WebExtensions, downloads, popup handling and private-profile isolation).
2. Define normalized browser events for navigation start/commit/finish, progress, title/URL changes, errors, popup requests, download requests and website permission requests.
3. Keep engine commands synchronous only when the underlying action is naturally immediate; represent native async results/events with `StateFlow`, suspending use cases or explicit result types.
4. Give each engine/session a stable tab ID and idempotent disposal semantics.
5. Keep extension metadata and install/permission use cases platform-neutral; keep Gecko classes out of common source sets.

Do not add a universal method for every possible browser capability. Prefer a core capability snapshot plus capability-specific interfaces/use cases.

## GeckoView lifecycle rules

- Initialize one normal-profile `GeckoRuntime` through a process-scoped owner, not a composable or individual tab.
- Create one `GeckoSession` per live tab and open it against the shared runtime.
- Attach the active session to a `GeckoView`; switching tabs detaches/hides the old view without closing its session.
- On tab close, close/dispose the matching session and remove that exact tab's engine from the registry.
- Handle Activity recreation separately from tab closure. Retain session ownership above the Activity where appropriate and reattach views safely.
- Do not assume setting `BrowserTab.isPrivate` provides private browsing. Decide the actual Gecko private-session/profile configuration and test history, cookies, cache and extension storage isolation before shipping private tabs.
- Use a single, explicit runtime lifecycle owner; do not destroy the runtime when a single tab closes.

## Extension implementation gates

### Gate A: built-in extension
- Package a tiny test WebExtension under Android assets with a stable ID/version.
- Install it idempotently through GeckoView's built-in-extension API.
- Verify a content script can run on a test page.
- Verify native messaging is only accepted from the expected extension and validates payload version/type.

### Gate B: extension management
- Enumerate installed extensions from GeckoView rather than treating a separate local list as authoritative.
- Add install/enable/disable/uninstall operations with clear loading/error states.
- Display requested permissions before install and whenever an extension asks for additional permissions.
- Persist user preferences separately from engine-owned installation state.

### Gate C: external extensions
- Decide supported sources and UX (AMO links, local XPI import, or both).
- Respect GeckoView's signature requirements for external XPI installation.
- Never imply every Firefox Android add-on is compatible; report engine/API incompatibility clearly.
- Add update policy, revocation/blocklist behavior and storage cleanup before calling this production-ready.

## Dependency and build gate

Before adding the dependency:
1. Select a pinned **stable** GeckoView release from Mozilla's Maven repository; avoid a floating version and avoid Nightly for production.
2. Confirm the artifact name/channel and its minimum Android/Java requirements from the official Quick Start.
3. Add Mozilla's Maven repository in the repository-management location required by the current Gradle setup.
4. Confirm the current Java compatibility settings and Android minSdk/ABI packaging implications.
5. Build `:composeApp:assembleDebug` and run on an Android emulator/device.
6. Record APK size and startup cost before making a packaging decision.

Official references:
- [GeckoView Quick Start](https://mozilla.github.io/geckoview/consumer/docs/geckoview-quick-start)
- [WebExtensions in GeckoView](https://mozilla.github.io/geckoview/consumer/docs/web-extensions)
- [WebExtensionController API](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/WebExtensionController.html)

## Exit criteria for this architecture stage

- The repository documents platform direction and dependency rules.
- Runtime/session ownership is explicit.
- Extension security and cross-platform limitations are explicit.
- The next coding stage can integrate GeckoView without putting native classes in common code.
