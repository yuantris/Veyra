# Desktop KCEF Navigation Adapter Design

**Status:** Design proposal for the next adapter implementation step
**Scope:** Top-level navigation decisions for the Desktop target
**Related contracts:** `docs/BROWSER_CORE_ARCHITECTURE.md`, `docs/NAVIGATION_POLICY_ADAPTER_DESIGN.md`

## 1. Current state

The current Desktop UI uses `compose-webview-multiplatform`'s `DesktopWebView`, which wraps `dev.datlag.kcef.KCEFBrowser`. The inspected Desktop implementation calls `KCEFBrowser.loadURL` / `loadRequest` for explicit loads, but it does not connect the shared `WebViewNavigator.requestInterceptor` to a CEF navigation callback. Therefore Desktop must continue to report `navigationPolicy = false`.

KCEF's public client wrapper delegates `org.cef.handler.CefRequestHandler` and exposes `addRequestHandler` / `removeRequestHandler`. This establishes that the underlying KCEF layer has a request-handler extension point; it does **not** establish that the current Compose wrapper exposes that hook to the Veyra app or that a particular callback is already wired.

## 2. Required callback behavior

The Desktop adapter must translate CEF's native pre-navigation callback into `NavigationRequest` and synchronously map the coordinator's result:

| Core decision | Native behavior |
| --- | --- |
| `Allow` | Return the native value that allows the current request to continue; do not issue another load. |
| `Cancel` | Cancel the current native request. |
| `OpenExternal(url)` | Cancel the in-browser request first, then hand the URL to the host external-URI handler. |
| `OpenInNewTab(url)` | Cancel the current request first, then ask the session/tab coordinator to open a new tab. |

The adapter must identify main-frame navigation. Subframe and resource requests must not be sent through the top-level navigation policy. If the native callback cannot reliably distinguish a main frame, the adapter must not advertise the capability until a safe fallback is designed and tested.

## 3. Integration choice

Do not patch `compose-webview-multiplatform` behavior from common code or pretend its Desktop interceptor is active. Evaluate these options in order:

1. **Preferred for Veyra's long-term architecture:** implement a Desktop-native `BrowserViewHost` backed directly by KCEF, with Veyra owning the KCEF client and its request-handler lifecycle. Keep `BrowserEngineContract` and `BrowserController` independent of CEF classes.
2. **Short-term alternative:** maintain a small, pinned fork/patch of `compose-webview-multiplatform` that exposes a Desktop-specific request-handler hook. This adds upstream maintenance cost and must be isolated behind the platform adapter.
3. Do not use URL-change observation as a substitute. It cannot cancel a request before it loads.

The first option is preferred because Veyra has already selected KCEF for Desktop and intends to own its native adapter layer. The Compose surface should render/host the native browser, not be the only place where browser policy exists.

## 4. Lifecycle and threading rules

- Register the request handler before the browser starts loading its initial URL.
- Keep the handler bound to a stable `TabId` and the exact engine/session instance that owns it.
- The native callback must be synchronous, deterministic, and non-blocking. Never wait for a coroutine, dialog, network request, or UI response inside the CEF callback.
- Re-check tab/engine ownership before host-side effects. A late callback from a closed or replaced tab must be cancelled.
- Remove the handler and dispose native browser resources through the engine lifecycle, not a transient Compose recomposition.
- Do not call `loadURL` from the `Allow` branch; the current native request is already in progress.

## 5. Verification gate

Before `navigationPolicy` can become true on Desktop, add a Desktop integration test or a reproducible test harness that proves:

1. allowed HTTP(S) main-frame navigation loads exactly once;
2. rejected navigation never commits inside the browser;
3. a custom scheme is cancelled before host external routing;
4. subframe/resource requests bypass top-level policy;
5. navigation from a closed tab cannot trigger host side effects;
6. handler registration/removal follows browser creation/disposal and does not leak across tabs.

Until these checks pass, Desktop reports `navigationPolicy = false` and `requestInterception = NONE`.

## 6. Out of scope

This adapter step does not implement broad network interception, ad blocking, downloads, popup creation, private profiles, or a JavaScript/native bridge. Each needs its own contract and capability tests.
