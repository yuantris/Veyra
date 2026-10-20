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
- The shared UI still uses the WebView library directly; concrete engine adapters and full tab-engine integration remain future work.

## Engine direction

| Platform | Current route | Direction |
| --- | --- | --- |
| Android | Android WebView | Evaluate bundled Chromium only if the capability advantage justifies its size and maintenance |
| iOS | WKWebView through the CMP WebView library | Keep WKWebView unless a permitted alternative is feasible |
| HarmonyOS | Not integrated yet | ArkWeb through CPF-KMP-CMP / ArkTS bridge |
| Desktop | KCEF/Chromium | Validate packaging, runtime download and shutdown lifecycle |

## Next milestones

1. Implement concrete Android and desktop `BrowserEngine` adapters.
2. Route navigation and loading events through the shared engine contract.
3. Integrate `BrowserSessionStore` with the CMP tab strip and keep independent engines per tab.
4. Add bookmark/history UI and private-session policies.
5. Integrate the CPF HarmonyOS toolchain and validate compatible dependency versions.
6. Add an iOS host project and verify native navigation.
7. Validate desktop Chromium packaging and runtime updates.
