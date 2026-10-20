# Veyra architecture

## Direction

- Compose Multiplatform owns the shared browser chrome and application UI.
- `browser-core` owns platform-neutral browser/session contracts and policy.
- A platform adapter owns the native rendering view and its lifecycle.
- Prefer a Chromium-based runtime where it is practical to ship and maintain; fall back to the system engine where policy or platform integration prevents it.
- Do not expose Android WebView, WKWebView, ArkWeb, or CEF types to common business code.

## Current prototype

The first app target is a proof-of-concept using `compose-webview-multiplatform` to verify shared CMP UI and web content on Android, iOS and desktop. This is not yet a production browser engine abstraction.

## Engine direction

| Platform | Initial verification | Long-term direction |
| --- | --- | --- |
| Android | Android WebView | Evaluate bundled Chromium only if it provides a compelling capability advantage |
| iOS | WKWebView | WKWebView unless a permitted alternative engine is feasible |
| HarmonyOS | ArkWeb through CPF-KMP-CMP / ArkTS bridge | System engine unless third-party Chromium becomes officially viable |
| Desktop | Verify library backend and packaging first | Evaluate CEF/JCEF or another supported Chromium embedding |

## Next milestones

1. Confirm the current CMP + WebView dependency builds on Android, iOS and desktop.
2. Extract URL navigation and loading events behind `BrowserEngine`.
3. Implement tabs as independent sessions; keep native view lifecycle separate from tab metadata.
4. Add bookmarks/history/downloads and private-session policies.
5. Integrate the CPF HarmonyOS toolchain and validate its compatible dependency versions.
6. Run a desktop Chromium packaging spike before committing to CEF/JCEF.
