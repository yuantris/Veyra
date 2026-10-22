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
- **Recently completed (2026-10-22):** renamed `:download-core` to `:browser-download`; reorganized `:browser-core` into root contracts plus `engine` / `session` / `navigation` / `security` packages; fixed the iOS `@JvmInline` resolution gap and added a CI `ios-compile` job; hardened C1/C3 with six deterministic race and failure tests (54 browser-core tests total).
- **Known open issues:**
  - `:browser-download` is built and tested but not yet depended on by `:composeApp`.
  - `composeApp` commonMain still mixes UI (`App.kt`) with common adapter code (`ComposeWebViewEngineAdapter`, `BrowserNavigationRequestInterceptor`, `ComposeWebViewSurfaceRenderer`, `ComposeWebViewPlatformCapabilities`) in one flat package.
- **Next smallest safe steps:**
  1. Wire `:browser-download` into the `composeApp` composition root behind the existing `DownloadController` contract, then add a close-vs-start race test.
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
