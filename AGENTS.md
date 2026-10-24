# AGENTS.md — Veyra engineering rules

This file is the repository-wide source of truth for AI coding agents and human contributors. Read it before changing code. These rules preserve decisions made across earlier development sessions so work can continue without silently changing direction.

## 1. Project goal and current phase

Veyra is a Kotlin Multiplatform / Compose Multiplatform browser for Android, iOS, HarmonyOS and desktop.

**Current phase (explicitly selected by the project owner): Android Miuix UI for phones and tablets.** Follow the ordered acceptance gates in `docs/ANDROID_MIUIX_ACCEPTANCE.md`. Do not schedule desktop UI work until the Android phone/tablet gates pass. Do not add a new engine integration as part of this UI phase. The platform-neutral browser core and its lifecycle/security ownership invariants remain normative; UI work must preserve them.

Compose is the presentation/assembly layer. It must not become the owner of browser session lifetime, navigation policy, security decisions, or the authoritative live-engine registry.

## 2. Fixed product and engine direction

The selected target direction is:

- Android: the long-term target is a **self-compiled Chromium kernel**. Use Android System WebView (`android.webkit.WebView`) now as a temporary native runtime to validate the adapter contract, navigation, lifecycle, security defaults and browser features. Do not treat System WebView as the final engine, and do not claim the self-compiled kernel is integrated until its build, packaging and emulator gates pass. See `docs/CHROMIUM_ANDROID_PLAN.md`.
- iOS: WKWebView.
- HarmonyOS: ArkWeb.
- Desktop: KCEF.

Android is the first native-engine integration. The current System WebView is a validation adapter only; it must remain replaceable behind the existing `BrowserEngineContract` and platform surface boundary by the future self-compiled Chromium runtime. Do not introduce GeckoView or a second engine contract.

## 3. Read these documents first

Before architecture changes, inspect the relevant current versions of:

1. `docs/ARCHITECTURE.md` — overall architecture and dependency direction.
2. `docs/BROWSER_CORE_ARCHITECTURE.md` — normative core contracts and C0–C9 acceptance gates.
3. `docs/PLATFORM_CAPABILITY_MATRIX.md` — truthful platform capability status.
4. `docs/CHROMIUM_ANDROID_PLAN.md` — Android runtime decision gates; do not start this work before core acceptance.
5. The relevant adapter/design document under `docs/` for the feature being changed.

If this file and a detailed architecture contract appear to conflict, do not guess: preserve the stricter safety/lifecycle invariant, inspect the implementation, and update the documents together as part of an explicit decision. Record any changed architectural decision in the relevant design document and this file.

## 4. Core ownership and dependency boundaries

The intended ownership model is:

- **`BrowserSessionCoordinator`**: the application-facing tab/session orchestration layer. It serializes metadata↔engine workflows, resolves profile identity, and projects normalized engine state/events into tab metadata. Compose observes this coordinator and forwards user commands; it must not reconcile the store against the engine registry itself.
- **`BrowserController`**: the engine command facade. Owns orchestration of engine open/navigate/back/forward/reload/stop/close and controller-visible page state. It is not a second engine registry.
- **`BrowserEngineManager`**: the single authoritative registry/owner of live engine instances and their creation/closure lifecycle. It must handle concurrent creation, close-during-create, close-during-close, shutdown-during-create, failures and cancellation deliberately.
- **`BrowserSessionStore`**: tab/session metadata only. It must not own or implicitly create/destroy native engines. Metadata and a live rendering surface are not the browser session.
- **`BrowserEngineContract`**: the sole active platform-neutral engine/session contract. Do not reintroduce legacy `BrowserEngine`, `BrowserEngineRegistry`, or a second engine abstraction/registry.
- **Platform adapters**: translate native engine callbacks and resources to common contracts. Native view/runtime types must not leak into `browser-core`.
- **Compose UI**: presents state and forwards user actions. It must not create a competing lifecycle authority.
- **`browser-download`**: owns download implementation and task/transport orchestration behind the browser's download contracts. `browser-core` must not depend on `browser-download` implementation.

Keep dependency direction one-way: common contracts/core must not depend on Compose UI, Android SDK, iOS frameworks, ArkUI/native engine types, or a platform implementation. Avoid adding Gradle modules unless a real dependency/lifecycle boundary requires one and the reason is documented. Prefer cohesive packages over many tiny abstractions.

## 4a. Logical module map and package layout

`docs/BROWSER_CORE_ARCHITECTURE.md` §2 defines the logical boundaries. This section records where each one physically lives today. It names ownership; it does not authorize new Gradle modules (see §4).

| Logical module | Responsibility | Current home |
| --- | --- | --- |
| browser-core | shared contracts and vocabulary: IDs, models, events, errors, capabilities, policy request/result types, engine contract | `:browser-core`, package `dev.veyra.browser.core` (root) |
| browser-session | tab/session lifecycle, metadata, session restore, private-profile association | `:browser-core`, packages `.engine` (registry + command facade) and `.session` (coordinator + store) |
| browser-security | URL validation, origin policy, permission decisions, bridge authorization | `:browser-core`, packages `.navigation` and `.security` |
| browser-download | download task model and task/transport orchestration | `:browser-download`; contracts in the `dev.veyra.browser.core` root package |
| browser-extensions | extension identity/permission contracts | contracts only, `dev.veyra.browser.core` root; no runtime implementation exists |
| browser-platform-<os> | native engine instance, callback mapping, JS injection, interception, permission execution | platform source sets of `:composeApp` (androidMain / iosMain / desktopMain) |
| composeApp | shared Compose UI and interaction | `:composeApp` |

Package dependency direction inside `:browser-core`:

- `core` (root): contracts only; depends on no sub-package and on no platform or Compose type.
- `engine`: depends on root and `navigation`.
- `session`: depends on root and `engine`.
- `navigation`, `security`: depend on root only.

Adapters and UI implement or observe the root contract surface plus the `session` package API; they must not reach into `.engine`, `.navigation` or `.security` internals. Gradle does not enforce this direction — review and tests do. Promote a package to a Gradle module only when a documented dependency, build, test or release boundary requires it.

Native-type rule, stated checkably: `WKNavigationDelegate`, `CefLoadHandler`, Android `WebView`/`GeckoSession`, ArkWeb/ArkUI types and every other platform engine type must not appear in `:browser-core` or in `commonMain` browser code. Core depends only on Veyra-defined interfaces and event models.

History and bookmarks: `BrowserSessionStore` owns in-memory history/bookmark metadata today. There is no persistence owner, restore policy or cross-profile policy yet; treat persistence as unsupported until an explicit decision is recorded here.

## 5. Non-negotiable lifecycle rules

1. A tab's metadata, logical browser session, live engine/runtime, native rendering surface, and visibility/activation state are separate concepts.
2. Tab selection is a UI/state transition, not an engine-lifecycle operation. Switching tabs or detaching/recreating a Compose surface must not implicitly close a session or erase navigation state.
3. Only the selected tab should be presented as the active page surface. Do not require every background tab's native view to remain attached, composed, or visible just to preserve its logical session.
4. Surface visibility, surface attachment, engine/session liveness, and renderer-process liveness must be modeled as distinct states. Detaching or hiding a surface must not be treated as closing the session.
5. A normal tab switch should reuse the existing live session when available; it must not create a replacement engine merely because the tab became inactive or the window resized.
6. Background resource policy is explicit and capability-aware: adapters may throttle/freeze background work, detach native views, or evict/recreate renderer resources under documented memory pressure/product policy. They must not silently close the logical tab/session or pretend in-memory JavaScript/DOM state survived an eviction or process crash.
7. If an engine/session is evicted or lost, surface a typed recoverable session/renderer-loss event, preserve restorable tab metadata (URL/history/profile where supported), and expose whether restoration is full, partial, or impossible. Do not promise scroll/DOM/JS continuity when the platform cannot serialize it.
8. Closing a tab is an explicit operation that closes its engine/session and releases resources owned exclusively by that tab.
9. App shutdown closes all owned sessions and waits for in-flight create/close work to settle.
10. Closing one tab must not tear down a runtime/process resource shared by other tabs.
11. Engine close and controller shutdown should be idempotent; concurrent callers must observe a defined outcome.
12. Late callbacks/events from a closed tab or superseded navigation must not mutate current state.
13. Creation failure must not leave a registered half-open engine or orphaned tab metadata. Cleanup failures must be observable; do not silently swallow them.
14. Cancellation is not an ordinary engine failure. Preserve coroutine cancellation where safe, while using `finally`/completion signaling so waiters cannot hang.
15. Do not "fix" a native resource leak by simply removing `destroy()`; define one explicit resource owner and release path first.

Before implementing native surfaces, document and test the attach/detach/re-attach/background/eviction/crash/close protocol. Surface disposal is not session disposal; renderer eviction is not tab closure.

## 6. Security and permission rules

### Navigation
- Centralize platform-neutral decisions in the navigation policy/coordinator; adapters must invoke it for navigation sources they can intercept.
- Treat main-frame navigation, subresources, redirects, popups/new tabs, and external schemes as distinct cases.
- Never silently load an external/new-tab decision in the current tab.
- Reject malformed and executable schemes by default. File/content/data/blob/intent-like schemes require explicit product/profile policy.
- Do not claim the common coordinator protects a navigation source that a native adapter cannot intercept. Declare that limitation in the capability matrix.

### JavaScript bridge
- Page-provided JSON is untrusted. Never accept an origin, frame identity, profile identity, or extension identity from the message payload as authoritative.
- The adapter must obtain document/frame security context from trusted engine callbacks and route every bridge call through the common authorization gate.
- Use exact canonical origin matching, not substring/suffix matching. Missing/opaque origin, untrusted frame, unapproved profile/extension, unsupported protocol, unknown method and oversized payload must fail closed.
- Authorization is not execution: bridge dispatch must separately enforce method permissions, request lifecycle, duplicate IDs, cancellation, timeouts, and response routing.
- Do not enable or advertise native messaging until end-to-end adapter tests demonstrate these properties.

### Permissions and privacy
- Website permission, OS runtime permission, and Veyra/extension capability permission are distinct layers. OS permission alone must not grant website permission.
- Denied, cancelled, unsupported, timed-out, and granted are distinct outcomes.
- A private profile is a storage/security boundary, not just a UI flag. Never silently fall back to regular persistent storage, history, cookies, cache, or permission grants.
- Capability flags must describe what the adapter can actually enforce and what tests prove—not what the underlying engine might theoretically support.

### Downloads and interception
- A download callback means a download candidate, not consent and not automatic transfer. Explicit policy/user acceptance is required before starting.
- Validate task/profile/tab ownership, cancellation, duplicate requests, and close-vs-start races.
- State the scope of interception truthfully (main-frame/subresource, request metadata, response body, etc.). Mark unsupported scope explicitly.

## 7. Kotlin and implementation quality

- Prefer idiomatic Kotlin and common Kotlin APIs for common code.
- In common code, value classes require an explicit `import kotlin.jvm.JvmInline`. The JVM/Android target has `kotlin.jvm.*` in its default imports, so a missing import passes JVM compilation and only fails on iOS/native targets. The `ios-compile` CI job guards this; never remove iOS targets from CI to avoid it.
- Avoid `!!`; use explicit types and handle nullable/failed states intentionally.
- Keep public contracts small, documented and stable. Make invalid states difficult to represent.
- Do not catch `Throwable` and silently convert it to success. If cleanup catches failures to unblock waiters, preserve/report the failure and rethrow cancellation where appropriate.
- Do not add speculative layers, duplicate state stores, global mutable registries, or platform-specific workarounds to common code.
- Do not change public API semantics merely to make a test pass. Fix the contract or implementation and add regression coverage.
- Avoid claiming a feature is complete because its interface or documentation exists.

## 8. Required workflow for every change

1. **Inspect before editing.** Read the existing contract, implementation, related tests, and docs. Do not assume file names or APIs from an earlier conversation are still accurate.
2. **State the invariant being fixed.** Prefer one coherent change that enforces a clear rule over broad unrelated refactoring.
3. **Implement tests with the change.** Include negative, failure, concurrency, and lifecycle cases where applicable.
4. **Run the narrowest relevant tests, then the repository's CI tasks.** Current CI includes `:browser-core:jvmTest`, `:browser-download:jvmTest`, and `:composeApp:desktopTest`; inspect the workflow before relying on this list.
5. **Read CI results.** A commit is not proof of passing tests. Report running, failed, skipped and passing checks accurately. Fix failures; do not bypass or disable tests to get a green run.
6. **Update docs and capability claims** when contracts, ownership, supported behavior, or decisions change.
7. **Summarize the exact files/commits changed, evidence from tests, remaining gaps, and the next smallest safe step.** Never imply work was done if it was only proposed.

For concurrent code, use deterministic coroutine tests with explicit barriers/deferreds rather than sleeps. Test races such as duplicate open, close during create, concurrent close, shutdown during create, failure during cleanup, and late events.

## 9. Core readiness gate — do not skip

Before expanding Android/native engine integration, review `docs/BROWSER_CORE_ARCHITECTURE.md` and demonstrate evidence for each category:

- C0: dependency direction and common compilation.
- C1: engine creation/registry/closure concurrency, cancellation and failure semantics.
- C2: session-vs-surface lifecycle and exclusive/shared resource ownership.
- C3: metadata/controller/engine consistency, private and last-tab behavior, stale callbacks.
- C4: navigation policy and source-specific interception limits.
- C5: bridge origin/frame/protocol/method authorization and end-to-end dispatch security.
- C6: website/OS/extension permission separation and private-profile isolation.
- C7: download/interception consent, ownership, races and cancellation.
- C8: truthful capability matrix backed by tests.
- C9: adapter boundary, surface ownership and renderer failure semantics.

A gate is not complete merely because a document, interface, or unit test exists; the implementation and evidence must match. Explicitly mark deferred features unsupported. The common/JVM CI suite must pass. If any gate is unresolved, continue core work and explain the blocker instead of moving to Android.

## 10. Current known implementation boundary

The repository has a common engine contract, `BrowserSessionCoordinator`, controller, engine manager, metadata store, navigation coordinator, profile abstractions, `:browser-download` and a platform-neutral bridge authorization primitive. `App.kt` should use `BrowserSessionCoordinator.create(...)` and must not rebuild the store/controller/manager graph or reconcile their flows itself. These are the starting points to audit—not proof that all core acceptance gates are complete.

In particular:
- The bridge authorization primitive is not a complete end-to-end JS bridge.
- The Android System WebView validation adapter is not proof that the future self-compiled Chromium kernel has passed build reproducibility, license, ABI, package-size, startup, security-update and capability gates.
- A Compose surface being visible is not proof that tab/session state survives surface disposal.
- Architecture documents define desired behavior; CI and implementation tests must establish actual behavior.

## 11. Collaboration and change discipline

When continuing work from prior AI sessions:
- Use this file and the current repository as the source of truth, not remembered summaries alone.
- Preserve the agreed engine direction and core-first sequence.
- Do not revert deliberate removals of legacy abstractions without explicit architectural review.
- Do not silently change public names, module boundaries, engine selection, privacy semantics, or capability claims.
- If a requirement is ambiguous and affects security, persistence, API compatibility, or engine ownership, inspect the existing contract and document the decision before coding.
- Prefer completing a vertical slice (contract + implementation + tests + docs) over adding many unfinished interfaces.
- Be candid about uncertainty and CI state. Never claim tests passed unless their actual result was observed.
- **Record the session in `docs/AI_WORK_LOG.md`.** Multiple AI sessions and human contributors take turns in this repository; the log is how they stay aligned. Before finishing, append one entry stating what you planned, what you completed (with test evidence), and the next smallest safe step, then refresh the status snapshot at the top of that document. The log is append-only: never rewrite or delete earlier entries, and never record proposed work as completed.

## 12. UI/UX standards (fixed presentation decision)

The browser's Compose UI uses the **miuix** library (`top.yukonga.miuix.kmp`, currently `0.9.4`: `miuix-ui` + `miuix-icons`). This is a fixed presentation decision comparable to the engine direction in §2; do not revert to Material3 or introduce a second UI toolkit without an explicit decision recorded here. The target look-and-feel is derived from the Luna browser reference (Weibo topic **#Luna浏览器#**).

### Visual language
- **Floating, inset-separated chrome.** The address bar is a top pill (lock + url + stop/reload) floating below the status-bar inset; the primary navigation is a bottom toolbar (back, forward, tab count / new-tab, bookmarks, menu, home) floating above the bottom inset. Content is padded by `WindowInsets.systemBars`.
- **New-tab home screen** with: a large search pill ("搜索或输入网址"), a favorites row (site monograms), a suggestions row (horizontal cards), a privacy-report card (shield glyph + "已阻止 N 个跟踪器"), and a customize pill button.
- **Privacy-first emphasis.** Privacy/reporting is a first-class, visible surface, not buried settings.
- **Clean, light palette with large radii** (≈20–24 dp) and subtle elevation on floating elements. Follow miuix's HyperOS/MiUI aesthetic via `MiuixTheme.colorScheme` tokens, not hard-coded colors.

### Implementation rules
- Wrap the app in `MiuixTheme(colors = lightColorScheme()/darkColorScheme())` and use `Scaffold(contentWindowInsets = WindowInsets.systemBars)`. Read colors from `MiuixTheme.colorScheme.*` (surface, surfaceContainer, primary, onBackground, error, …).
- `:composeApp` is the only presentation module. UI renders state from `BrowserSessionCoordinator` and forwards user commands; it must **not** own session lifecycle, navigation policy, or the engine registry (see §4, §10). `App.kt` is the composition root.
- Keep the address bar, bottom toolbar, and home screen as separable composables so new-tab vs in-page states swap cleanly.

### Responsive browser shell (required next UI phase)
- After the Android native WebView validation vertical slice is stable, implement one standard browser UI entry point in `:composeApp` (for example `BrowserScaffold` / `BrowserScreen`), assembled from reusable address bar, tab switcher, web-content host, browser controls, and new-tab home content. Do not create a separate UI lifecycle or duplicate the engine/session graph.
- Use Compose window size classes / available window constraints and adaptive navigation patterns, not device-model checks or fixed screen-width assumptions. Support compact phones, landscape phones, tablets, foldables (including fold/unfold and posture changes), split-screen/resizable windows, and desktop-sized windows.
- Compact layouts may use a floating bottom toolbar; expanded layouts should be able to move primary controls to a side rail or wider top toolbar. Keep address/search, tab switching, navigation actions and page content reachable at every size.
- Treat safe drawing/system-bar insets and display cutouts correctly. Measure the web-content viewport from the actual remaining content area; browser chrome must not overlap page content or native WebView touch regions.
- Window-size/posture changes must only recompose/re-layout the surface. They must not close, recreate, or reset the `BrowserSession`, native engine, current URL, scroll position, or in-flight navigation.
- Add screenshot/layout tests or deterministic Compose UI tests for compact, expanded, landscape and resized-window configurations; test tab-switch and surface reattachment while resizing. Document any platform-specific adaptive limitation.
- Keep this UI task behind the engine/session contract. The UI forwards commands to `BrowserController` / `BrowserSessionCoordinator` and observes normalized state; it must not call WebView directly for browser behavior.

### Build-baseline constraints (do not regress)
miuix 0.9.4 pins a specific toolchain; the project baseline is therefore **Compose 1.12.2 / Kotlin 2.4.21 / AGP 9.4.1 / Gradle 9.6.0**. Preserve these observations:
- **No `iosX64()` target.** miuix publishes `iosArm64`/`iosSimulatorArm64` only. Under Kotlin 2.4's strict metadata resolver, a `commonMain` dependency missing a variant for any declared leaf target fails the whole graph ("Couldn't resolve … for all target platforms"). The three modules therefore declare only `iosArm64()` + `iosSimulatorArm64()`. Do not re-add `iosX64()` unless miuix (or a replacement) ships that variant.
- **AGP 9 + Kotlin Multiplatform bypass.** `gradle.properties` carries `android.builtInKotlin=false` + `android.newDsl=false` to keep the legacy `com.android.library` plugin working with AGP 9. These are stopgaps; prefer migrating `android {}` to `com.android.kotlin.multiplatform.library` to remove the deprecation warnings.
- `compose-webview` (2.0.3) is a hard dependency for Desktop/iOS surface rendering and is aligned to Compose 1.12.2 by the Compose plugin; do not remove it as "dead code."
