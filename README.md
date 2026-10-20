# Veyra

A cross-platform browser experiment built with Kotlin Multiplatform + Compose Multiplatform.

The first milestone is to prove a shared CMP browser shell and real web content on Android, iOS and desktop. HarmonyOS is planned through CPF-KMP-CMP and ArkWeb.

## Current prototype

- Shared CMP home screen and address bar.
- URL entry, search fallback and quick links.
- Basic back/forward/reload controls.
- compose-webview-multiplatform for Android WebView, iOS WKWebView and desktop KCEF/Chromium.
- Architecture notes for the later HarmonyOS adapter.

## Toolchain

- Kotlin 2.2.21
- Compose Multiplatform 1.9.2
- Android Gradle Plugin 8.11.2
- Gradle 8.14.x
- JDK 17 recommended

These versions are a starting point aligned with the current CPF ecosystem; verify local toolchain and artifact availability before upgrading.

## Run

1. Clone this repository and open its root in Android Studio.
2. Use JDK 17 as the Gradle JDK and sync.
3. Run :composeApp:assembleDebug for Android.
4. Run the desktop target on Windows/macOS/Linux. First launch initializes the KCEF runtime, so allow network access and wait for initialization.
5. For iOS, open an Xcode host project and link the Veyra framework. A complete Xcode host project is not included yet.

The repository does not yet contain the Gradle wrapper JAR or a complete iOS/HarmonyOS host project. If Gradle is installed locally, generate the wrapper using gradle wrapper --gradle-version 8.14.3.

## HarmonyOS

See docs/HARMONYOS.md. The HarmonyOS app host and CPF-specific build pipeline are not yet included in this first commit.

## Architecture

See docs/ARCHITECTURE.md.

## Known gaps

This is an experiment, not a production browser. Tabs/sessions, bookmarks, history, downloads, private profiles, cookie policy, request filtering, extension support, crash recovery, and full Chromium packaging/update strategy are not implemented yet.
