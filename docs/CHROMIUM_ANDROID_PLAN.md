# Android Chromium implementation plan

**Status:** Active implementation direction  
**Authority:** This plan is subordinate to [the architecture baseline](ARCHITECTURE.md) and [Browser Core Architecture](BROWSER_CORE_ARCHITECTURE.md).  
**Decision:** Android uses a Chromium-based runtime. GeckoView is not the current direction; obsolete GeckoView planning has been removed.

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

1. Audit `BrowserEngine`, `BrowserEngineContract`, `BrowserController`, `BrowserEngineManager` and the existing Android adapter; converge duplicate contracts before adding engine features.
2. Add focused tests for session ownership, active-tab switching, detach/reattach, idempotent close and stale navigation events.
3. Implement the chosen Chromium runtime behind the single stable engine contract.
4. Validate core navigation and lifecycle on a real Android emulator/device.
5. Add capability-specific features only after their acceptance tests and runtime limits are documented.
6. Keep iOS/WKWebView, HarmonyOS/ArkWeb and desktop/KCEF adapters aligned to the same core contracts without claiming identical capabilities.
