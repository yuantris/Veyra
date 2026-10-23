# Veyra Platform Capability Matrix

**Purpose:** Separate the long-term browser-engine architecture from capabilities actually wired in the current prototype.
**Rule:** A capability is advertised only when the active adapter can enforce or deliver it. A capability that the native engine could theoretically support is not automatically a Veyra capability.

## Current validation prototype (Android native System WebView; other temporary surfaces use compose-webview-multiplatform 2.0.3)

| Capability | Android | iOS | Desktop (KCEF/CEF) | Notes |
| --- | --- | --- | --- | --- |
| Top-level navigation policy callback | Callback wired; public capability remains false pending integration verification | Not wired | Not wired | Android uses the library's synchronous native URL interception callback. |
| Main-frame URL interception scope | Callback wired; scope reported as main-frame only | Unsupported | Unsupported | Scope metadata does not itself advertise policy enforcement as production-ready. |
| Broad resource interception | Unsupported | Unsupported | Unsupported | Separate capability from navigation policy. |
| Private profiles | Unsupported | Unsupported | Unsupported | Current adapter does not isolate cookies/cache/storage per profile. |
| Session restore | Unsupported | Unsupported | Unsupported | Current adapter does not serialize and restore engine session state. |
| Downloads | Verified in Android Emulator for the current native System WebView validation adapter | Unsupported | Unsupported | Android's native `DownloadListener` → app acceptance policy → core task model → Ktor transport → app-specific files dir passed `DownloadPipelineAndroidTest` in CI. The future self-compiled Chromium adapter must independently verify this path. iOS has no page download API; desktop detection is unverified. |
| Website permission broker | Unsupported | Unsupported | Unsupported | The platform-neutral decision point exists in Browser Core (`WebsitePermissionPolicy` with per-profile site settings, distinct grant/deny/cancel/time-out/unsupported outcomes, and policy tests), but no platform adapter connects an OS permission prompt to it yet. |
| Popup/new-window interception | Unsupported | Unsupported | Unsupported | A core event type alone is not a platform implementation. |
| Native messaging / JS bridge policy | Unsupported | Unsupported | Unsupported | No origin-bound permissioned bridge is connected to Browser Core. |
| WebExtensions | Unsupported | Unsupported | Unsupported | Do not infer extension support from Blink/V8 or Chromium branding alone. |

## Architecture targets (not yet implemented by this prototype)

Android currently uses the device-provided System WebView only as a temporary validation runtime. The long-term target is a self-compiled Chromium kernel, which has not been built or integrated yet. iOS targets WKWebView, HarmonyOS targets ArkWeb, and desktop targets KCEF/CEF. These targets do not imply that the current Android WebView prototype already provides their features; WebExtensions support must be verified on the eventual self-compiled runtime.

Each adapter must:
1. Publish an immutable capability snapshot that matches its real native integration.
2. Document scope and limitations (for example, top-level navigation vs all resource requests).
3. Supply platform integration tests for capabilities that can block, permit, or redirect native work.
4. Keep unsupported operations explicit; never silently substitute a no-op that looks successful.
5. Avoid cross-platform capability inference from shared interfaces alone.

## Current navigation-policy verification gate

The Android adapter now reports `navigationPolicy = true`. `AndroidNavigationPolicyWebViewTest` drives the real production path and passes on a real WebView (API 35 system-WebView emulator, `connectedDebugAndroidTest` in CI):

- `composeApp/src/androidInstrumentedTest/.../AndroidNavigationPolicyWebViewTest.kt` constructs the WebView through the **same `createWebView` factory `AndroidChromiumSurfaceRenderer` uses** and verifies the real `WebViewClient.shouldOverrideUrlLoading` interception (it is NOT the compose-webview library `WebViewNavigator` path, which the test plan deems insufficient). It asserts against a real WebView that:
  - an allowed top-level HTTP(S) link is intercepted exactly once and begins loading;
  - a rejected top-level request does not load and the current document stays;
  - a custom scheme is intercepted and routed to the host policy, never loaded by the WebView;
  - subresource requests (img/script/iframe) do not enter top-level navigation policy;
  - a late callback after the tab's WebView is destroyed cannot reach the host policy.
- It is gated in CI: the `android-emulator-integration` job runs `:composeApp:connectedDebugAndroidTest` on an API 35 emulator, which includes this test.

**Status:** CLEARED. `AndroidNavigationPolicyWebViewTest` passes on the API 35 system-WebView emulator via `:composeApp:connectedDebugAndroidTest` (CI `android-emulator-integration` job). `navigationPolicy` is now `true` (flipped in `ComposeWebViewPlatformCapabilities.android.kt`). Verification environment: API 35 (`system-images;android-35;google_apis;x86_64`), Android System WebView shipped with the Google APIs image. iOS and Desktop remain `false` until their own native delegate/handler path and tests are added.

## Current downloads verification result

The Android download pipeline passed `DownloadPipelineAndroidTest` in the GitHub Actions Android Emulator job (run [39820740225](https://github.com/yuantris/Veyra/actions/runs/39820740225)). The test exercises the current compose-webview prototype's production download callback, acceptance policy, transfer completion and destination, cancellation/partial-file cleanup, controller shutdown, and private-profile attribution.

Accordingly, Android's `downloads` capability is now `true` for this verified prototype adapter. iOS and Desktop remain unsupported. This result applies only to the current System WebView validation adapter. The future self-compiled Chromium adapter must run its own integration tests before inheriting this capability.
