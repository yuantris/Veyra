# Android Chromium implementation plan

## Decision record

- **Android engine direction:** Chromium-based runtime using Blink + V8.
- **Extension requirement:** a real WebExtensions implementation is mandatory; Blink + V8 alone does not satisfy this requirement.
- **Shared contract:** `:browser-core` (`BrowserEngineContract`, `BrowserPageState`, `BrowserEvent`, `BrowserController`).
- **Shared UI:** Compose Multiplatform, with renderer binding isolated from engine control.
- **Current status:** direction selected; exact Android Chromium embedder/runtime is not yet selected or integrated.

## Critical distinction: Chromium is not one embeddable API

Chromium is a browser project and rendering stack. Different embedders expose different APIs and capabilities. Android System WebView is a content embedder and does not expose the full Chrome browser layer. Do not infer WebExtensions support from the presence of Chromium, Blink or V8.

Official Chromium documentation describes Android WebView as an embedder based on `//content` and lower layers rather than the sibling `//chrome` layer:
- [Chromium Android WebView architecture](https://github.com/chromium/chromium/blob/main/android_webview/docs/architecture.md)
- [Chromium supported products and platforms](https://chromium.googlesource.com/experimental/chromium/src/+/HEAD/docs/supported_platforms.md)

## Runtime selection gate

Before integrating a candidate, create a small isolated Android spike and verify each item on a real device/emulator:

1. Embedding API and native view lifecycle are suitable for an Android browser.
2. The exact runtime includes a working WebExtensions host, not merely JavaScript injection.
3. Extension install, enable/disable, uninstall and installed-extension enumeration work.
4. Manifest permissions and user consent are enforced.
5. Content scripts execute in the expected isolated world and match configured origins.
6. Background execution/service-worker behavior is documented and tested.
7. Extension storage, popup/action UI and native messaging are available or have explicit limitations.
8. Native messaging is bound to trusted extension IDs and validates a versioned message schema.
9. ABI support, minSdk, Android OS support, security updates, licensing, APK size and update distribution are acceptable.
10. Navigation, request interception, downloads, popups, file chooser, website permissions, renderer crashes and private-profile isolation are measured separately.

If a candidate cannot pass the WebExtensions gate, do not label it WebExtensions-capable. Re-evaluate the runtime rather than silently replacing extensions with arbitrary page scripts.

## Proposed source-set boundaries

Keep native engine classes out of `commonMain`. Exact package names can change as the selected runtime is validated.

```text
composeApp/src/androidMain/kotlin/dev/veyra/browser/
  engine/
    ChromiumRuntimeOwner.kt
    ChromiumBrowserEngine.kt
    ChromiumBrowserHost.kt
    ChromiumEngineFactory.kt
    ChromiumNavigationAdapter.kt
  extensions/
    ChromiumExtensionRuntime.kt
    ChromiumExtensionPermissions.kt
    ChromiumNativeMessaging.kt
```

These are proposed boundaries, not files that already exist.

- `ChromiumRuntimeOwner`: owns the process/runtime lifecycle and profile strategy.
- `ChromiumBrowserEngine`: adapts one tab's commands, state and events to `BrowserEngineContract`.
- `ChromiumBrowserHost`: attaches/detaches the native rendering surface without owning tab metadata.
- `ChromiumEngineFactory`: creates engines and reports verified capabilities.
- `ChromiumExtensionRuntime`: wraps only extension APIs actually exposed by the selected runtime.

## Lifecycle and dependency rules

- `BrowserController` owns logical engine instances and normalized events.
- `BrowserSessionStore` owns tab metadata and selection.
- The renderer host owns view attachment only; view detachment must not implicitly close a tab.
- Closing a tab closes its engine/session and releases tab-scoped native resources.
- Runtime/profile owners outlive individual tabs when required by the selected runtime.
- Platform-specific types remain in Android source sets.
- The Compose UI consumes `BrowserEngineContract` and a renderer binding contract, never casts to a concrete engine class.
- Capabilities remain false until native integration and tests prove the behavior.

## First implementation sequence

1. Complete shared renderer/engine decoupling in `commonMain`.
2. Select and document one concrete Chromium runtime using the gates above.
3. Prove runtime startup and a single live tab before changing the full app shell.
4. Implement the Android `BrowserEngineContract` adapter and per-tab lifecycle.
5. Verify page state, navigation policy, back/forward/reload/stop and resource release.
6. Run the extension spike: bundled test extension, content script, permission prompt, storage and native messaging.
7. Add Android integration tests for tab switching, tab closure, navigation callbacks and extension behavior.
8. Only then migrate more shared UI and add advanced features.

## Exit criteria

- A named, pinned runtime and its license/update strategy are recorded.
- WebExtensions support is demonstrated with an actual extension, not inferred from engine branding.
- Core contracts and tests pass without Android SDK types in `browser-core`.
- Tab switching preserves the engine session; closing a tab releases only its own resources.
- Unsupported capabilities are reported honestly and are covered by tests.
