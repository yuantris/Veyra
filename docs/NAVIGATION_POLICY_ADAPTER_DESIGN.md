# Veyra Navigation Policy Adapter Design

**Status:** Accepted design for the next implementation step  
**Scope:** Navigation-policy ownership and platform callback flow between `browser-core` and the current Compose WebView surface  
**Related contract:** `docs/BROWSER_CORE_ARCHITECTURE.md`

## 1. Why this design exists

The current app routes address-bar navigation through `BrowserController.navigate`, but navigation initiated inside a page is observed only after the WebView state changes. That is too late to cancel a blocked navigation or route a custom scheme to an external application. The current adapter must not claim navigation-policy support until the native surface can enforce a decision before the navigation proceeds.

This step is specifically about a correct framework boundary, not about adding resource interception, ad blocking, private profiles, downloads, or a JavaScript bridge.

## 2. Ownership and dependency rules

- `BrowserController` is the application-facing command facade and owns the decision-to-action flow.
- `BrowserNavigationCoordinator` normalizes the policy decision for a top-level navigation request.
- `BrowserEngineContract` owns one tab's normalized page state and event stream. It does not own platform intents or tab creation.
- The platform surface adapter translates native navigation callbacks into core requests and applies the result returned by the host.
- The shared UI must not call a native navigator directly for address-bar navigation.
- Platform code must not duplicate policy rules. It may report native metadata and execute the decision selected by the coordinator.
- Navigation policy and resource interception remain distinct capabilities.

Dependency direction:

```text
Address bar / page callback
          |
          v
BrowserController + BrowserNavigationCoordinator
          |
          +---- Allow ------> engine command / native navigation
          +---- Cancel -----> cancel native navigation
          +---- OpenExternal -> host external-app handler
          +---- OpenInNewTab -> tab/session coordinator
          |
          v
BrowserEngineContract events + state
          |
          v
Shared UI and session store
```

## 3. Request and decision semantics

A top-level navigation request carries:
- `tabId`: stable owner of the request;
- `url`: requested target, never trusted solely because it came from a page;
- `source`: `USER`, `PAGE`, `REDIRECT`, `RESTORE`, or `UNKNOWN`;
- `isMainFrame`: true only when the platform can identify the main frame;
- `hasUserGesture`: nullable if the platform cannot provide trustworthy gesture metadata.

The coordinator returns one of:
- `Allow`: the current WebView may proceed with this exact request;
- `Cancel`: the current WebView must not proceed;
- `OpenExternal(url)`: cancel the in-WebView navigation and ask the host to open the URL externally;
- `OpenInNewTab(url)`: cancel the current navigation and ask the tab coordinator to create/navigate another tab.

A decision callback must be completed once. If the tab/session has closed while an asynchronous policy decision is pending, the result is treated as `Cancel`. A missing or failed policy handler must not implicitly grant custom schemes. Unknown schemes are handed to the external-navigation path or denied by an explicit policy.

## 4. Avoiding recursive navigation and duplicate loads

There are two different entry paths and they must not be conflated:

1. **Command-originated navigation** (address bar, history restore, explicit reload): the controller evaluates policy, then calls the engine to initiate a load after `Allow`.
2. **Surface-originated navigation** (link click, form submission, page script, redirect when observable): the platform callback asks the controller/coordinator for a decision. If the decision is `Allow`, the adapter lets the native engine continue the already-requested navigation. It must not call `engine.navigate` for the same request, because that would start a second load and may recursively trigger the callback.

The implementation must expose a dedicated policy-evaluation path for surface-originated requests rather than reusing the command method in a way that dispatches the navigation again. Both paths share the same coordinator and policy handler.

Back/forward/reload semantics are engine commands and should not be reclassified as arbitrary page navigations unless the native API actually emits a new top-level request requiring policy evaluation. This distinction must be verified with the pinned WebView library.

## 5. Platform callback boundary

The platform-neutral surface port must not expose native WebView types. It should represent navigation interception as a callback/decision contract using core types only.

The adapter must verify the actual API provided by `io.github.kevinnzou:compose-webview-multiplatform:2.0.3` before implementation. In particular, confirm whether the library exposes a pre-navigation callback, whether it can cancel navigation synchronously, whether it identifies main-frame requests, and whether it supports redirects and new-window requests.

If the library does not expose a callback with sufficient control:
- do not emulate interception by observing `lastLoadedUrl` after navigation;
- keep `BrowserCapabilities.navigationPolicy = false`;
- document the missing callback;
- choose a supported platform-specific WebView host/delegate or replace the surface library in a separately reviewed decision.

The adapter may claim `navigationPolicy = true` only after a pre-navigation enforcement path exists and tests prove that Cancel and external handoff prevent the in-WebView load.

## 6. Host actions and event ownership

The core returns decisions; it does not launch operating-system intents or create Compose UI. The app host supplies explicit handlers for:
- opening an external URL;
- opening a URL in a new tab;
- surfacing a blocked/unsupported navigation when useful.

The adapter emits normalized lifecycle events for navigations that actually proceed. A cancelled request should not emit `NavigationStarted` as if a document load had begun. A policy rejection may be reported as a normalized failure with `NAVIGATION_BLOCKED` only if the event model can identify the request without misrepresenting it as a native load failure; otherwise introduce a dedicated policy-decision event before mapping it into page state.

## 7. Concurrency and lifecycle rules

- Bind each pending callback to the engine instance and tab identity that originated it.
- After a suspending policy decision, re-check that the same engine is still registered for the tab.
- Close/cancel pending decisions when a tab closes where the callback API permits cancellation.
- Never let a late callback from a closed/replaced engine mutate a new tab's state.
- Resolve native callback completion exactly once, including exceptions and cancellation.
- Keep `BrowserEngineContract.close()` idempotent and independent from surface detachment.
- Do not make the controller depend on Compose lifecycle; the app-owned coroutine scope must outlive the controller.

## 8. Capability reporting

The current Compose WebView adapter remains conservative:
- navigation policy: unsupported until pre-navigation cancellation is proven;
- request interception: `NONE` unless a separate resource callback is verified;
- private profiles: unsupported;
- session restore: unsupported.

Observing a URL change is state synchronization, not navigation interception. A working address-bar navigation path is also not proof that in-page navigation is governed by policy.

## 9. Implementation sequence

1. Verify the pinned Compose WebView API and write down the exact callback semantics.
2. Add a controller/coordinator API that evaluates surface-originated requests without dispatching a second navigation.
3. Add explicit host routing for `OpenExternal` and `OpenInNewTab`.
4. Connect the surface callback to that API, including one-shot callback completion and stale-tab checks.
5. Normalize navigation lifecycle events and error mapping only where the platform provides enough evidence.
6. Add common tests for allow/cancel/external/new-tab decisions, stale engine replacement, callback cancellation, and exactly-once completion.
7. Add platform integration tests proving that a blocked/custom-scheme navigation does not load inside the WebView.
8. Set capability flags to supported only after those tests pass.

## 10. Acceptance criteria

- Address-bar and page-originated top-level navigations share one policy implementation.
- A page-originated allowed navigation proceeds exactly once.
- A cancelled navigation does not load in the WebView.
- External and new-tab decisions are routed by the host and do not silently disappear.
- A delayed decision cannot navigate a tab that has since closed or been replaced.
- Capability reporting reflects tested enforcement, not merely observed URL changes.
- Browser Core tests and the application build pass on the latest commit.

## 11. Verified dependency constraints (compose-webview-multiplatform 2.0.3)

The pinned library source confirms that `RequestInterceptor.onInterceptUrlRequest` is synchronous and returns one of `WebRequestInterceptResult.Allow`, `Reject`, or `Modify`. `WebRequest` provides `isForMainFrame`, `isRedirect`, and `method`. The library README states that this request interceptor is supported on Android and iOS only; it does not establish equivalent Desktop enforcement.

Consequences:
- A suspend `NavigationPolicyHandler` cannot be called directly from this native callback without blocking the callback thread. Blocking is prohibited.
- The core navigation decision used by the native callback must be synchronous and fast. It should perform deterministic policy checks only.
- Any future decision requiring asynchronous user interaction or remote work must be a separate defer/cancel/reissue flow, with explicit limitations for POST bodies, headers, user gestures and redirect semantics. It must not be smuggled into this synchronous contract.
- The interceptor sees resource requests as well as main-frame requests. The adapter must inspect `isForMainFrame`; subresource requests must not be routed through top-level navigation policy.
- The shared API can be present in common code, but capability reporting must be platform-specific. Android/iOS can advertise navigation interception only after integration tests; Desktop must remain unsupported unless its native KCEF/CEF handler is wired and tested independently.
- Request interception and navigation policy remain separate capability claims even if they share the library callback.

## 12. Updated implementation order

1. Convert the deterministic `NavigationPolicyHandler` and `BrowserNavigationCoordinator.decide` to synchronous APIs.
2. Add a controller entry point for evaluating a surface-originated request without calling `engine.navigate`; verify that the same engine still owns the tab.
3. Add common tests for the synchronous decision path and stale/closed tab behavior.
4. Connect the request interceptor for main-frame requests only on platforms where the pinned library actually invokes it.
5. Add explicit host routing for external and new-tab decisions; do not silently discard either result.
6. Add platform-specific capability reporting and integration tests before advertising navigation-policy support.
