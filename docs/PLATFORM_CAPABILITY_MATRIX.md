# Veyra Platform Capability Matrix

**Purpose:** Separate the long-term browser-engine architecture from capabilities actually wired in the current prototype.
**Rule:** A capability is advertised only when the active adapter can enforce or deliver it. A capability that the native engine could theoretically support is not automatically a Veyra capability.

## Current prototype (compose-webview-multiplatform 2.0.3)

| Capability | Android | iOS | Desktop (KCEF/CEF) | Notes |
| --- | --- | --- | --- | --- |
| Top-level navigation policy callback | Callback wired; public capability remains false pending integration verification | Not wired | Not wired | Android uses the library's synchronous native URL interception callback. |
| Main-frame URL interception scope | Callback wired; scope reported as main-frame only | Unsupported | Unsupported | Scope metadata does not itself advertise policy enforcement as production-ready. |
| Broad resource interception | Unsupported | Unsupported | Unsupported | Separate capability from navigation policy. |
| Private profiles | Unsupported | Unsupported | Unsupported | Current adapter does not isolate cookies/cache/storage per profile. |
| Session restore | Unsupported | Unsupported | Unsupported | Current adapter does not serialize and restore engine session state. |
| Downloads | Unsupported | Unsupported | Unsupported | No normalized download pipeline connected yet. |
| Website permission broker | Unsupported | Unsupported | Unsupported | No normalized permission request flow connected yet. |
| Popup/new-window interception | Unsupported | Unsupported | Unsupported | A core event type alone is not a platform implementation. |
| Native messaging / JS bridge policy | Unsupported | Unsupported | Unsupported | No origin-bound permissioned bridge is connected to Browser Core. |
| WebExtensions | Unsupported | Unsupported | Unsupported | Do not infer extension support from Blink/V8 or Chromium branding alone. |

## Architecture targets (not yet implemented by this prototype)

The normative architecture document describes a Chromium-based Android runtime, WKWebView, ArkWeb, and KCEF/CEF as targets. The exact Android Chromium embedder remains unselected, and WebExtensions support must be verified on the chosen runtime. These targets do not imply that the current Compose WebView prototype already provides their features.

Each adapter must:
1. Publish an immutable capability snapshot that matches its real native integration.
2. Document scope and limitations (for example, top-level navigation vs all resource requests).
3. Supply platform integration tests for capabilities that can block, permit, or redirect native work.
4. Keep unsupported operations explicit; never silently substitute a no-op that looks successful.
5. Avoid cross-platform capability inference from shared interfaces alone.

## Current navigation-policy verification gate

The Android adapter deliberately keeps `navigationPolicy = false` until an Android integration test verifies all of the following against a real WebView:
- an allowed top-level HTTP(S) link loads once;
- a rejected top-level request does not load;
- a custom scheme is rejected in WebView and routed to the host;
- subresource requests do not enter top-level navigation policy;
- a late callback for a closed tab cannot trigger host navigation.

Until those tests exist and pass, the Android callback remains wired for development, but the public capability stays false. iOS and Desktop must remain false until their own native delegate/handler path and tests are added.
