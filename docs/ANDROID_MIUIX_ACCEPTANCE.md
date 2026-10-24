# Android Miuix UI Acceptance Plan

## Scope and priority

This phase covers **Android phones and Android tablets only**. Desktop UI work is explicitly out of scope until Android passes the acceptance gates below. Keep the existing Miuix KMP 0.9.4 Luna visual baseline; do not reintroduce a Material 3 visual system or build a parallel UI architecture.

Compose remains the presentation layer. Browser session, tab metadata, navigation, engine creation/closure, and native surface lifetime stay owned by the existing browser core and platform adapter contracts.

## Acceptance order

### A1 — Android phone baseline
- Validate the home screen, address/search input, address submission, loading/stop/reload, tab selection, new-tab action, back/forward controls, and bottom navigation on a compact phone-sized viewport.
- Ensure interactive controls have clear enabled/disabled states, at least 48 dp targets for primary tab actions (close/new tab), accessible action descriptions, and no horizontal overflow at narrow widths.
- Add or update focused tests for layout decisions and important UI behavior where they can be tested reliably.
- Acceptance: Android build and automated tests pass; a phone emulator smoke test confirms the main navigation path.

### A2 — Android tablet and large-screen layout
- Validate portrait and landscape tablet sizes, split-screen/narrow window widths, and foldable-like compact/expanded widths.
- Check address bar and tab strip width constraints, page content sizing, spacing, and bottom navigation placement.
- Ensure size/orientation changes only recompose layout; they must not recreate the BrowserSession or native engine.
- Acceptance: breakpoint tests pass and tablet emulator/manual verification records the tested configurations. A unit test alone does not count as runtime validation.

### A3 — Core browser interactions on Android
- Verify Android system Back behavior against browser history, home/new-tab state, and tab state.
- Verify tab creation, selection, closing, active-tab fallback, and cleanup without leaking engines or losing retained tab state.
- Verify address field focus/IME submission and loading/stop/reload feedback.
- Acceptance: interaction and lifecycle tests pass, including Android instrumentation for native WebView behavior where applicable.

### A4 — Miuix visual and touch refinement
- Align surfaces, colors, shapes, typography, icons, pressed/disabled states, and spacing with the current Miuix Luna theme.
- Remove misleading placeholder controls or make their unsupported status explicit; do not imply that unimplemented bookmarks, privacy reports, or menus work.
- Verify scrolling, keyboard visibility, system bars, touch targets, and content insets on phone and tablet.
- Acceptance: no known blocking visual/interaction defects remain in the agreed Android configurations.

### A5 — Android release gate
- Run the relevant common tests, Android build, and Android emulator instrumentation workflows.
- Inspect CI results and runtime evidence; do not equate successful compilation with device-level acceptance.
- Record tested API level, viewport/device class, test run links, remaining limitations, and acceptance status in the work log.
- Acceptance: A1–A5 evidence is documented and the Android phone/tablet UI is approved before desktop UI work begins.

## Working rules

1. Work in the order above, starting at A1 and recording any blockers rather than silently skipping gates.
2. Reuse the existing BrowserScaffold, Miuix theme, session assembly, and core coordinator unless inspection demonstrates a concrete defect.
3. UI changes must not move session/engine lifecycle ownership into composables.
4. Do not claim tablet runtime verification based only on responsive breakpoint unit tests.
5. Desktop adaptation is deferred until A1–A5 pass.
