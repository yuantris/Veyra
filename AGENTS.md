# AGENTS.md — Veyra repository-wide development rules

> **Read this file before doing any work. It is the current source of truth for task priority and collaboration discipline.**
> Project owner direction: stop drifting into polish and test churn. Build a usable Android browser end to end first.

## 1. Product goal and current mainline

Veyra is a Kotlin Multiplatform / Compose Multiplatform browser. The near-term goal is **a working Android browser**, not parity with mature commercial browsers.

The required order is:

1. **Make the core actually collaborate:** connect `BrowserController`, `BrowserSessionCoordinator`, `BrowserEngineManager`, `BrowserSessionStore` and the existing contracts into one real command/state path. Audit implementation, not just interfaces.
2. **Connect Android's native engine boundary:** adapt the existing platform surface to the engine contract; Android's selected engine is the **Android platform WebView (`android.webkit.WebView`)**. Use the system-provided WebView for the current implementation. Keep Android-native WebView types inside the Android adapter and translate them to common contracts. Do not introduce GeckoView or a separately bundled Chromium engine. A future Gecko-based engine is out of scope unless the project owner explicitly reopens that decision.
3. **Connect Compose UI end to end:** the user can enter a URL/search, load and view a page, go back/forward, reload/stop, open/switch/close tabs, and see loading/title/URL/error state. Commands must reach the real engine and engine events must reach the UI.
4. **Run a focused Android phone acceptance pass** on this complete path. Fix blockers that prevent normal browsing.
5. **Finish Android tablet/adaptive behavior** after the phone path works, then address high-value touch/accessibility and visual defects.
6. Only after Android is usable and accepted, resume desktop UI or broaden other platform implementations.

This order overrides stale task notes that put isolated Miuix polish, desktop UI, self-compiled Chromium planning, or exhaustive platform parity ahead of the usable Android vertical slice. Preserve existing architecture/security invariants while following this order; do not use “architecture first” as an excuse to build abstractions without wiring a working path.

## 2. What counts as progress

Prefer a **vertical slice** that works from user action to native engine and back to visible state. Each task should remove a concrete blocker to that slice.

Before starting a task, answer internally (and in the final report):
- Which user-visible Android browsing path does this unblock?
- Which existing contract/implementation owns it?
- What is the smallest complete change that makes it work?
- What is the cheapest relevant evidence to verify it?

If a proposed task does not help the Android vertical slice, fix a demonstrated correctness/security/lifecycle defect, or unblock the next acceptance gate, defer it.

## 3. Explicit anti-drift rules

Do **not**:
- Spend a development turn repeatedly adjusting one icon, button size, spacing, color, or screenshot unless it blocks usability/accessibility or fails a stated acceptance criterion.
- Run or wait for Android emulator CI after every cosmetic or low-risk edit.
- Add more architecture documents, interfaces, modules, abstractions, test harnesses, or acceptance gates when the existing ones are sufficient to implement the next slice.
- Treat an interface, document, mock, compile success, or unit test as proof that browser functionality is wired end to end.
- Start desktop UI work before Android phone/tablet usability is accepted.
- Reopen settled engine, UI toolkit, module-boundary, or ownership decisions without concrete evidence and an explicit decision.
- Weaken a meaningful test or disable a test simply to make CI green.
- Fixate on commercial-browser feature parity before basic navigation and tab workflows work.

A focused test-driven fix is appropriate when it protects behavior or resolves a real failure. The rule is against disproportionate test/CI overhead and cosmetic churn, not against quality.

## 4. Architecture and ownership boundaries

Use the current repository and the detailed contracts in `docs/` as implementation references. Inspect actual source before assuming a class or API exists.

- **`BrowserController`**: command facade for open/navigate/back/forward/reload/stop/close and controller-visible page state. It is not a second engine registry.
- **`BrowserSessionCoordinator`**: coordinates tab/session metadata and engine workflows; Compose forwards commands to it/controller and observes state.
- **`BrowserEngineManager`**: sole authoritative owner/registry of live engine instances and their creation/closure lifecycle.
- **`BrowserSessionStore`**: tab/session metadata only; it must not implicitly create or destroy native engines.
- **`BrowserEngineContract`**: the one platform-neutral engine/session contract. Do not introduce a competing legacy contract or second registry.
- **Android adapter**: owns `android.webkit.WebView`-specific types, native callbacks and resources; translates them to common contracts.
- **Compose UI**: presentation and user input only. It must not become a competing owner of engine/session lifecycle, navigation policy, security decisions, or the live-engine registry.
- **`browser-download`**: download implementation and task/transport orchestration behind common contracts.

Dependency direction is one-way: common code must not depend on Compose UI, Android SDK, Android WebView or other native engine types. Avoid new Gradle modules unless a real dependency/lifecycle boundary requires one. Prefer completing the existing module path over adding speculative layers.

Keep these lifecycle invariants:
- Tab metadata, logical session, live engine, rendering surface, and active/visible state are distinct.
- Switching tabs or changing window size must not implicitly close/recreate a session or erase its navigation state.
- Surface detach/disposal is not session closure. Only an explicit close/shutdown path releases the resources it owns.
- Closing a tab and app shutdown must be idempotent; concurrent create/close/shutdown and cancellation must have defined outcomes.
- Late callbacks from a closed tab or superseded navigation must not mutate current state.
- Unsupported platform capabilities must be reported honestly. Do not claim behavior that the adapter cannot enforce or tests do not establish.

## 5. Android-first scope and UI

- Android (phone first, then tablet) is the active product target. **Do not do desktop UI work while Android is incomplete.**
- Miuix is the selected Compose UI library. Do not replace it with Material 3 or add another UI toolkit without an explicit product decision.
- The UI should be functional before it is polished. Keep the address/search field, page surface, navigation controls, tab creation/switch/close, and loading/error feedback connected to real state.
- Bookmarks, menus, privacy reports, downloads, extensions and other secondary features must not displace the basic browse/navigation/tab vertical slice unless a specific current task requires them.
- Adaptive layout must not change session ownership or recreate the engine just because a phone rotates, a window resizes, or the app moves between compact and expanded widths.

## 6. Test and CI policy: risk-based, not ritual-based

Use the cheapest evidence that answers the question. Do not make the emulator suite the gate for every small edit.

### Per commit / normal iteration
- Run the narrow relevant compile/test task.
- Keep common core and download unit tests in the normal CI path.
- Keep Android/Desktop compile checks where the change can affect compilation.
- Run focused common tests for controller, coordinator, policy, or lifecycle changes.

### Android emulator integration
Run the full emulator suite when:
- changing native engine adapter behavior or surface lifecycle;
- changing navigation delivery, tab switching/closing, history restoration, permissions, downloads, bridge/security paths;
- a relevant emulator regression was observed;
- preparing a milestone acceptance/release.

For cosmetic-only or low-risk layout changes, do not wait for a full emulator run unless a measured UI acceptance test is directly relevant. Batch related UI changes and run emulator verification at a meaningful checkpoint. Prefer manually triggered emulator CI or a dedicated integration workflow over running it on every push/PR, while preserving fast compile/unit-test feedback. Never claim emulator verification if it was skipped or is still running.

### Before declaring a milestone usable
Run the end-to-end Android phone flow against the real adapter/engine: launch → enter URL/search → load page → back/forward → reload/stop → open/switch/close tabs → confirm state survives switching → observe loading and failure state. Then check tablet/adaptive behavior. Record actual results and known gaps.

## 7. Practical implementation workflow

1. **Inspect current code and git/CI state.** Read the relevant contract and implementation, then the closest tests. Do not rely on a previous AI summary alone.
2. **Name the concrete blocker and owner.** Avoid broad refactors unless the current structure demonstrably prevents the slice from working.
3. **Implement the smallest complete vertical change.** Prefer connecting existing components over adding abstractions.
4. **Verify narrowly first.** Run the relevant compile/unit test; run emulator integration according to §6.
5. **Inspect real CI outcomes.** A pushed commit is not evidence of passing checks. Distinguish passed, failed, queued, running, and skipped.
6. **Update only documentation affected by the actual decision/change.** Do not create documentation as a substitute for implementation.
7. **Report exact changes and evidence.** State files/commit, tests actually run and outcomes, what is still broken/unsupported, and the next concrete implementation step.

If CI fails, read the failing job/log and fix the underlying issue. Do not blindly rerun all jobs or make unrelated edits. If a check is queued/running, say so. If a failure is unrelated, explain why with evidence.

## 8. Security and correctness remain non-negotiable

Speed does not justify unsafe behavior:
- Validate navigation centrally where possible; reject malformed/executable schemes by default and state adapter interception limits truthfully.
- Treat page-provided bridge messages as untrusted. Use trusted engine origin/frame context, exact origin matching and fail-closed authorization. Do not advertise a JavaScript bridge before end-to-end security tests exist.
- Website permissions, OS runtime permissions and Veyra/extension permissions are separate decisions.
- A download callback is only a candidate, not user consent to start a transfer.
- Private profiles must not silently fall back to regular persistent storage.
- Preserve cancellation semantics; do not swallow failures or use `!!` to hide nullable states.

For security-sensitive or lifecycle-affecting changes, add focused regression coverage. Prefer deterministic concurrency tests over sleeps.

## 9. Repository coordination

- Relevant design references include `docs/ARCHITECTURE.md`, `docs/BROWSER_CORE_ARCHITECTURE.md`, `docs/PLATFORM_CAPABILITY_MATRIX.md`, and current adapter docs. Read only what is needed for the task; do not require rereading every document for a cosmetic change.
- If a document conflicts with this current priority or the chosen Android platform WebView direction, do not silently follow the stale instruction. Inspect code, record the conflict, and update the relevant decision text in the same change when appropriate.
- Record meaningful implementation sessions in `docs/AI_WORK_LOG.md`: goal, completed work, actual test evidence, files/commit, and next concrete step. Keep prior entries intact; never mark proposed or unverified work as completed.
- Never claim a feature is finished because a contract or plan exists.

## 10. Default next task

Unless the project owner explicitly redirects, continue with **the next missing link in the Android end-to-end path**:

1. audit the current `BrowserController` ↔ `BrowserSessionCoordinator` ↔ `BrowserEngineManager` command/state flow;
2. identify and fix the first concrete disconnect or missing lifecycle path;
3. connect the Android system WebView adapter to the existing engine contract;
4. wire Compose actions and observable state to that path;
5. verify the complete basic browsing flow.

Do not start with another button polish pass, desktop UI, a new architecture proposal, or an exhaustive emulator run.

## 11. AI change-quality gate: prevent avoidable CI failures

The AI must optimize for **correct changes per commit**, not number of commits or speed of producing a diff. CI is a verification layer, not a substitute for reading code or checking obvious mistakes. Repeated avoidable failures are a process defect and must change the next iteration's workflow.

### Before editing
- Read the exact current implementation, interface declarations, nearby call sites, and the closest existing tests. Do not infer SAM/lambda support, lifecycle ownership, threading guarantees, or policy behavior from a type's name.
- Identify the intended invariant and production behavior first. Tests must respect production security defaults; if deterministic test input needs special policy, inject a narrowly scoped test policy rather than weakening production behavior.
- Check the current branch/HEAD and latest workflow results. Do not stack a new speculative fix on top of an unresolved failure without understanding that failure.

### Before committing/pushing
- Review the full diff for type/API correctness, Kotlin syntax, nullability, lifecycle ownership, threading, and unintended production behavior changes.
- Run the cheapest relevant local compile/test that is available. At minimum, reason through the exact changed call sites and test setup; never claim a local check ran when it did not.
- For UI/instrumentation tests, use condition-based synchronization for the state under test (for example, wait until the real WebView is attached), not immediate assertions against asynchronous Compose/UI work. Keep view-tree access on the main thread. Avoid arbitrary sleeps where a deterministic condition is available.
- Ensure each test proves the intended production path. A mock, interface-only test, successful compile, or unrelated green job is not proof of end-to-end behavior.
- If local execution is unavailable, state that limitation before pushing and use the narrowest CI feedback loop appropriate to the risk; do not pretend CI is a pre-commit check.

### After a failure
- Read the failing job's actual test report and relevant log lines before editing. Classify the failure (compile/API, assertion/behavior, timing/lifecycle, infrastructure/flaky environment) using evidence.
- Fix the root cause with the smallest complete change. Do not weaken assertions, loosen security policy, disable tests, or add retries merely to get a green result.
- Re-run the relevant check and inspect the newest commit's workflow result. Distinguish clearly between failed, running, skipped, and passed; do not report success until observed.
- If a change causes an avoidable failure, record the specific missed preflight check and add that check to the workflow for subsequent edits. Do not repeat the same class of mistake.

### Required report
For each meaningful change, report: the concrete behavior changed; commit/branch; checks actually executed and their observed outcomes; any failed run and its evidence-based root cause; what remains unverified; and the next smallest useful step. Never use a green compile or a different successful workflow to imply that a failing emulator test passed.