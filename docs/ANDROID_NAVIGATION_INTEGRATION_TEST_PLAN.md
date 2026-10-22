# Android Navigation Integration Validation

## Status

The shared `BrowserNavigationRequestInterceptor` has JVM/desktop unit coverage. That verifies decision mapping only; it does **not** prove Android WebView invokes the interceptor at the right time or honors `Reject`.

Until device/emulator integration tests pass, Android reports:
- `navigationPolicy = false`
- `requestInterception = MAIN_FRAME_NAVIGATION`

Do not upgrade the capability based on common tests alone.

## Required integration harness

Add an Android instrumentation test source set and run tests on an emulator with a real Android `WebView`. Prefer a local in-process HTTP server or a deterministic test page over public websites. Tests should wait on explicit navigation/page-finish callbacks with bounded timeouts, never sleeps.

The harness must exercise the exact production wiring from `BrowserTabHost` through the pinned `compose-webview-multiplatform:2.0.3` `RequestInterceptor` and its Android `AccompanistWebViewClient`. A test that installs an unrelated custom `WebViewClient` only verifies Android platform behavior and is not sufficient.

## Acceptance cases

1. **Allowed main-frame URL** — interceptor sees the request once and the target document loads.
2. **Rejected main-frame URL** — interceptor returns `Reject`; the target document never commits and the current document remains usable.
3. **External scheme** — the host action is invoked once; WebView does not navigate to the URI.
4. **New-tab decision** — the current WebView rejects the navigation; the host creates exactly one new tab and dispatches the destination once.
5. **Subresources** — image, script, stylesheet, and iframe requests do not invoke the top-level Browser Core policy. Verify iframe treatment matches the explicit `isForMainFrame` policy.
6. **Redirects** — a redirect is tagged as `NavigationSource.REDIRECT`; no duplicate navigation is dispatched by the adapter.
7. **Closed tab / stale callback** — after tab closure, late callbacks must not trigger external launches, create tabs, or issue engine commands.
8. **Repeated clicks** — repeated taps and redirects do not result in duplicate host actions.
9. **Lifecycle** — destroying/recreating the host does not leave an interceptor attached to a disposed engine or route events to an obsolete controller.

## Capability promotion gate

Set Android `navigationPolicy = true` only after the production-wiring integration suite passes on at least one supported emulator API level and the test job is required in CI. Record the API level and WebView provider version in test output. Until then, keep the capability conservative.
