# Android Chromium implementation plan

**Status:** Active implementation plan — System WebView validation now, self-compiled Chromium target later  
**Authority:** This plan is subordinate to [the architecture baseline](ARCHITECTURE.md) and [Browser Core Architecture](BROWSER_CORE_ARCHITECTURE.md).  
**Current decision (2026-10-24):** Use Android System WebView as the temporary native implementation for functional validation. The intended long-term runtime is a self-compiled Chromium kernel; replace the runtime behind the existing adapter boundary when the kernel is buildable and passes the documented gates. GeckoView is not the chosen direction.

## 1. Goals and decision boundaries

- Prioritize Android while keeping the engine contract platform-neutral.
- Keep Chromium/runtime types, native views, callbacks and resource ownership inside `androidMain`.
- Reuse the existing browser-core contract and coordination layer; do not create a second engine contract for Chromium.
- Choose the concrete Chromium embedding/runtime based on verified requirements, including navigation and request interception, downloads, popup handling, profile isolation, extension needs, update strategy, licensing, ABI support, APK size and startup cost.
- Do not equate using Chromium/Blink/V8 with automatically having a complete browser runtime or WebExtensions support.

## 2. Existing adapter entry points

The current Android factory entry point is `createPlatformBrowserEngineFactory()`, which delegates to `SurfaceBoundBrowserEngineFactory`. The existing `AndroidChromiumSurfaceRenderer` is part of the current Android rendering path. Audit these implementations before replacing or duplicating them.

## 3. Adapter responsibilities

Keep the initial implementation in the existing Android source set and organize related classes by responsibility:

```text
composeApp/src/androidMain/kotlin/dev/veyra/browser/
  AndroidBrowserEngineFactory.kt
  AndroidChromiumSurfaceRenderer.kt
  # Add focused engine/session/runtime classes only where ownership requires them.
```

- The engine adapter translates the stable core commands and native callbacks into normalized state/events/errors.
- The renderer/surface adapter attaches and detaches the native rendering surface without owning tab metadata or deciding when a tab session closes.
- The engine/session owner is responsible for idempotent close and native resource release.
- Shared runtime/process resources, if required by the chosen embedding, have a lifecycle owner separate from individual tabs.
- A tab switch or surface detach must not silently destroy a live session.
- Closing a tab closes only its session; closing the application/runtime is a separate lifecycle operation.

## 4. Capability and security gates

Before marking a capability supported, provide implementation evidence and tests for the selected runtime:

- Navigation decisions and main-frame errors.
- Request interception scope and limitations (main frame vs subresources).
- Download candidate callbacks, explicit user/policy acceptance, transfer start, cancellation and cleanup.
- Popup/new-window behavior.
- JavaScript bridge origin/frame validation and permission enforcement.
- Normal/private profile isolation for cookies, cache, history, storage and session restore.
- WebExtensions or other extension support, if required by product scope; do not claim compatibility without a working end-to-end test.
- Renderer crash/session-loss handling and recovery.

Unsupported or partially supported capabilities must be represented honestly by the capability model. Never return fake success for a feature the runtime cannot provide.

## 5. Packaging and operational validation

Record the concrete runtime/version and verify:
1. Stable pinned dependency and reproducible resolution.
2. Supported Android API levels, ABIs and JDK/toolchain compatibility.
3. License and distribution requirements.
4. APK/AAB size impact and cold-start cost.
5. Runtime updates and security-patch strategy.
6. Emulator/device smoke tests for navigation, lifecycle and process recreation.

## 6. Implementation sequence

1. Audit the single `BrowserEngineContract`, `BrowserController`, `BrowserEngineManager` and the existing Android adapter before adding engine features.
2. Add focused tests for session ownership, active-tab switching, detach/reattach, idempotent close and stale navigation events.
3. Implement the chosen Chromium runtime behind the single stable engine contract.
4. Validate core navigation and lifecycle on a real Android emulator/device.
5. Add capability-specific features only after their acceptance tests and runtime limits are documented.
6. Keep iOS/WKWebView, HarmonyOS/ArkWeb and desktop/KCEF adapters aligned to the same core contracts without claiming identical capabilities.

## 7. Historical runtime decision (2026-10-23; superseded on 2026-10-24)

**Audit of the existing adapter (plan §2 / §6.1).** `AndroidBrowserEngineFactory.kt` is a
six-line `actual fun` delegating to `SurfaceBoundBrowserEngineFactory` (defined in the commonMain
`ComposeWebViewEngineAdapter.kt`). `AndroidChromiumSurfaceRenderer.kt` renders through
`android.webkit.WebView` via `AndroidView`, with secure defaults already in place: JavaScript on,
`allowFileAccess`/`allowContentAccess`/file-URL access off, `MIXED_CONTENT_NEVER_ALLOW`,
`safeBrowsingEnabled`, SSL errors cancelled (never `proceed()`), and renderer-gone → recreate the
surface. **The Android path therefore already uses the device's Chromium WebView; it does not bundle
a custom kernel.**

### Previous decision: Android System WebView as the final runtime (superseded)

- It *is* Chromium. Google ships Android System WebView as a Chromium build, distributed through
  Google Play on the same cadence as Chrome (versions 146/155 observed in 2026). This satisfies the
  plan's "Chromium-based runtime" requirement on essentially every device ≥ Android 7/8.
- Zero APK cost (shared system component), automatic security patches from Google, and no custom
  build infrastructure.
- Already isolated: every native type stays in `androidMain`; `browser-core` depends only on the
  platform-neutral `BrowserEngineContract`. A bundled embedder can later replace this implementation
  without touching core (the plan's own forward note in §2).

### Previous rationale for deferring bundled Chromium (historical; no longer the product decision)

Self-building Chromium (Content API) or a ChromeView-style library would add **80–150 MB+ to the
APK**, require ~64 GB RAM / hundreds of GB of disk for the build, and shift 0-day patching onto us
(recompile and re-release per CVE). GeckoView is the only Maven-friendly bundled option but is the
Firefox engine, not Chromium — excluded by this plan. Crosswalk is unmaintained (dead since 2017/2019).
Revisit a bundled runtime **only** if product needs demand device-uniform rendering or features the
system WebView cannot expose.

### Recommended incremental upgrade (same runtime): `androidx.webkit`

`androidx.webkit:webkit` provides `WebViewCompat` and a modern, forward-compatible API without bundling
a kernel. Adopt it when we need features gated on a newer WebView (for example WebMessage ports or
process metrics) rather than as a runtime change. It does not alter the selected runtime.

### Requirement mapping (plan §1)

| Requirement | System WebView status |
| --- | --- |
| Navigation / request interception | Main-frame only via `shouldOverrideUrlLoading`; subresources excluded from top-level policy (matches matrix) |
| Downloads | Verified on emulator; `downloads = true` |
| Popup / new-window | Unsupported (matrix honest) |
| Profile isolation | Unsupported (WebView shares the app cookie jar; matrix honest) |
| Extensions | Unsupported (matrix honest) |
| Update strategy | Google Play auto-update — an operational advantage |
| Licensing | Chromium/Apache; no new obligation beyond the app's own |
| ABI / min API | Device-dependent; effectively the WebView floor (≈ Android 7) |
| APK size / cold start | Minimal (shared component) |

### Remaining verification gates before advertising more capabilities (plan §4 / §5 / §6)

1. **`navigationPolicy` gate** (matrix, "Current navigation-policy verification gate"): an Android
   integration test proving allow-loads-once / rejected-does-not-load / custom-scheme-to-host /
   subresource-excluded / late-callback-cannot-navigate, then flip `navigationPolicy = true`.
2. Session-ownership tests (plan §6.2): active-tab switch, detach/reattach, idempotent close, stale
   navigation events. `ComposeWebViewEngineTest` already covers these at the contract level; confirm
   them under the Android renderer path specifically.
3. Emulator/device smoke test (plan §5.6): navigation, lifecycle, and renderer-process recreation.
4. Decide `androidx.webkit` adoption as an API-surface upgrade, not a runtime change.

This decision is reversible: the architecture already keeps the runtime behind `androidMain` and the
single `BrowserEngineContract`, so moving to a bundled Chromium later is a localized adapter change.

**Superseded 2026-10-24 by explicit user decision:** System WebView is now the temporary functional-validation runtime, not the final engine choice. The long-term target is a self-compiled Chromium kernel. Keep the same common contract and platform boundary so the kernel replaces the WebView implementation without rewriting Browser Core. Do not claim kernel support until the packaging and runtime acceptance gates below have evidence.

## 8. Current decision — native WebView validation, self-compiled Chromium target (2026-10-24)

### Purpose and non-goals

- Continue using `android.webkit.WebView` as the real native runtime for fast, emulator-verifiable vertical slices.
- Validate the production adapter path end-to-end: document load/state callbacks, navigation policy, downloads, surface detach/reattach, renderer loss/recovery, security defaults and close semantics.
- Keep `BrowserEngineContract` as the only engine contract and `BrowserEngineManager` as the only live-engine registry. Keep Android SDK types in `androidMain`.
- The System WebView is a temporary validation implementation, not a commitment to ship the device-provided kernel.
- The future self-compiled Chromium integration must replace the implementation behind the same adapter boundary; do not create a parallel core or second registry.

### Required sequence

1. **Native WebView baseline (current):** build and run the smoke/instrumentation suite on the API 35 emulator; keep capability flags limited to evidence from this implementation.
2. **Complete the Android adapter vertical slice:** wire native callbacks to normalized state/events, validate command delivery and lifecycle under active-tab changes, surface disposal/re-attachment, renderer crash and shutdown. Document unsupported capabilities honestly.
3. **Define the self-compiled Chromium build plan:** select and document the Chromium source revision/build target, reproducible toolchain, supported ABIs/API levels, licensing/NOTICE obligations, artifact distribution, APK/AAB size, cold start and security patch cadence before committing to implementation details.
4. **Build the kernel separately from app integration:** prove a minimal Android embedder can build and render on the API 35 emulator, then capture artifact size and startup measurements.
5. **Replace the WebView surface/runtime behind the existing adapter boundary:** preserve core contracts and run the same applicable adapter conformance tests plus Chromium-specific tests. Never inherit WebView capability flags without fresh evidence.

### Current evidence and gaps

- Existing tests cover real WebView top-level navigation-policy enforcement, download flow, session/surface ownership and renderer-crash recovery at the current prototype boundary.
- `AndroidWebViewRuntimeSmokeTest` has been added as the first baseline gate for native document loading, callback delivery and secure settings. Its result is pending CI after this commit.
- No self-compiled Chromium source build, embedder, packaged kernel, ABI matrix, license audit, APK-size measurement or kernel-specific test has been completed yet. These remain future work and must not be described as implemented.
