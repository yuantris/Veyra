# Veyra architecture

## Product and platform direction

Veyra is a cross-platform browser with a shared Compose Multiplatform UI and native browser-engine adapters. The product architecture is shared; rendering engines and platform callbacks are not.

| Platform | Engine direction | Priority |
| --- | --- | --- |
| Android | GeckoView | First implementation and extension-capability reference |
| iOS | WKWebView | Native adapter; extension compatibility is not assumed |
| HarmonyOS | ArkWeb | Native adapter through the HarmonyOS host/bridge |
| Desktop | KCEF / Chromium | Later adapter after the core contract is validated |

Do not expose `GeckoSession`, `GeckoView`, `WKWebView`, ArkWeb, or CEF types to `commonMain` or browser business logic.

## Existing code and current gaps

- `browser-core` is a Kotlin Multiplatform module with the platform-neutral `BrowserEngine` contract and `BrowserSessionStore`.
- `BrowserSessionStore` owns tab metadata, active-tab selection, bookmarks and in-memory history. Private-tab visits are excluded from shared history.
- The current `BrowserEngine` contract covers navigation basics, but not yet capability discovery, permission requests, downloads, popups, request policy or extensions.
- `composeApp/src/commonMain/.../WebViewBrowserEngine.kt` currently bridges the cross-platform WebView library. It is a prototype adapter, not a native GeckoView implementation.
- The current UI mounts only the active WebView. Switching tabs can therefore recreate the renderer and reload its URL; live page state is not guaranteed to survive.
- Android and desktop page loading were previously manually verified. Each future change must be validated by its own CI run and Android device/emulator test.

## Dependency direction

```text
Compose UI (commonMain)
        |
        v
Browser application/use cases
        |
        +---- BrowserSessionStore (tab metadata and browser records)
        |
        +---- BrowserEngine contract + capability/policy contracts
                    ^
                    |
        platform adapter factory (platform source sets)
          |          |           |           |
       GeckoView  WKWebView     ArkWeb       KCEF
        Android      iOS       HarmonyOS    Desktop
```

Shared code may depend on `browser-core`. Platform adapters may depend on `browser-core` and their own native SDKs. `browser-core` must never depend on `composeApp` or any platform engine.

## Core ownership and lifecycle

### Browser runtime

- Android owns one application/process-scoped `GeckoRuntime` for the normal profile, initialized by an explicit engine/runtime owner rather than by a composable.
- Private browsing must use a deliberately configured private session/profile strategy. Do not assume a normal `GeckoSession` becomes private just because its tab metadata says `isPrivate = true`.
- Each live tab maps to a platform session/engine instance. For GeckoView, this means a `GeckoSession` attached to a `GeckoView` only while that tab is displayed.
- Selecting another tab changes the displayed native view/session; it must not dispose the previous tab's session.
- Closing a tab disposes its session and removes its engine registry entry. Closing a tab must not shut down the shared runtime.
- Activity recreation, process death, renderer crash and app backgrounding are separate lifecycle cases. Persist restorable tab metadata, but do not claim that renderer memory/JavaScript state survives process death.

### Engine contract

Keep `BrowserEngine` platform-neutral. It should represent user intent and observable browser state, not native objects. Its stable responsibilities are:

- navigate to a URL, back, forward, reload and stop;
- expose URL, title, loading/progress, back/forward availability and normalized failures;
- expose lifecycle/disposal semantics;
- report a platform capability snapshot so UI/use cases can hide or explain unsupported features.

Do not force all engines to implement every native feature through fake universal methods. Model optional capabilities explicitly and put specialized behavior behind capability-specific interfaces.

### Native interaction and policies

Native adapters own navigation delegates, web-content UI callbacks, file chooser callbacks, permission callbacks, downloads, popups, request interception and renderer/runtime errors. They translate native callbacks into typed core events and translate core decisions back into native actions.

Distinguish:
- navigation policy (allow, cancel, external app, new tab);
- website permission (camera, microphone, location, notifications);
- operating-system runtime permission;
- download decisions and progress;
- popup/new-window requests;
- resource interception and request blocking.

A single boolean such as `interceptEveryRequest()` is not a sufficient cross-platform abstraction. Request interception differs by engine and platform; capability declarations must be explicit.

## Extension architecture (required direction)

Android GeckoView is the first extension-capable implementation. Use Mozilla WebExtensions support rather than inventing a JavaScript injection system as a substitute for extensions.

Logical boundaries (these do not need to be separate Gradle modules initially):

- `extension-api`: normalized extension metadata, identifiers, versions, permission requests and capability requirements.
- `extension-runtime`: install, uninstall, enable, disable, enumerate and update use cases.
- `extension-permissions`: user-facing permission decisions and least-privilege policy.
- `extension-storage`: Veyra-owned settings and extension preferences; native engines may also maintain their own extension storage.
- platform extension adapters: map Veyra operations to native extension APIs and report unsupported features.
- `extension-ui`: management, detail, permission and settings screens, implemented in shared Compose where practical.

GeckoView integration details:

- Use `GeckoRuntime.getWebExtensionController()` to manage extensions.
- GeckoView supports signed external XPI installation and built-in extensions packaged in the APK. External installation must handle Mozilla signature requirements and permission prompts.
- Built-in extensions can use native messaging; use a versioned, typed message protocol between the extension and Android host.
- Treat a site's page content as untrusted. Do not expose unrestricted native messaging or privileged bridge operations to arbitrary websites.
- Persist extension metadata and reconcile it with the runtime's installed-extension list; the runtime is authoritative for engine-level installation state.
- Do not assume every Firefox Android extension or every WebExtensions API is supported identically by GeckoView.
- The extension manager must display unsupported platform/API capabilities instead of silently claiming compatibility.

Other platforms may implement only a subset of the Veyra extension API. A shared API means a shared product contract, not a promise that every extension binary runs on every engine.

## Security baseline

- Never expose arbitrary native methods directly to web pages.
- Bind privileged extension/native messages to known extension IDs and validate message shape, origin/context, permissions and request IDs.
- Request only the permissions needed by a feature; show permission prompts for install and permission changes.
- Keep private browsing data, extension storage and browsing history policy explicitly separated. Private visits must not enter normal history.
- Validate schemes and URLs before external-app launches or privileged operations.
- Treat remote extension packages, manifests, page messages and intercepted response data as untrusted input.

## Delivery plan

1. **Architecture contract (this stage):** document platform ownership, engine/session lifecycle, capability reporting, extension boundaries and security invariants.
2. **Android GeckoView spike:** add a pinned stable GeckoView version and Mozilla Maven repository; verify app startup, one live session, navigation delegates, runtime reuse and Android packaging before refactoring all UI.
3. **Android engine adapter:** implement `BrowserEngine` and a native host that retains one `GeckoSession` per live tab. Wire tab select/close and test page state retention.
4. **Native browser callbacks:** normalize navigation, load progress/errors, popup, file chooser, website permissions and downloads. Mark unsupported behavior explicitly.
5. **Extension foundation:** first install/list/enable/disable/uninstall a bundled test extension, then add signed XPI import/source handling, permission prompts and native messaging.
6. **Shared UI integration:** route UI actions through core use cases; remove direct WebView-library usage from shared browser business logic.
7. **Other platform adapters:** WKWebView, ArkWeb and KCEF; each advertises its own capability set.
8. **Production hardening:** process-death recovery, private profile isolation, crash recovery, storage policy, security tests, engine update strategy and distribution-size review.

## Validation checklist

- Switching tabs preserves a live page session; closing a tab disposes only that session.
- Browser runtime is not created/destroyed by recomposition.
- Back/forward/reload/stop and loading state work against real pages.
- Unsupported native features are surfaced as capabilities, not fake successes.
- Private visits do not enter regular history.
- A bundled WebExtension can be installed idempotently and communicate through the native-messaging contract.
- External extension installation verifies signatures and presents permission prompts.
- Untrusted web content cannot invoke privileged extension/native operations.
