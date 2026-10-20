# Veyra architecture

## Direction

- Compose Multiplatform owns shared browser UI.
- `browser-core` owns platform-neutral browser/session contracts and policy.
- Platform adapters own native rendering views and their lifecycle.
- Prefer Chromium where it is practical to ship and maintain; use the system engine where platform policy or integration requires it.
- Do not expose Android WebView, WKWebView, ArkWeb, or CEF types to common business code.

## Verified prototype

Android real-page loading and desktop KCEF/Chromium initialization and page loading have been manually verified. Android and desktop Kotlin compilation pass in GitHub Actions.

## Browser core

- `BrowserEngine` exposes navigation state through `StateFlow<BrowserEngineState>` and load/back/forward/reload/stop/dispose operations.
- `BrowserSessionStore` owns tab metadata, active-tab selection, bookmarks and history.
- Private tabs do not write visits to the shared history list.
- Platform hosts should own a separate engine instance per tab and dispose it when a tab closes.
- `App.kt` now connects `BrowserSessionStore` to a tab strip and keeps one `WebViewBrowserEngine`, `WebViewState`, and navigator per tab host. Inactive surfaces are retained behind the active surface so switching tabs does not intentionally dispose their page state.
- The adapter currently bridges the cross-platform WebView library; it is not yet a direct Android `WebView` or KCEF API implementation. Tab retention, loading callbacks, and navigation need runtime testing on Android and desktop.

## Engine direction

| Platform | Current route | Direction |
| --- | --- | --- |
| Android | Android WebView | Evaluate bundled Chromium only if the capability advantage justifies its size and maintenance |
| iOS | WKWebView through the CMP WebView library | Keep WKWebView unless a permitted alternative is feasible |
| HarmonyOS | Not integrated yet | ArkWeb through CPF-KMP-CMP / ArkTS bridge |
| Desktop | KCEF/Chromium | Validate packaging, runtime download and shutdown lifecycle |

## Next milestones

1. Validate multi-tab behavior on Android and desktop, including switching, closing active/inactive tabs, and loading-state updates.
2. Replace the bridge with concrete platform adapters where that gives better lifecycle and capability control; retain the cross-platform wrapper where it is more stable.
3. Add bookmark/history UI and a private-tab entry point; ensure private visits never enter history.
4. Add downloads, permissions, and popup policies through platform-specific capability interfaces.
5. Integrate the CPF HarmonyOS toolchain and validate compatible dependency versions.
6. Add an iOS host project and verify native navigation.
7. Validate desktop Chromium packaging and runtime updates.
