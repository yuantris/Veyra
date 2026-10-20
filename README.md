# Veyra

A cross-platform browser experiment built with Kotlin Multiplatform + Compose Multiplatform.

## Current status

- Android real webpage loading has been manually verified.
- Desktop KCEF/Chromium initialization and real webpage loading have been manually verified.
- Android and desktop Kotlin compilation passes in GitHub Actions.
- `browser-core` is now a compiled Kotlin Multiplatform module containing the `BrowserEngine` contract and shared `BrowserSessionStore`.
- Browser-core JVM tests pass in GitHub Actions.
- The session store supports tab metadata, active-tab selection, bookmarks, history, and exclusion of private-tab visits from history.

The current CMP UI still uses `compose-webview-multiplatform` directly. Concrete per-platform `BrowserEngine` adapters and full UI integration with independent tab engine instances are the next implementation step.

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

See `docs/ARCHITECTURE.md`.

## Known gaps

This is an experiment, not a production browser. Full engine-adapter integration, independent live tab instances, complete iOS hosting, HarmonyOS integration, bookmarks/history UI, downloads, private profiles and policies, request filtering, extension support, crash recovery, and production Chromium packaging/update strategy remain incomplete.
