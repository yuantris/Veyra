# Android Navigation Integration Validation

## Status

The shared `BrowserNavigationRequestInterceptor` has JVM/desktop unit coverage. The Android instrumentation suite now exercises the **real production path** — the `createWebView` factory that `AndroidChromiumSurfaceRenderer` uses, with its `WebViewClient.shouldOverrideUrlLoading` interception — and verifies the five gate cases from the capability matrix against a real WebView.

Until device/emulator integration tests pass, Android reports:
- `navigationPolicy = false`
- `requestInterception = MAIN_FRAME_NAVIGATION`

Do not upgrade the capability based on common tests alone.

## Integration harness

`composeApp/src/androidInstrumentedTest/kotlin/dev/veyra/browser/AndroidNavigationPolicyWebViewTest.kt` drives the same `createWebView` factory the shipping renderer uses (not the compose-webview library `WebViewNavigator` path, which the earlier harness used and which this plan deems insufficient). It loads a deterministic inline HTML document and uses bounded latches rather than sleeps; all assertions are network-free because `shouldOverrideUrlLoading` fires synchronously when a top-level navigation is initiated. `.github/workflows/core-tests.yml` runs `:composeApp:connectedDebugAndroidTest` on an API 35 emulator.

## Coverage of acceptance cases

Covered at the WebView boundary by `AndroidNavigationPolicyWebViewTest`:
1. **Allowed main-frame URL** — intercepted once, document begins loading. ✅
2. **Rejected main-frame URL** — `Cancel`, target never loads, current document stays. ✅
3. **External scheme** — custom scheme intercepted and routed to the host policy; WebView does not navigate. ✅ (host action = the recorded policy decision; this adapter's external-handoff routing is a controller concern, not the WebView client)
4. **New-tab decision** — not yet covered at the WebView boundary (new-tab creation/dispatch is a controller-level concern above `shouldOverrideUrlLoading`). ⏳
5. **Subresources** — img/script/iframe do not enter top-level policy. ✅
6. **Redirects** — not yet covered. ⏳
7. **Closed tab / stale callback** — after `webView.destroy()`, a late navigation cannot reach the host policy. ✅ (renderer-boundary form)
8. **Repeated clicks** — not yet covered. ⏳
9. **Lifecycle** — not yet covered (no separate integration test yet). ⏳

`navigationPolicy` flips to `true` only after the full suite passes on the emulator and the API level + WebView provider version are recorded.

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
