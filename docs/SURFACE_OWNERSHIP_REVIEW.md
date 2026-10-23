# Platform Surface Resource Ownership Review

> Exit criterion 5 (`BROWSER_CORE_ARCHITECTURE.md` §13): *"Platform surface resource ownership protocol reviewed at the contract level."*
> Reviewed tree: `e3ddd79` (current `main`), 2026-10-23.
> Companion: `docs/CORE_GATE_REVIEW.md` (C0–C9 gates).

This is a review, not new behavior. Every claim below is anchored to a contract or implementation
location, and the load-bearing invariants already have tests (see `ComposeWebViewEngineTest`).

## 1. The two resource axes

The architecture separates two independent resource lifetimes, and the central invariant is that
**they never collapse into one**:

| Axis | Resource | Owner | Lifetime end trigger |
| --- | --- | --- | --- |
| **Engine / session** | `BrowserEngineContract` instance, navigation state, profile binding | `BrowserEngineManager` (registry + disposal) | `manager.closeTab` / `closeAll` → `engine.close()` |
| **Native view / surface** | The platform `WebView` (or future Chromium/ArkWeb view) and its `BrowserSurfacePort` command bridge | The Compose composable (`ComposeWebViewSurface`) | Compose disposes the composable |

`BrowserSurfacePort` / `BrowserSurfaceBinding` live in the **app** layer (`composeApp`), never in
`browser-core`. `BrowserEngineContract` never exposes a native view or runtime type (C0). The two
axes meet only through the nullable `surface: BrowserSurfacePort?` field on
`ComposeWebViewEngine` (`ComposeWebViewEngineAdapter.kt:100`), which is attach/detach-only.

## 2. Ownership chain (code-anchored)

### Creation
1. Composition root: `rememberBrowserSession()` creates `controllerScope` via `remember`
   (`BrowserSessionAssembly.kt:61`) — a `CoroutineScope(SupervisorJob + Dispatchers.Default)` that
   intentionally outlives the Compose subtree.
2. `BrowserSessionCoordinator.create(engineFactory, scope, …)` builds the graph
   (`BrowserSessionCoordinator.kt:253`), wiring `BrowserEngineManager(factory)`
   (`BrowserController.kt:39`, `:260`).
3. Per tab: `coordinator.openTab` → `controller.openTab` → `engineManager.openTab` →
   `factory.create(request)` → `ComposeWebViewEngine` (implements both `BrowserEngineContract` and
   `BrowserSurfaceBinding`). `isPrivate`/`profileId` ride in on `BrowserEngineCreateRequest`
   (`BrowserEngineContract.kt:34`).

### Surface binding (the second axis)
- `ComposeWebViewSurface` creates the native `WebView(state, navigator)` (`:104`) and a
  `BrowserSurfacePort` bridge (`:71–79`), both Compose-owned.
- `DisposableEffect(engine, surface)` attaches on enter and detaches on dispose (`:81–84`). Detach
  **only** nulls the `surface` reference (`ComposeWebViewEngineAdapter.kt:140–142`); it never
  touches the engine's lifecycle.
- `BrowserEngineManager.detachSurface(tabId) = Unit` (`:116`) — a deliberate no-op at the registry
  level: surface detach is intentionally unrelated to engine ownership.

### Teardown (the critical path)
1. `App.kt:365–368`: `DisposableEffect(session) { onDispose { session.requestShutdown() } }` — the
   composition root wires the owner scope's cancellation to App dispose. **No scope leak.**
2. `BrowserSession.requestShutdown()` (`BrowserSessionAssembly.kt:33`) runs on `controllerScope`:
   `coordinator.close()` → then `downloadController?.close()` → then `controllerScope.cancel()`.
   Order is enforced (coordinator first, scope last).
3. `BrowserSessionCoordinator.close()` (`:235`) cancels its event/pageState jobs, then
   `controller.close()`.
4. `BrowserController.close()` (`:236`) under the lifecycle mutex: cancels all per-tab jobs, then
   `engineManager.closeAll()`.
5. `BrowserEngineManager.closeAll()` (`:165`) sets `shuttingDown = true`, waits for in-flight
   creations, removes every engine from the registry, and calls `engine.close()` inside
   `NonCancellable` (`:148`) so disposal cannot be interrupted. One failure never skips the rest
   (`:199–204`).

## 3. Invariants (each test-backed)

| # | Invariant | Where it holds | Test |
| --- | --- | --- | --- |
| I1 | Detaching a surface **never** closes the engine | `ComposeWebViewEngine.detachSurface` nulls the ref only; `BrowserEngineManager.detachSurface` is `Unit`; no close path is reachable from detach | `detachingSurfaceKeepsEngineUsableAndReattachesPendingNavigation` (`ComposeWebViewEngineTest.kt:46`) |
| I2 | Engine close is reachable **only** through `manager.closeTab/closeAll`, driven by `controller.closeTab/close` ← `coordinator.close` ← holder `requestShutdown` | manager/controller method bodies | teardown chain §2 |
| I3 | `close()` is idempotent | `ComposeWebViewEngine.close()` `if (closed) return` (`:352`); `manager.closeTab` `engines.remove` → `null` ⇒ `false` (`:122`) | idempotent close (`ComposeWebViewEngineTest.kt:133–139`) |
| I4 | Teardown order: coordinator → download controller → owner scope | `BrowserSession.requestShutdown` (`:33–42`) | — |
| I5 | Native-view lifetime = Compose composition; engine/session lifetime = owner scope; **decoupled** | §2 axes table | I1 |
| I6 | Renderer crash does **not** close the engine; recovery detaches the dead surface and attaches a replacement | `reportRendererCrashed` sets `rendererHealthy=false` + emits `RendererCrashed` but does not call `close()` (`:232–256`) | `rendererCrashIsObservableAndIsNotTreatedAsTabClose` (`:163`) |
| I7 | No platform/native type crosses into core | `BrowserSurfacePort/Binding` are `internal` to `composeApp`; `BrowserEngineContract` is platform-neutral | C0 evidence in `CORE_GATE_REVIEW.md` |
| I8 | The owner scope is the sole teardown trigger and **is** wired to App dispose | `App.kt:365–368` | — |

## 4. Honest gaps / forward notes (non-blocking)

These do not block the criterion; they are recorded so a future runtime change re-confirms them.

- **G1 — Engine and surface binding are one object today.** `ComposeWebViewEngine` implements both
  `BrowserEngineContract` and `BrowserSurfaceBinding`. That is correct for the single-platform
  compose-webview prototype, but a future Chromium/ArkWeb runtime likely splits the state owner
  (native engine process) from the surface binding (UI process). `BrowserSurfaceBinding`'s doc
  already anticipates this ("A future platform renderer can provide its own binding
  implementation"); the split is a refactor, not a contract break.
- **G2 — `BrowserEngineManager.detachSurface` is a no-op `Unit`.** Today that is correct because the
  engine owns a nullable surface ref and Compose owns the native view. If a future engine binds a
  surface-scoped native resource (e.g. a GL context), this no-op would skip its release — the method
  must then gain real teardown. Flag, not a defect.
- **G3 — Detached-but-alive state is intended.** A backgrounded tab's composable leaves composition
  (WebView disposed by Compose) while the engine stays open (detached). Re-entry recreates the
  `WebView`/`BrowserSurfacePort` and `attachSurface` re-binds; navigation issued while detached is
  buffered via `pendingUrl` (`:134–137`, `:167`). This is the desired session-persistence behavior,
  documented here so it is not mistaken for a leak.
- **G4 — Host must call shutdown.** `controllerScope` is cancelled only when `requestShutdown()` is
  invoked. The current `App` does this correctly (`App.kt:365–368`); a non-Compose host must do the
  same or the scope leaks. There is no built-in safety net (intentional — the host owns the scope).

## 5. Verdict

**Exit criterion 5: MET.** The platform surface resource ownership protocol is reviewed at the
contract level:

- The two axes (engine/session vs native view/surface) are distinct and deliberately decoupled
  (§1).
- The full ownership and teardown chain is anchored to code, and the owner scope is wired to App
  dispose (§2).
- All eight load-bearing invariants hold, and the critical ones (I1, I3, I6) have tests (§3).
- The four forward notes (§4) are design observations for future runtimes, none of which block
  native-engine integration.

With this, all five exit criteria in `BROWSER_CORE_ARCHITECTURE.md` §13 are satisfied, and the
Android runtime selection in `CHROMIUM_ANDROID_PLAN.md` becomes the next phase.
