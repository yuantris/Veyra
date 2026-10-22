# Veyra

A cross-platform browser built with Kotlin Multiplatform and Compose Multiplatform.

## Architecture direction

- Shared Compose UI does not imply a shared rendering engine.
- Android is the first platform and the selected engine direction is Chromium-based; see [the Android Chromium plan](docs/CHROMIUM_ANDROID_PLAN.md).
- iOS uses WKWebView, HarmonyOS uses ArkWeb, and desktop uses KCEF.
- Native engine types and callbacks stay inside platform adapters.
- `:browser-core` owns browser contracts and coordination; `:download-core` owns download task coordination and transport abstractions.
- The initial module count is intentionally small. Logical subsystems remain packages until a real dependency, build, test or release boundary justifies a separate Gradle module.

## Current status

The repository is an evolving prototype, not yet a production-ready browser. Core contracts and coordination are under active development. Do not infer production support for downloads, private-profile isolation, WebExtensions, full request interception or other advanced capabilities from the existence of a shared interface alone. A capability is considered supported only after the concrete platform adapter and its tests demonstrate it.

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
4. Run the desktop target on Windows/macOS/Linux.
5. For iOS, an Xcode host project must be created and linked to the Veyra framework.

## HarmonyOS

See `docs/HARMONYOS.md`. The HarmonyOS app host and CPF-specific build pipeline are not included yet.

## Architecture documents

- [Architecture baseline](docs/ARCHITECTURE.md): module boundaries, dependency rules, platform directions and change-review criteria.
- [Browser Core Architecture](docs/BROWSER_CORE_ARCHITECTURE.md): normative browser contracts, lifecycle, JS Bridge security, capability interfaces and acceptance tests.
- [Android Chromium plan](docs/CHROMIUM_ANDROID_PLAN.md): historical investigation notes; superseded as the engine selection plan by the GeckoView direction in the architecture baseline.

