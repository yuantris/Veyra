# AGENTS.md — Veyra engineering rules

This file is the repository-wide source of truth for AI coding agents and human contributors. Read it before changing code. These rules preserve decisions made across earlier development sessions so work can continue without silently changing direction.

## 1. Project goal and current phase

Veyra is a Kotlin Multiplatform / Compose Multiplatform browser for Android, iOS, HarmonyOS and desktop.

**Current priority: make the platform-neutral browser core correct and testable before expanding native-engine integration.** Do not interpret a request to "continue" as permission to jump to Android UI, add a new engine integration, or polish the UI. Continue from the core architecture and its unmet acceptance gates.

Compose is the presentation/assembly layer. It must not become the owner of browser session lifetime, navigation policy, security decisions, or the authoritative live-engine registry.

## 2. Fixed product and engine direction

The selected target direction is:

- Android: Chromium-based runtime. The exact embeddable/runtime implementation is subject to the explicit evaluation gates in `docs/CHROMIUM_ANDROID_PLAN.md`; do not claim a bundled Chromium runtime, WebExtensions, or equivalent capabilities merely because Android System WebView uses Chromium.
- iOS: WKWebView.
- HarmonyOS: ArkWeb.
- Desktop: KCEF.

Android is the first planned native-engine integration after core acceptance, not a reason to weaken or platform-contaminate common contracts. Do not switch Android back to GeckoView or treat System WebView as an already-approved final runtime without an explicit decision backed by the Chromium plan.

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
- **`download-core`**: owns download implementation and task/transport orchestration behind the browser's download contracts. `browser-core` must not depend on `download-core` implementation.

Keep dependency direction one-way: common contracts/core must not depend on Compose UI, Android SDK, iOS frameworks, ArkUI/native engine types, or a platform implementation. Avoid adding Gradle modules unless a real dependency/lifecycle boundary requires one and the reason is documented. Prefer cohesive packages over many tiny abstractions.

## 5. Non-negotiable lifecycle rules

1. A tab's metadata, browser session/engine, and attached rendering surface are separate concepts.
2. Switching tabs or detaching/recreating a Compose surface must not implicitly close a session or erase navigation state.
3. Closing a tab is an explicit operation that closes its engine/session and releases resources owned exclusively by that tab.
4. App shutdown closes all owned sessions and waits for in-flight create/close work to settle.
5. Closing one tab must not tear down a runtime/process resource shared by other tabs.
6. Engine close and controller shutdown should be idempotent; concurrent callers must observe a defined outcome.
7. Late callbacks/events from a closed tab or superseded navigation must not mutate current state.
8. Creation failure must not leave a registered half-open engine or orphaned tab metadata. Cleanup failures must be observable; do not silently swallow them.
9. Cancellation is not an ordinary engine failure. Preserve coroutine cancellation where safe, while using `finally`/completion signaling so waiters cannot hang.
10. Do not "fix" a native resource leak by simply removing `destroy()`; define one explicit resource owner and release path first.

Before implementing native surfaces, document and test the attach/detach/re-attach/crash/close protocol. Surface disposal is not session disposal.

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
4. **Run the narrowest relevant tests, then the repository's CI tasks.** Current CI includes ` :browser-core:jvmTest`, `:download-core:jvmTest`, and `:composeApp:desktopTest`; inspect the workflow before relying on this list.
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

The repository has a common engine contract, `BrowserSessionCoordinator`, controller, engine manager, metadata store, navigation coordinator, profile abstractions, download core and a platform-neutral bridge authorization primitive. `App.kt` should use `BrowserSessionCoordinator.create(...)` and must not rebuild the store/controller/manager graph or reconcile their flows itself. These are the starting points to audit—not proof that all core acceptance gates are complete.

In particular:
- The bridge authorization primitive is not a complete end-to-end JS bridge.
- An Android WebView prototype is not proof that the selected bundled/embeddable Chromium runtime decision has passed its packaging, license, ABI, size, startup and capability gates.
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
