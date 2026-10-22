# Veyra Browser Core Architecture

**Status:** Proposed normative contract  
**Scope:** Shared browser domain model and platform adapter contracts  
**First implementation:** Android + a Chromium-based runtime (selection gated on verified WebExtensions support)  
**Decision rule:** Platform adapters must either satisfy a contract or explicitly report that the capability is unsupported/limited. They must never silently fake success.

This document is the source of truth for core architecture. The platform implementation plan is subordinate to this contract.

## 1. Goals and non-goals

### Goals

- Share browser product behavior and Compose UI while keeping native engine types out of shared code.
- Give every live tab a stable identity and an explicitly owned engine session.
- Normalize navigation, errors, permissions, downloads, popups, request policies and extension management.
- Make JavaScript/native communication permissioned, origin-aware and versioned.
- Describe platform differences explicitly and test them as observable capabilities.

### Non-goals

- Do not pretend Chromium embedders, WKWebView, ArkWeb and CEF have identical APIs.
- Do not require every platform to run every WebExtension.
- Do not implement a generic “intercept every network request” API if a platform cannot provide it.
- Do not persist renderer memory or claim that JavaScript heap state survives process death.
- Do not expose native engine objects to common code.

## 2. Modules and dependency direction

### Logical module boundaries

These are logical responsibilities first. Keep the initial Gradle module count small; split modules only when dependency and release needs justify it.

| Boundary | Owns | Must not depend on |
| --- | --- | --- |
| `browser-core` | IDs, immutable models, engine/session contracts, events, errors, capabilities, policy request/result types | Compose UI, Android SDK, GeckoView, WebKit, ArkWeb, CEF |
| `browser-domain` (logical initially) | Navigation use cases, tab commands, permission decisions, download and extension use cases | Native engine classes |
| `browser-session` (logical initially) | Tab-to-engine registry, active tab, lifecycle and disposal orchestration | Compose lifecycle as the owner of engine lifetime |
| `browser-security` (logical initially) | URL/scheme validation, origin matching, bridge authorization and permission policy | Platform UI or engine internals |
| `browser-extensions` (logical initially) | Extension metadata, install/enable/disable use cases, permission prompts, storage policy | Assuming every platform has WebExtensions |
| `composeApp` | Shared UI and platform UI hosts | Direct native engine calls from common UI |
| Platform adapters | Native runtime/session, delegates, native view attachment, native callbacks and SDK-specific feature implementations | Other platform adapters |

Dependency direction:

```text
Compose UI / platform UI host
             |
             v
Browser use cases + BrowserSessionCoordinator
             |
             v
        browser-core
             ^
             |
  platform adapter factory + implementations
    |          |          |          |
 Chromium  WKWebView    ArkWeb     KCEF
 Android      iOS       HarmonyOS  Desktop
```

Rules:
1. `commonMain` may reference only platform-neutral contracts.
2. Platform source sets implement contracts and may depend on their native SDK.
3. `browser-core` never imports `composeApp` or platform SDKs.
4. UI sends commands to use cases/coordinator; UI does not own engine disposal.
5. Cross-platform capability claims must be grounded in the capability snapshot and platform tests.

## 3. Core identities and lifecycle contracts

Use stable opaque identifiers, not list indexes or native object identity:

- `TabId`: one user-visible tab.
- `SessionId`: one browser-engine session. Normally one live session per tab.
- `NavigationId`: one navigation attempt/document load lifecycle.
- `RequestId`: one async bridge/prompt/download decision request.
- `DownloadId`: one download task.
- `ProfileId`: one normal/private/user profile boundary.
- `ExtensionId`: one extension identity.

### BrowserEngine

`BrowserEngine` represents commands and observable state for one tab/session. It is not the native view itself.

Normative responsibilities:
- navigate, back, forward, reload and stop;
- expose immutable current state and a typed event stream;
- expose a capability snapshot;
- attach/detach a native view through a platform host contract, without destroying the session on detach;
- close/dispose idempotently and report that it is no longer usable.

Suggested Kotlin shape (illustrative; names can be adapted to the existing source):

```kotlin
interface BrowserEngine {
    val tabId: TabId
    val state: StateFlow<BrowserPageState>
    val events: Flow<BrowserEvent>
    val capabilities: BrowserCapabilities

    fun navigate(request: NavigationRequest)
    fun goBack()
    fun goForward()
    fun reload()
    fun stop()

    suspend fun close()
}
```

Native view attachment belongs in a platform-only host such as `BrowserViewHost`; do not put `View`, `UIView`, `NSView`, ArkUI, `GeckoView` or `CefBrowser` in common interfaces.

### BrowserSession / BrowserSessionCoordinator

A browser session is the lifetime boundary for a tab's engine state. A coordinator owns the map `TabId -> BrowserEngine`, active tab selection, engine creation, view attachment, and closure.

- The tab store owns product metadata (title, visible URL, pinned/muted state later, private flag, etc.).
- The engine registry owns live engine instances and is authoritative about whether a tab has a live renderer.
- The active tab is a selection, not an engine lifetime.
- Selecting a tab attaches/activates the existing session; it does not recreate it.
- Closing a tab removes it from the registry and closes exactly its engine.
- Closing the last tab creates a fresh empty tab according to product policy; it does not reuse the already-closed engine.
- Engine close must be idempotent. Duplicate UI events must not double-release native resources.
- Runtime lifetime is above individual tabs where the chosen engine exposes a shared runtime. Closing one tab must not shut down a process/profile-scoped runtime used by other tabs.
- A detached session remains live unless a platform explicitly evicts it under a documented memory policy. Any eviction must be surfaced as a recoverable session-loss event.
- Process death is not a tab close. Persist restorable metadata separately; recreate engines after process restart and do not claim in-memory JavaScript state survived.

### Normal and private profiles

- `isPrivate` metadata alone is not privacy isolation.
- A private tab must be associated with an explicit private `ProfileId` / engine configuration.
- Cookie jar, cache, history, downloads, extension permissions/storage and crash/restore behavior need a documented profile policy.
- Private navigation must not enter normal history, normal session restore, or normal profile data stores.
- If a platform cannot provide the required isolation, private mode must be disabled or clearly marked as limited until an implementation passes isolation tests.
- Do not mix private and normal tabs in a single engine session.

## 4. State, navigation events and errors

### State snapshot

`BrowserPageState` is a latest-value snapshot, not an event log. It should contain at least:
- current committed URL and display URL (if different);
- title;
- loading phase and normalized progress (0..100 when supported);
- back/forward availability;
- security indicators available from the engine (e.g. secure connection, certificate warning);
- current main-frame error, if any;
- renderer/session health;
- active navigation ID.

A missing native field must be represented as unknown/not-supported, not invented.

### Navigation lifecycle

Emit typed events, associated with `tabId` and `navigationId` where relevant:

- `NavigationRequested`: source, target URL, main-frame/subframe when available, user gesture when available.
- `NavigationDecisionRequired`: policy must allow, cancel, external-app handoff or new-tab routing.
- `NavigationStarted`
- `NavigationRedirected` (when exposed)
- `NavigationCommitted`
- `PageTitleChanged`
- `LoadProgressChanged`
- `NavigationCompleted`
- `NavigationFailed`: normalized error and main-frame/subframe scope.
- `ExternalNavigationRequested`
- `NewWindowRequested`
- `RendererCrashed` / `SessionLost`
- `SecurityStateChanged` when supported.

Rules:
- Do not assume every engine emits every intermediate event. The adapter documents event coverage and synthesizes only events that are semantically safe.
- Events for an old navigation must not overwrite state from a newer navigation. Match updates to `NavigationId` where the platform provides enough information.
- Progress is best-effort and not comparable as a performance metric across engines.
- A redirect is not a separate tab navigation unless product policy says so.
- Subframe errors must not automatically replace the top-level page error.

### Error model

Use a stable cross-platform category plus optional native diagnostic data:

```kotlin
enum class BrowserErrorCategory {
    INVALID_URL, UNSUPPORTED_SCHEME, NAVIGATION_BLOCKED,
    DNS, CONNECTION, TLS, TIMEOUT, OFFLINE,
    HTTP, CONTENT_PROCESS_CRASHED, RESOURCE, PERMISSION_DENIED,
    CANCELLED, ENGINE_FAILURE, UNKNOWN
}

data class BrowserError(
    val category: BrowserErrorCategory,
    val message: String,
    val nativeCode: String? = null,
    val failingUrl: String? = null,
    val isMainFrame: Boolean? = null,
    val isRecoverable: Boolean = true,
)
```

Do not expose raw native exception objects in common state. Keep them in platform logs with sensitive URL/query redaction.

## 5. Capability model and independent interfaces

A capability snapshot describes what the active engine/profile can do. Capabilities may depend on engine version, profile, permissions and runtime configuration.

Suggested capability groups:
- navigation policy and external-app handoff;
- request interception scope (navigation-only, selected resource classes, or broad interception);
- downloads (detect, manage, progress, cancel, choose destination);
- website permission decisions;
- OS runtime permission integration;
- popup/new-window handling;
- JavaScript/native messaging;
- WebExtensions runtime and supported extension API level;
- private-profile isolation;
- certificate/security callbacks;
- media/fullscreen handling;
- restore/session serialization.

Use independent interfaces instead of one giant engine interface:

```kotlin
interface NavigationPolicyHandler {
    /**
     * Fast, deterministic and non-blocking: some native navigation callbacks are synchronous.
     * Asynchronous user prompts must use a separate defer/cancel/reissue flow.
     */
    fun decide(request: NavigationRequest): NavigationDecision
}

interface RequestInterceptor {
    val supportedScope: RequestInterceptionScope
    suspend fun intercept(request: ResourceRequest): ResourceDecision
}

interface DownloadController {
    val tasks: StateFlow<List<DownloadTask>>
    suspend fun start(request: DownloadRequest): DownloadId
    suspend fun cancel(id: DownloadId)
}

interface WebsitePermissionBroker {
    suspend fun request(request: WebsitePermissionRequest): WebsitePermissionDecision
}

interface PopupHandler {
    suspend fun onPopup(request: PopupRequest): PopupDecision
}

interface NativeMessageEndpoint {
    suspend fun send(message: BridgeEnvelope): BridgeResult
}
```

These are illustrative contract shapes, not instructions to add every method to `BrowserEngine`. The factory exposes supported optional interfaces/capabilities for each engine. Unsupported operations return an explicit `UnsupportedCapability` result or are absent from the adapter surface.

### Navigation policy vs resource interception

Navigation-policy evaluation must be synchronous and non-blocking because some native engines require a decision before their callback returns. Do not block a native callback to await a suspend function. Any future policy that requires user interaction or remote work needs an explicit asynchronous deferral protocol and must document whether the original request's method, body, headers, and user gesture can be preserved.

These are distinct:
- Navigation policy decides where a user/page navigation goes (allow, cancel, external app, new tab).
- Resource interception deals with network/resource requests and response bodies.
- Download handling decides how a download is surfaced, stored, cancelled and reported.

Never claim that navigation callbacks provide full ad-blocking or arbitrary subresource interception. Request interception coverage must be tested and described per platform.

### Permission model

Separate three concepts:
1. **Website permission:** site origin requests camera, microphone, location, notifications, clipboard or other sensitive web capability.
2. **OS runtime permission:** Android/iOS/HarmonyOS system permission required to grant the website permission.
3. **Veyra capability permission:** extension/native feature asks to access browser data or invoke privileged host functions.

The core policy first checks site settings and profile policy. The platform adapter then requests OS permission if needed, and only grants the website request if both layers permit it. Denials, cancellations, timeouts and “not supported” are distinct outcomes. Never auto-grant solely because the OS permission is already granted.

### Download contract

Normalize a download request into URL, suggested filename, MIME type, expected size if known, originating tab/profile and user-gesture metadata if available. The platform download manager reports queued/running/paused/completed/failed/cancelled states and bytes where available. Destination selection and notification behavior are platform-specific. Private downloads must follow the private-profile policy and must not leak into normal download history.

### Popup contract

A popup is a policy request, not an instruction to silently create a native view. The coordinator decides whether to open a new tab, reuse the current tab, open an external app, or block. If the engine cannot provide the necessary callback, report that limitation and test the fallback.

## 6. JavaScript Bridge protocol and security

### Principle

Use GeckoView WebExtensions/native messaging for extension-to-host communication on Android. A general-purpose JavaScript bridge exposed to arbitrary pages is not the extension system and must not be treated as one.

### Envelope

All bridge messages use a versioned, typed envelope:

```json
{
  "protocol": "veyra.bridge",
  "version": 1,
  "id": "request-id",
  "type": "request",
  "method": "bookmarks.query",
  "payload": {},
  "context": {
    "tabId": "tab-id",
    "origin": "https://example.com"
  }
}
```

Response:

```json
{
  "protocol": "veyra.bridge",
  "version": 1,
  "id": "request-id",
  "type": "response",
  "ok": true,
  "payload": {}
}
```

Errors use a stable code such as `INVALID_MESSAGE`, `UNSUPPORTED_VERSION`, `UNAUTHORIZED_ORIGIN`, `PERMISSION_DENIED`, `METHOD_NOT_FOUND`, `TIMEOUT`, `CANCELLED` or `INTERNAL_ERROR`. Never return stack traces, filesystem paths, cookies or other secrets to the page.

### Required validation

- Validate protocol name, supported version, message type, request ID, method allowlist and payload schema.
- Bound message size, outstanding request count and execution time; support cancellation where meaningful.
- Resolve origin from trusted engine/frame metadata, not from a page-supplied `context.origin`.
- Bind requests to tab/session and the originating frame when available.
- Use exact normalized origin matching (scheme + host + effective port); no substring or suffix-only checks.
- Reject opaque, malformed and untrusted origins by default.
- Deny privileged bridge calls from arbitrary third-party pages by default. Prefer extension-ID-authenticated native messaging or a narrow allowlist of trusted first-party origins.
- Authorize each method separately with least-privilege scopes; do not have a single “bridge enabled” boolean that grants all APIs.
- Require explicit user consent for sensitive grants and provide revocation.
- Treat all web content and all message payloads as untrusted.
- Never expose arbitrary file, shell, app-intent, cookie, credential or unrestricted network methods to web content.
- Record security-relevant decisions without logging full secrets or sensitive query parameters.

### Permissions

Represent grants with subject, scope, profile, creation time and optional expiry/revocation. A grant may be scoped to:
- a specific extension ID;
- an exact origin;
- a capability/method group;
- a profile (normal/private).

Private profile must not inherit privileged grants or extension storage unless an explicit policy and user choice permit it.

## 7. Extension contract

- Android GeckoView is the initial WebExtensions implementation.
- The core defines extension metadata, permissions, install source, lifecycle state, errors and capability requirements without importing GeckoView types.
- The Android adapter maps those operations to `WebExtensionController`.
- External XPI installation must respect Mozilla signing requirements and GeckoView permission prompts. Built-in extensions packaged in the APK are a separate trusted distribution path.
- Runtime installed-extension state is authoritative for engine installation; Veyra storage keeps user preferences and UI metadata, then reconciles on startup.
- Native messaging accepts messages only from the expected extension identity and validates protocol/version/payload.
- Do not promise all Firefox add-ons work: compatibility depends on GeckoView support and each extension's APIs.
- iOS, ArkWeb and KCEF must report their actual extension capability. They may later support a Veyra-specific constrained plugin API, but that is a separate compatibility tier.

## 8. Platform capability matrix

Legend: **Native** = supported by a documented native API; **Limited** = possible with constraints/adapter work; **Custom** = Veyra must build substantial product logic; **Unknown** = must be verified in a spike before promising it. This matrix is a planning baseline, not a substitute for testing the pinned SDK versions.

| Capability | Android: GeckoView | iOS: WKWebView | HarmonyOS: ArkWeb | Desktop: KCEF/CEF |
| --- | --- | --- | --- | --- |
| Navigation callbacks/policy | Native delegates | Native `WKNavigationDelegate` | Native navigation callbacks; verify SDK level | Native CEF handlers |
| Progress/title/loading | Native delegates/state | Native progress + delegates | Native callbacks; verify SDK level | Native handlers |
| Request interception | Native request/content hooks; exact scope test required | Limited; navigation policy is not arbitrary resource interception | Scheme/request hooks vary; exact scope test required | Native request/resource handlers |
| Downloads | Adapter and native download APIs/intent flow; verify current Gecko APIs | Custom download pipeline and destination UX; API coverage/version must be tested | Native capability depends on ArkWeb version; verify | CEF download handler |
| Website permissions + OS permissions | Native content permission delegate + Android permission broker | Native UI/permission callbacks; OS-specific constraints | ArkWeb callbacks + HarmonyOS permission broker | CEF callbacks + host OS permission broker |
| Popups/new windows | Native delegate; map to tab policy | Native `WKUIDelegate` | Native window callbacks; verify | CEF lifespan handler |
| JS-to-host messaging | WebExtensions/native messaging preferred; secure origin rules still apply | Message handlers; origin/frame policy must be enforced by Veyra | JS proxy/message API; scope and origin controls must be verified | Message router / process message; origin and frame checks required |
| WebExtensions | Native GeckoView WebExtensions; API compatibility varies | No general Firefox WebExtensions runtime | No general Firefox WebExtensions runtime assumed | CEF does not automatically provide Chrome Web Store extensions; custom integration required |
| Private profile isolation | Native/runtime configuration must be designed and tested | Separate website data store configuration; test all storage classes | Separate profile/data isolation must be verified | Separate request context/cache/cookie store |
| Renderer/process crash | Native runtime/session callbacks; recovery policy in Veyra | Web content process termination callback | Verify native crash/lifecycle hooks | CEF render-process handlers |
| Native view detach/reattach without closing session | GeckoSession + GeckoView host lifecycle | WKWebView retained independently of Compose subtree | ArkWeb controller/view lifecycle | CefBrowser lifespan controlled outside Compose |
| Session restore | Metadata can be persisted; live JS state not guaranteed after process death | Metadata/state restoration limited; no live JS heap restoration | Verify restoration semantics | CEF state/session recreation strategy |

### Required fallback behavior

- Unsupported resource interception: state the scope honestly; do not claim full ad blocking. Provide engine-specific rule support or a clearly labeled limited mode.
- Unsupported extension runtime: disable installation of incompatible packages and explain that only supported Veyra plugin types can run.
- Unsupported private isolation: do not expose private mode as secure/isolated until the isolation tests pass.
- Unsupported popup callback: block safely or route through an explicit link/navigation policy; do not create a blank tab pretending the popup was handled.
- Unsupported download metadata: use the best available URL/MIME/filename and show missing fields as unknown.
- Missing progress/security detail: expose unknown, not a fabricated value.

## 9. Tab lifecycle and resource release state machine

```text
CREATED -> OPENING -> LIVE <-> DETACHED
                      |          |
                      v          v
                  CRASHED/LOST -> RESTORABLE
                      |
LIVE / DETACHED / CRASHED -> CLOSING -> CLOSED
```

Rules:
- `DETACHED` means the native view is not attached/visible; the engine session remains live.
- `CLOSING` rejects new commands and cancels outstanding prompts/bridge requests owned by that tab.
- `CLOSED` is terminal; late callbacks are ignored using tab/session/navigation identity checks.
- On close, cancel pending bridge requests, dismiss tab-owned permission/popup UI, cancel or detach tab-bound tasks according to download policy, unregister delegates, detach the native view, close the session and remove the registry entry.
- A download that has already been accepted into the browser-wide download manager may outlive its originating tab; it must retain profile attribution and must not retain the tab engine unnecessarily.
- The runtime is closed only at application/profile-owner shutdown, not on tab close or recomposition.
- Activity/view recreation must not equal tab close.
- Memory-pressure eviction must be explicit, observable and recoverable; never silently reload a tab while claiming its live state was preserved.

## 10. Testing and acceptance matrix

### Core unit tests (common/JVM where applicable)

- Tab open/select/close and active-tab fallback.
- Closing the last tab creates a fresh session identity.
- Duplicate close is harmless; commands after close fail deterministically.
- Old navigation events cannot overwrite a newer navigation state.
- Private visits never enter normal history/session restore.
- Origin matching rejects lookalike domains, wrong schemes, wrong ports, opaque origins and malformed values.
- Bridge rejects unknown methods/versions, oversized payloads, replayed/unknown IDs, unauthorized origins and permission escalation.
- Capability-gated operations return explicit unsupported results.
- Download and permission state transitions are deterministic.

### Android GeckoView integration tests

- Runtime is reused across tab changes and not created by Compose recomposition.
- Two tabs keep separate URL/history/document state; switching away and back preserves a live page's in-memory state.
- Closing one tab closes only its GeckoSession; other tabs continue running.
- Back/forward/reload/stop, redirects, progress, title and main-frame errors work on real pages.
- Navigation policy handles external schemes, target=_blank/popups and blocked URLs.
- Permission denial/cancellation and OS permission denial are handled without hangs.
- Request-interception scope is measured with main-frame, image, script, fetch/XHR, redirects and service-worker cases.
- Bundled test extension installs idempotently; enable/disable/uninstall work; native messaging authenticates extension identity.
- External XPI signature failure and permission prompts are tested.
- Process death recreates tabs from metadata without claiming live JavaScript state survived.
- Private profile tests verify cookies, history, cache and extension-storage isolation.

### Platform conformance tests

Each adapter must run the shared core contract tests where possible plus native integration tests for every capability it advertises. Capability changes caused by SDK/engine versions must be covered by versioned test results. No platform is considered conformant merely because it compiles.

## 11. Implementation gates

1. Review and approve this contract before adding GeckoView to the app.
2. Map the existing `BrowserEngine` and `BrowserSessionStore` onto this contract; preserve working behavior and identify API changes.
3. Add the GeckoView dependency at a pinned stable version and validate build/package/startup on an Android device.
4. Implement runtime owner, engine registry and one-session-per-live-tab host.
5. Pass navigation and lifecycle tests before adding broad feature work.
6. Add permission/download/popup/request policy adapters.
7. Add the bundled WebExtension and secure native messaging before external extension UX.
8. Implement other platform adapters against the same contract and publish their capability snapshots.

## 12. Upstream API references

- [GeckoView WebExtensions](https://mozilla.github.io/geckoview/consumer/docs/web-extensions)
- [GeckoView WebExtensionController](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/WebExtensionController.html)
- [Apple WKWebView](https://developer.apple.com/documentation/webkit/WKWebView)
- [Apple WKNavigationDelegate](https://developer.apple.com/documentation/webkit/wknavigationdelegate)
- [Apple WKUIDelegate](https://developer.apple.com/documentation/webkit/wkuidelegate)
- [CEF general usage and request handling](https://chromiumembedded.github.io/cef/general_usage.html)
- [Huawei ArkWeb developer documentation](https://developer.huawei.com/consumer/en/doc/harmonyos-guides-V5/web-introduction-V5)
