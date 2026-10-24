# Veyra architecture

**Status:** Normative architecture baseline  
**Principle:** Prefer the smallest set of modules that creates real ownership and dependency boundaries. Package organization is not a reason by itself to create a Gradle module.

## 1. Product and platform direction

Veyra is a cross-platform browser built with Kotlin Multiplatform and Compose Multiplatform. UI and browser product behavior are shared where appropriate; rendering engines, native callbacks and platform resource ownership remain platform-specific.

| Platform | Selected engine direction | Integration principle |
| --- | --- | --- |
| Android | Self-compiled Chromium kernel (long-term target); System WebView for temporary validation | First implementation priority; validate the adapter contract on WebView now, then replace the runtime behind the same boundary after kernel build/package gates pass |
| iOS | WKWebView | Native adapter |
| HarmonyOS | ArkWeb | Native adapter through the HarmonyOS host/toolchain |
| Desktop | KCEF / Chromium | Native adapter |

These are engine directions, not claims that every platform supports identical browser APIs or WebExtensions. Capability support must be verified and reported honestly. Do not expose GeckoView, WebKit, ArkWeb, CEF or platform UI types to `commonMain` browser-domain code.

## 2. Initial Gradle module boundaries

Keep the initial module count deliberately small:

| Module | Owns | Does not own |
| --- | --- | --- |
| `:composeApp` | App entry points, shared Compose UI, platform UI/surface adapters, dependency assembly | Browser policy implementations hidden inside UI; engine lifetime tied to recomposition |
| `:browser-core` | Stable browser contracts and shared coordination: IDs, models, engine/session ownership, navigation, normalized events/errors, capabilities and policy interfaces | Native SDKs, Compose UI, file I/O, platform-specific downloads |
| `:browser-download` | Download task lifecycle, queue/concurrency, progress, cancellation, transport abstraction and task state | Browser tab ownership, native engine objects, concrete platform file APIs |

Platform engine adapters initially live in the appropriate platform source sets and packages. Do not create a Gradle module for every logical subsystem. Navigation, tab metadata, JS bridge policy, permissions and extension use cases can begin as cohesive packages/contracts within the existing modules. Split them only when a demonstrated need exists—for example, independent dependency isolation, build/test isolation, publication, or a genuinely separate release lifecycle.

Inside `:browser-core`, the root package holds contracts only; coordination lives in the `engine`, `session`, `navigation` and `security` sub-packages. The current physical home of every logical module and the package dependency direction are recorded in `AGENTS.md` §4a; keep the two documents in sync.

A shared contract does not imply that every feature needs a shared implementation. Platform-specific behavior stays in platform adapters; unsupported capabilities are explicit.

## 3. Dependency rules

```text
                         composeApp
                       /            \\
                      v              v
               browser-core      browser-download
                      ^              |
                      |              | depends on download contracts
              platform adapters ----+
                      |
             native engine SDKs
```

The diagram shows ownership and assembly rather than requiring a strict linear dependency chain. The rules are:

1. `:browser-core` never depends on `:composeApp`, platform SDKs or concrete engines.
2. `:browser-download` may depend on browser-owned download contracts; `:browser-core` must not depend on the download implementation.
3. `:composeApp` is the composition root: it wires a controller, engine factory, optional subsystem controllers and platform adapters.
4. Common code depends on platform-neutral interfaces. Native objects do not escape their adapter/source set.
5. Dependencies point toward stable contracts. Avoid circular project dependencies and service-locator access to concrete platform implementations.
6. Prefer a package over a new module when the code shares the same owner, dependency set, test lifecycle and release cadence.

## 4. Core contract convergence

The single active engine/session abstraction is `BrowserEngineContract`. The previous `BrowserEngine` / `BrowserEngineRegistry` path and its Compose adapter have been removed; new adapters must implement the active contract rather than reintroducing a parallel API. The contract must cover:

- stable `TabId`, `SessionId`, `NavigationId`, `RequestId` and `ProfileId` identities;
- navigation commands and normalized page state/events/errors;
- capability reporting for optional platform features;
- explicit engine/session ownership, surface attach/detach and idempotent close;
- a clear distinction between tab metadata and the live native renderer/session;
- private-profile isolation as an engine/runtime policy, not merely a boolean on tab metadata.

The engine manager owns live engine instances. Selecting or detaching a tab must not implicitly dispose its engine. Tab selection, native-surface attachment/visibility, engine-session liveness and renderer health are separate state axes: only the selected tab needs to be presented, and background views do not have to remain attached or composed merely to preserve a live logical session. Adapters may throttle/freeze background work, detach views or evict renderer resources under explicit capability-aware policy, but renderer eviction/crash must be observable and recoverable where supported; it must not be silently treated as tab closure. Preserve restorable tab metadata and report honestly whether recovery restores only URL/history or also richer page state. Closing a tab closes only its owned session. Runtime objects shared across tabs must have a separate owner and lifecycle.

## 5. Subsystem boundaries

### Navigation and policy
Navigation decisions must be fast and deterministic when invoked from synchronous native callbacks. Separate navigation decisions from resource interception; do not claim broad interception where a platform only supports top-level navigation callbacks.

### Downloads
A platform download callback only announces a candidate request; it does not imply user consent or start network I/O. The app/policy layer explicitly accepts or rejects the request. `:browser-download` owns task state and scheduling, while an injected transport owns HTTP behavior, cookies/authentication, destination creation, persistence and cleanup. Platform capabilities and limitations must be explicit.

### JS/native bridge and extensions
Bridge messages are versioned and validated. The receiver derives origin/frame identity from trusted engine metadata rather than trusting fields supplied by page JavaScript. Privileged methods require origin/extension identity checks and explicit permission policy. Do not treat arbitrary script injection as equivalent to a WebExtensions runtime. Extension contracts can begin in browser-core packages; a separate Gradle module is not justified until dependency or release boundaries require it.

### Permissions, popups and interception
Keep website permission requests, operating-system runtime permissions, popups, downloads and request interception as distinct decisions. Optional features should use focused interfaces or explicit capability-specific adapters instead of bloating the base engine interface.

## 6. Platform adapter rules

- Android is the first adapter. Use native System WebView to validate the architecture now; the intended final runtime is self-compiled Chromium and must replace the temporary WebView implementation behind the same contract.
- iOS uses WKWebView; HarmonyOS uses ArkWeb; desktop uses KCEF.
- Each adapter translates native callbacks into normalized state/events and maps core decisions back to native actions.
- Capabilities remain unavailable until the exact implementation is tested.
- A platform adapter owns native surface attachment; the browser session owner owns engine lifetime.
- Do not create fake implementations that silently report success for unsupported features.

## 7. Testing and quality gates

Every architectural change must pass the relevant module tests and build checks. The CI test workflow must include both `:browser-core:jvmTest` and `:browser-download:jvmTest`, plus the existing app adapter tests. Add focused tests for lifecycle, navigation race handling, event normalization, download state transitions, cancellation and unsupported capabilities.

Before marking a platform feature complete, test it against the real native engine (emulator/device where applicable), not only a fake transport or mocked contract.

## 8. Implementation sequence

1. Treat this document and `BROWSER_CORE_ARCHITECTURE.md` as the architectural baseline.
2. Audit and converge the legacy/new engine contracts before adding more engine-specific behavior.
3. Verify BrowserController, engine manager, session ownership and event delivery with focused tests.
4. Integrate the selected Android Chromium runtime first through a platform adapter that obeys the stable contract.
5. Complete download transport as a platform adapter and wire it at the app composition root.
6. Add iOS, HarmonyOS and desktop adapters against the same contract, documenting capability differences.
7. Introduce more Gradle modules only when an observed dependency/build/release boundary justifies them.

## 9. Change review checklist

For every new class or module, answer:

- Which single responsibility and owner does it belong to?
- Why is a package insufficient if a new Gradle module is proposed?
- Does the dependency direction point toward stable contracts?
- Who owns cancellation, native resources and shutdown?
- How is the behavior tested, including failure and unsupported-platform cases?
- Does this make another platform or feature easier to implement without creating needless abstraction?

If these questions cannot be answered clearly, do not add the abstraction yet.
