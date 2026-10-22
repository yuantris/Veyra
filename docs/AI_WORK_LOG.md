# Veyra AI collaboration work log

**Purpose.** Multiple AI sessions and human contributors take turns in this repository. This log is how they stay aligned on what was planned, what was done, and what comes next. The rule that requires it lives in `AGENTS.md` §11.

**Rules**

1. Append one entry per session before finishing. The log is append-only: never rewrite or delete earlier entries.
2. Every entry states: goal, planned, completed (with test evidence), next smallest safe step, files touched.
3. Refresh the status snapshot below with each entry. It must reflect verified reality, not intentions.
4. Never record proposed work as completed, and never claim tests passed unless their result was observed.
5. If a session discovers a conflict between documents, fix the documents together and say so in the entry.

## Status snapshot

- **Phase:** core-first. The C0–C9 acceptance gates in `docs/BROWSER_CORE_ARCHITECTURE.md` are not all evidenced yet; native engine integration has not started.
- **Modules:** `:composeApp`, `:browser-core`, `:browser-download`. Logical module map and package layout: `AGENTS.md` §4a.
- **Recently completed (2026-10-22):** renamed `:download-core` to `:browser-download`; reorganized `:browser-core` into root contracts plus `engine` / `session` / `navigation` / `security` packages; fixed the iOS `@JvmInline` resolution gap and added a CI `ios-compile` job; hardened C1/C3 with six deterministic race and failure tests; added the download composition seam, five C7 race/cancellation tests, the explicit acceptance layer, and the Ktor-based Android download transport with `App.kt` wiring (capability held at `downloads = false` pending device verification).
- **Known open issues:**
  - `:browser-download` is built and tested but not yet depended on by `:composeApp`.
  - `composeApp` commonMain still mixes UI (`App.kt`) with common adapter code (`ComposeWebViewEngineAdapter`, `BrowserNavigationRequestInterceptor`, `ComposeWebViewSurfaceRenderer`, `ComposeWebViewPlatformCapabilities`) in one flat package.
- **Next smallest safe steps:**
  1. Add the Android instrumented download test from the `PLATFORM_CAPABILITY_MATRIX.md` verification gate (the emulator CI job already exists), then flip Android `downloads = true`.
  2. Continue C-gate evidence: coordinator late-event/stale-callback cases and C4/C5 negative paths not yet covered by tests.
  3. Decide whether `composeApp` commonMain should split UI from common adapter code (same bar as the core split: only with a real boundary).

## Session log

### 2026-10-22 — Module map, package split, module rename, collaboration log

- **Goal:** Land the proposed module division as documented structure: record the logical module map in `AGENTS.md`; clarify `:browser-core` package structure, interfaces and dependency direction; evaluate whether capabilities really need to be split out of core into sub-packages (no splitting for its own sake); rename the misnamed `:download-core`; create this collaboration log.
- **Planned:** (1) AGENTS.md module map + checkable native-type rule; (2) package reorganization of `:browser-core`; (3) module rename across build, CI and docs; (4) multi-AI log document.
- **Completed:**
  - `AGENTS.md`: new §4a logical module map with current physical homes; package dependency direction; native-type rule as a checkable type list; history/bookmarks ownership gap recorded; §11 rule requiring this log; CI task names updated to `:browser-download:jvmTest`.
  - `:browser-core` package reorganization (moves only, no behavior change): split the 419-line `BrowserContracts.kt` god-file into per-domain root contract files (`BrowserIdentity`, `BrowserModels`, `BrowserEvent`, `BrowserEngineContract`, `NavigationContracts`, `PermissionContracts`, `DownloadContracts`, `BridgeContracts`, `ExtensionContracts`); moved coordination into sub-packages — `engine` (`BrowserEngineManager`, `BrowserController`), `session` (`BrowserSessionCoordinator`, `BrowserSessionStore`), `navigation` (`BrowserNavigationCoordinator`), `security` (`BridgeAuthorization`).
  - Split evaluation recorded: justified, because the 419-line file mixed eight contract domains, the normative docs already described logical modules the flat package did not reflect, and sub-packages give future module extraction a seam. Gradle module split still not justified; existing "packages before modules" policy kept.
  - Renamed `:download-core` → `:browser-download` (settings.gradle.kts, iOS framework baseName, CI workflow, README/AGENTS/ARCHITECTURE references). Download contracts stay in the `browser-core` root package; `DefaultDownloadController` needed no import changes.
  - Documentation conflict fixes: README claimed the Android Chromium plan was "superseded by the GeckoView direction" (contradicts the fixed Chromium direction in AGENTS.md §2 and CHROMIUM_ANDROID_PLAN.md) — corrected; duplicate section numbering in BROWSER_CORE_ARCHITECTURE.md fixed (11/12/13); added cross-pointers between AGENTS.md §4a, ARCHITECTURE.md §2 and BROWSER_CORE_ARCHITECTURE.md §2.
  - Discovered (pre-existing, not fixed): `:browser-core` iOS targets do not compile because `@JvmInline` cannot resolve from common code on native. Restored the original declarations unchanged and recorded the decision options above.
- **Test evidence:** `:browser-core:jvmTest`, `:browser-download:jvmTest`, `:composeApp:desktopTest` — BUILD SUCCESSFUL; `:composeApp:compileDebugKotlinAndroid` — BUILD SUCCESSFUL (run locally with `ANDROID_HOME=~/Library/Android/sdk`; CI runs it). No tests were added, removed or modified. `:browser-core:compileKotlinIosX64` fails for the pre-existing reason above; CI does not cover iOS.
- **Next smallest safe step:** wire `:browser-download` into the `composeApp` composition root behind the existing `DownloadController` contract, then add a close-vs-start race test.
- **Files:** `AGENTS.md`, `README.md`, `docs/ARCHITECTURE.md`, `docs/BROWSER_CORE_ARCHITECTURE.md`, `docs/AI_WORK_LOG.md` (new), `settings.gradle.kts`, `.github/workflows/core-tests.yml`, `browser-download/**` (renamed from `download-core/**`), `browser-core/**` (package moves + contract file split), `composeApp/**` (three import updates).

### 2026-10-22 — Fix `@JvmInline` resolution for iOS targets

- **Goal:** Resolve the known issue recorded above: `:browser-core` (and the modules depending on it) did not compile for their declared iOS targets because `@JvmInline` was unresolvable from common code.
- **Root cause (verified, not guessed):** `kotlin.jvm.JvmInline` is usable in common code — the stdlib declares it as an expect annotation with no-op actuals on non-JVM platforms — but it is **not** part of common default imports. The JVM/Android target has `kotlin.jvm.*` in its default imports, so the missing import compiled green on JVM and only failed on iOS/native. The original code also predates the CI ever compiling an Apple target, so the breakage stayed latent.
- **Completed:**
  - Added the single explicit `import kotlin.jvm.JvmInline` to `BrowserIdentity.kt` (the only file using the annotation). Value-class semantics are preserved on every platform; no data-class degradation, no expect/actual shim, no API change.
  - Added a `macos-latest` `ios-compile` job to `.github/workflows/core-tests.yml` covering `iosArm64` / `iosSimulatorArm64` / `iosX64` for `:browser-core`, `:browser-download` and `:composeApp`, so this can never go latent again.
  - Recorded the gotcha in `AGENTS.md` §7 (explicit import required; the `ios-compile` CI job guards it; do not remove iOS targets from CI to avoid the issue).
- **Test evidence:** all 9 iOS compile tasks across the three modules BUILD SUCCESSFUL (run locally on macOS); `:browser-core:jvmTest`, `:browser-download:jvmTest`, `:composeApp:desktopTest`, `:composeApp:compileDebugKotlinAndroid` BUILD SUCCESSFUL. No production code beyond the one import; no tests changed.
- **Next smallest safe step:** wire `:browser-download` into the `composeApp` composition root behind the existing `DownloadController` contract, then add a close-vs-start race test.
- **Files:** `browser-core/src/commonMain/kotlin/dev/veyra/browser/core/BrowserIdentity.kt` (one import + comment), `.github/workflows/core-tests.yml` (new `ios-compile` job), `AGENTS.md` (§7 bullet), `docs/AI_WORK_LOG.md` (this entry; iOS issue removed from known open issues).

### 2026-10-22 — Core phase: C1/C3 lifecycle evidence, gitignore fix

- **Goal:** Commit the structure work from the previous sessions, then continue the core-first phase with the documented next step: deterministic evidence for the C1/C3 races and failure semantics that `BROWSER_CORE_ARCHITECTURE.md` requires but tests did not yet cover.
- **Completed:**
  - Committed the previous sessions' work as three reviewable commits (package reorganization + iOS fix; module rename + ios-compile CI; module map + collaboration log + stale doc fixes), each green on its own.
  - `BrowserEngineManagerConcurrencyTest`: concurrent duplicate open of one tab shares a single creation and returns the same engine instance; concurrent close of one tab disposes exactly once with defined outcomes for both callers (owner `true`, waiter `false`).
  - `BrowserEngineManagerLifecycleTest`: a failing `engine.close()` surfaces unchanged from `closeTab` and leaves the registry terminal (repeat close reports `false`); `closeAll` aggregates disposal failures without skipping other engines and reports the failure count in the thrown exception.
  - `BrowserSessionCoordinatorTest`: an engine close failure reaches the caller as `IllegalStateException` after metadata removal while other tabs keep their engines; `CancellationException` during close is rethrown unchanged instead of being wrapped as an ordinary failure.
  - `.gitignore`: removed the `/.github/` rule. CI workflows are versioned project configuration (both workflow files are tracked), and the rule forced `git add -f` for them; the user confirmed it should go.
- **Test evidence:** `:browser-core:jvmTest` 54 tests pass (48 before, 6 added); `:browser-download:jvmTest`, `:composeApp:desktopTest` pass; `:composeApp:compileDebugKotlinAndroid` and the three `compileKotlinIosArm64` tasks pass. No production code changed in this session; only tests and `.gitignore`.
- **Next smallest safe step:** wire `:browser-download` into the `composeApp` composition root behind the existing `DownloadController` contract, then add a close-vs-start race test.
- **Files:** `.gitignore`, `browser-core/src/commonTest/.../engine/BrowserEngineManagerConcurrencyTest.kt`, `browser-core/src/commonTest/.../engine/BrowserEngineManagerLifecycleTest.kt`, `browser-core/src/commonTest/.../session/BrowserSessionCoordinatorTest.kt`, `docs/AI_WORK_LOG.md`.

### 2026-10-22 — Download wiring: composition seam, C7 evidence, acceptance layer

- **Goal:** Continue the documented next step — make `:browser-download` usable from the app behind the existing `DownloadController` contract, and prove the C7 race/cancellation semantics with tests.
- **Completed:**
  - `BrowserSessionCoordinator.create(...)` gained an optional `downloadController` and threads it into `BrowserController`; the coordinator now exposes `downloadTasks` plus fail-closed `startDownload`/`cancelDownload`, so `App.kt` can use downloads without touching the controller directly.
  - `DefaultDownloadControllerTest` gained five C7 cases: duplicate request ID reuse (no second transport run, mismatched payload rejected), cancel of a running task, cancel of a queued task (transport never invoked), close-vs-start race (close waits for the in-flight transfer, task ends cancelled, later starts rejected), and transport cancellation mapping to a cancelled task.
  - composeApp gained the explicit acceptance layer required by C7: `DownloadPolicy` (HTTP/HTTPS only; a candidate is never auto-started) and `DownloadRequestHandler` (routes accepted candidates into the core API and reports Started/Rejected/Failed; cancellation is rethrown, never converted to a failure).
  - Test-infrastructure notes worth remembering: JUnit4 requires test methods to return void (a trailing `assertIs`/`assertFailsWith` breaks the class); a controller on an `Unconfined` scope cancels in place, so "close waits for in-flight work" is only deterministically observable when the controller shares the test's event-loop scope.
- **Test evidence:** `:browser-core:jvmTest` 56 tests pass; `:browser-download:jvmTest` 8 pass; `:composeApp:desktopTest` 17 pass; `:composeApp:compileDebugKotlinAndroid` and the three `compileKotlinIosArm64` tasks pass.
- **Not done (awaiting decision):** the platform `DownloadTransport` implementation and the `App.kt` event-collection wiring. Only the Android adapter currently detects downloads (`DownloadListener` → `DownloadRequested`, `downloads = true`); desktop and iOS report no download capability. Options recorded in the task list: Android system `DownloadManager`, Ktor client, hand-rolled per-platform (HttpURLConnection + NSURLSession), or defer.
- **Next smallest safe step:** decide the transport strategy, then wire `App.kt` to collect `BrowserEvent.DownloadRequested` through `DownloadRequestHandler` into `coordinator.startDownload`, and close the controller at the composition root.
- **Files:** `browser-core/src/commonMain/.../session/BrowserSessionCoordinator.kt`, `browser-core/src/commonTest/.../session/BrowserSessionCoordinatorTest.kt`, `browser-download/src/commonTest/.../DefaultDownloadControllerTest.kt`, `composeApp/src/commonMain/.../DownloadPolicy.kt` (new), `composeApp/src/commonMain/.../DownloadRequestHandler.kt` (new), `composeApp/src/commonTest/.../DownloadRequestHandlerTest.kt` (new), `docs/AI_WORK_LOG.md`.

### 2026-10-22 — Download transport (Ktor, Android-first) and capability honesty

- **Goal:** Decide and implement the platform `DownloadTransport` so the download pipeline is executable end-to-end, then finish the `App.kt` wiring.
- **Decision (user asked for a recommendation between system DownloadManager and Ktor):** **Ktor**. Reasons: system DownloadManager does not share WebView cookies, so any authenticated download fails; the product layering puts download UI in the shared layer, which requires owning the transfer; one KMP transport serves every platform once detection exists; and it is unit-testable with MockEngine, which DownloadManager is not. Cost accepted: `ktor-client-core` + the OkHttp engine, pinned to 3.3.3 (last line built against this project's Kotlin 2.2 toolchain).
- **Completed:**
  - `KtorDownloadTransport` (commonMain): streams the response with `readAvailable` into a `DownloadFileSink`, reports progress, maps non-200 to failed tasks, aborts partial files on failure/cancellation, and rethrows cancellation unchanged. Verified API facts instead of guessing: ktor 3.3.3 `ByteReadChannel` exposes member `isClosedForRead()` and suspend extensions `readAvailable`/`readRemaining` (returns kotlinx-io `Source`); kotlinx-io 0.8.0 `Source` has `exhausted()`/`readAtMostTo` but no `readByteArray()`.
  - Android: OkHttp engine + `AndroidDownloadFileSinkFactory` writing to the app-specific external Downloads dir with name sanitizing and collision suffixes; `AndroidAppContext` holder initialized from `MainActivity`; expect/actual `createPlatformDownloadTransport()` returns null on desktop and iOS (no download detection there — matches their `downloads = false`).
  - `App.kt`: constructs the download controller at the composition root, collects `BrowserEvent.DownloadRequested` through `DownloadRequestHandler`, shows a compact active-download row with cancel, surfaces rejected/failed outcomes as a status line, and closes the controller before the scope on dispose.
  - Capability honesty (deliberate, per C8 and the navigationPolicy precedent in the same matrix): the pipeline is wired and unit-tested, but no emulator/device test has proven it, so Android `downloads` was flipped **true → false** with the reason in code, and `PLATFORM_CAPABILITY_MATRIX.md` now carries a downloads verification gate listing exactly what the integration test must prove before the flag flips.
- **Test evidence:** `:composeApp:desktopTest` 21 tests pass (4 new Ktor transport tests: streaming + progress, non-200 → failed, write failure aborts partial file, cancellation aborts partial file); `:browser-core:jvmTest` 56, `:browser-download:jvmTest` 8 pass; `:composeApp:compileDebugKotlinAndroid` and `:composeApp:compileKotlinIosArm64` pass. Not yet verified: the end-to-end path on a real device/emulator — that is the recorded gate.
- **Next smallest safe step:** add the Android instrumented download test from the verification gate (the repo already has an emulator CI job), then flip `downloads = true`.
- **Files:** `gradle/libs.versions.toml`, `composeApp/build.gradle.kts`, `composeApp/src/commonMain/.../PlatformDownloadTransport.kt` (new), `composeApp/src/commonMain/.../App.kt`, `composeApp/src/androidMain/.../AndroidDownloadTransport.kt` (new), `composeApp/src/androidMain/.../MainActivity.kt`, `composeApp/src/androidMain/.../ComposeWebViewPlatformCapabilities.android.kt`, `composeApp/src/desktopMain/.../PlatformDownloadTransport.kt` (new), `composeApp/src/iosMain/.../PlatformDownloadTransport.kt` (new), `composeApp/src/commonTest/.../KtorDownloadTransportTest.kt` (new), `docs/PLATFORM_CAPABILITY_MATRIX.md`, `docs/AI_WORK_LOG.md`.

### 2026-10-23 — Android device test for the downloads gate (written; CI verification pending)

- **Goal:** Implement the downloads verification gate from `PLATFORM_CAPABILITY_MATRIX.md` as an Android integration test against production code, then close the gate.
- **Completed:**
  - Extracted the production `DownloadListener` wiring into `createDownloadListener(...)` and made `createWebView` internal so the instrumented test can exercise the real glue instead of a copy.
  - New `DownloadPipelineAndroidTest` (androidInstrumentedTest, MockWebServer as the transfer endpoint, no external network): (1) a real WebView fires the production listener for a download-attribute link and the request carries URL/tab/profile/suggested file name; (2) an accepted candidate completes, keeps private profile attribution and lands in the app-specific Downloads directory; (3) cancelling a throttled transfer ends it cancelled with no partial file; (4) closing the controller mid-transfer ends cancelled without hanging; (5) the Android file sink sanitizes traversal/illegal names, suffixes collisions and leaves nothing behind on abort.
  - Verified locally as far as possible without a device: the test compiles (`:composeApp:compileDebugAndroidTestKotlinAndroid`) and the whole unit suite is green. The emulator on this machine boots fine (arm64 API 35 AVD), but per the user's instruction the full device run was skipped after a first attempt exposed a compile error in the test itself; CI's `android-navigation-integration` job runs `:composeApp:connectedDebugAndroidTest` on push and is the verification vehicle.
  - API facts verified with javap instead of memory: mockwebserver 4.12.0's throttling method is `throttleBody(bytesPerPeriod, period, unit)`, not `setThrottleBody`.
- **Deliberately not claimed:** the gate is **not closed**. `downloads` stays `false` on Android until the CI emulator job runs this test green. The matrix now says exactly that.
- **Next smallest safe step:** after CI confirms `DownloadPipelineAndroidTest`, flip Android `downloads = true`; meanwhile continue C-gate evidence — the coordinator-level late-event path (a late `NavigationCompleted` after `closeTab` must not write history/metadata for the closed tab) is untested.
- **Files:** `composeApp/src/androidMain/.../AndroidChromiumSurfaceRenderer.kt` (listener extracted, `createWebView` internal), `composeApp/src/androidInstrumentedTest/.../DownloadPipelineAndroidTest.kt` (new), `gradle/libs.versions.toml`, `composeApp/build.gradle.kts` (mockwebserver), `docs/PLATFORM_CAPABILITY_MATRIX.md`, `docs/AI_WORK_LOG.md`.

### 2026-10-23 — C3 evidence: coordinator late-event projection

- **Goal:** Close the C3 test gap recorded in the previous entry: the coordinator's projection of `NavigationCompleted` into history/metadata had no test for a closed tab.
- **Completed:** `lateEventForClosedTabDoesNotWriteHistoryOrMetadata` in `BrowserSessionCoordinatorTest`. The test owns the event hub so it can inject an event the controller filter did not drop (defense in depth), and uses a valid event for a live tab as a positive control: once that event's effect is visible, the collector has provably processed the late event published before it — no sleeps, fully deterministic.
- **Test evidence:** `:browser-core:jvmTest` green (57 tests; coordinator class now 8); `:browser-download:jvmTest` and `:composeApp:desktopTest` green.
- **Next smallest safe step:** after CI confirms `DownloadPipelineAndroidTest`, flip Android `downloads = true`. Remaining C-gate candidates: C4 source-specific cases (redirect-source navigations) and the `composeApp` commonMain UI/adapter package split evaluation.
- **Files:** `browser-core/src/commonTest/.../session/BrowserSessionCoordinatorTest.kt`, `docs/AI_WORK_LOG.md`.

### 2026-10-23 — C4 evidence: new-tab decision and redirect-source policy

- **Goal:** Close the two remaining items on C4's minimum verification list ("allow/cancel/external/new-tab, redirects, unknown schemes and late callbacks"): the new-tab decision path and redirect-source navigations.
- **Completed:**
  - `BrowserControllerTest.newTabDecisionIsReturnedToTheCallerWithoutLoadingTheCurrentTab`: a custom policy returning `OpenInNewTab` must hand the decision back to the caller for routing; the current tab's engine is never dispatched.
  - `BrowserNavigationCoordinatorTest.redirectSourceNavigationFollowsTheSameBuiltInChecks`: a permissive custom policy cannot let a `REDIRECT`-source navigation bypass the built-in executable-URL checks (script URL cancelled, web URL allowed).
- **Test evidence:** `:browser-core:jvmTest` green (59 tests; controller class 8, navigation class 7); `:browser-download:jvmTest`, `:composeApp:desktopTest` green; Android + Android-test + iOS compiles green.
- **Next smallest safe step:** after CI confirms `DownloadPipelineAndroidTest`, flip Android `downloads = true`. Remaining candidate: evaluate whether `composeApp` commonMain should split UI from common adapter code (same bar as the core split: only with a real boundary).
- **Files:** `browser-core/src/commonTest/.../engine/BrowserControllerTest.kt`, `browser-core/src/commonTest/.../navigation/BrowserNavigationCoordinatorTest.kt`, `docs/AI_WORK_LOG.md`.
