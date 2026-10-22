# Android Navigation Integration Validation

## Status

The shared `BrowserNavigationRequestInterceptor` has JVM/desktop unit coverage. An Android instrumentation test now exercises the pinned Compose WebView + navigator interceptor path and verifies a rejected main-frame link leaves the current document loaded. The emulator CI run is still the acceptance gate; the broader cases below are not yet all covered.

Until device/emulator integration tests pass, Android reports:
- `navigationPolicy = false`
- `requestInterception = MAIN_FRAME_NAVIGATION`

Do not upgrade the capability based on common tests alone.

## Integration harness

Implemented initial harness: `composeApp/src/androidInstrumentedTest/kotlin/dev/veyra/browser/BrowserNavigationRequestInterceptorAndroidTest.kt`. It uses a deterministic inline HTML document and a rejected same-origin-style link destination, with bounded latches rather than sleeps. `.github/workflows/core-tests.yml` runs `:composeApp:connectedDebugAndroidTest` on an API 35 emulator.

The initial test covers rejection and current-document continuity. Add the remaining acceptance cases below incrementally; do not treat the entire matrix as covered yet.

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
