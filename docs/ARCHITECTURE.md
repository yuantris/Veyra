# Veyra architecture

## Direction

- Compose Multiplatform owns shared browser UI.
- `browser-core` owns platform-neutral browser/session contracts and policy.
- Platform adapters own native rendering views and their lifecycle.
- Prefer Chromium where it is practical to ship and maintain; use the system engine where platform policy or integration requires it.
- Do not expose Android WebView, WKWebView, ArkWeb, or CEF types to common business code.

## Verified prototype

Android real-page loading and desktop KCEF/Chromium initialization and page loading have been manually verified. Android and desktop Kotlin compilation have passed in earlier GitHub Actions runs; each subsequent change must be checked against its own CI run.

## Browser core

- `BrowserEngine` exposes navigation state through `StateFlow<BrowserEngineState>` and load/back/forward/reload/stop/dispose operations.
- `BrowserSessionStore` owns tab metadata, active-tab selection, bookmarks and history.
- Private tabs do not write visits to the shared history list.
- Platform hosts should own a separate engine instance per tab and dispose it when a tab closes.
- `App.kt` connects `BrowserSessionStore` to the current prototype tab strip. It now mounts only the active WebView rather than stacking several native WebView surfaces with alpha/z-index. This avoids relying on native-view stacking to switch tabs, but means switching tabs can recreate the WebView and reload its URL; scroll position and in-page state are not yet guaranteed to survive.
- Closing/disposal unregisters the matching engine instance to avoid a stale disposal callback removing a newer engine.
- The adapter currently bridges the cross-platform WebView library; it is not yet a direct Android `WebView`, WKWebView, ArkWeb or KCEF API implementation.

## UI status

The current interface is a functional prototype for validating browser operations, not the final visual design. Once tab lifecycle and engine behavior are stable, redesign the shared browser UI as a separate milestone rather than treating the current layout as the product direction.

## Engine direction

| Platform | Current route | Direction |
| --- | --- | --- |
| Android | Android WebView | Evaluate bundled Chromium only if the capability advantage justifies its size and maintenance |
| iOS | WKWebView through the CMP WebView library | Keep WKWebView unless a permitted alternative is feasible |
| HarmonyOS | Not integrated yet | ArkWeb through CPF-KMP-CMP / ArkTS bridge |
| Desktop | KCEF/Chromium | Validate packaging, runtime download and shutdown lifecycle |

## Next milestones

1. Validate multi-tab behavior on Android and desktop, including switching, closing active/inactive tabs, loading-state updates, and whether URL-based restoration is acceptable.
2. Decide on a tab retention strategy based on native-view support; implement native adapters where that gives better lifecycle control.
3. Redesign the browser UI after agreeing on the target visual direction.
4. Add bookmark/history UI and a private-tab entry point; ensure private visits never enter history.
5. Add downloads, permissions, and popup policies through platform-specific capability interfaces.
6. Integrate the CPF HarmonyOS toolchain and validate compatible dependency versions.
7. Add an iOS host project and verify native navigation.
8. Validate desktop Chromium packaging and runtime updates.
