# Veyra

A cross-platform browser experiment built with Kotlin Multiplatform + Compose Multiplatform.

## Current status

- Android real webpage loading has been manually verified with the previous WebView prototype.
- Desktop KCEF/Chromium initialization and real webpage loading have been manually verified.
- Android and desktop Kotlin compilation passed in earlier GitHub Actions runs; every subsequent change must be checked against its own CI run.
- `browser-core` is a Kotlin Multiplatform module containing the platform-neutral `BrowserEngine` contract and shared `BrowserSessionStore`.
- Browser-core JVM tests cover tab/session metadata behavior.
- The current shared UI still uses `compose-webview-multiplatform` as a prototype renderer. The engine control contract is being separated from the renderer so Android can move to a dedicated Chromium-based adapter.
- Android engine direction has changed from GeckoView to Chromium (Blink + V8). No Android Chromium runtime/adapter has been selected or integrated yet.
- WebExtensions support is a hard engine-selection gate, not an assumed property of every Chromium embedder.

## Platform direction

| Platform | Engine direction | Priority |
| --- | --- | --- |
| Android | Chromium-based embeddable runtime (TBD; WebExtensions support must be verified) | First |
| iOS | WKWebView | Later |
| HarmonyOS | ArkWeb | Later |
| Desktop | KCEF / Chromium | Later |

Shared Compose UI does not imply a shared browser engine. Native rendering, navigation delegates, request interception, downloads, file chooser, website permissions and extension runtime are implemented by platform adapters.

## Toolchain

- Kotlin 2.2.21
- Compose Multiplatform 1.9.2
- Android Gradle Plugin 8.11.2
- Gradle Wrapper 8.14.x
- JDK 17

## Run

1. Clone this repository and open its root in Android Studio.
2. Use JDK 17 as the Gradle JDK and sync.
3. Run `./gradlew :composeApp:assembleDebug` on macOS/Linux or `gradlew.bat :composeApp:assembleDebug` on Windows.
4. Run the desktop target on Windows/macOS/Linux. On first launch, KCEF initializes its Chromium runtime; allow network access and wait for initialization.
5. For iOS, an Xcode host project still needs to be created and linked to the Veyra framework.

## HarmonyOS

See `docs/HARMONYOS.md`. The HarmonyOS app host and CPF-specific build pipeline are not included yet.

## Architecture

See [`docs/BROWSER_CORE_ARCHITECTURE.md`](docs/BROWSER_CORE_ARCHITECTURE.md) for the normative core contracts, lifecycle rules, JS Bridge security, capability interfaces, platform matrix and acceptance tests. [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) gives the architecture overview. See [`docs/CHROMIUM_ANDROID_PLAN.md`](docs/CHROMIUM_ANDROID_PLAN.md) for the Android-first execution plan.

## Known gaps

This is an experiment, not a production browser. The Android Chromium runtime/adapter and verified WebExtensions runtime, independent live tab sessions, complete iOS hosting, HarmonyOS integration, bookmarks/history persistence and UI, downloads, private-profile isolation, request filtering, extension manager, crash recovery and production engine update strategy remain incomplete.
