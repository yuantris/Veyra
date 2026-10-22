# Android Chromium plan — historical decision record

> Superseded as the active Android engine plan by [the architecture baseline](ARCHITECTURE.md), which selects GeckoView as Veyra's Android engine direction. Keep this document only as a record of earlier Chromium/WebView investigation; do not use it to override the current platform decision.

## Historical context

The initial prototype used Android System WebView, backed by the device's Chromium provider. That proved basic webpage rendering but did not establish browser-runtime capabilities such as WebExtensions, isolated profiles, or broad request interception.

Chromium, Blink and V8 do not by themselves imply a complete browser or WebExtensions runtime. Any future reconsideration of a Chromium-based Android engine must be a deliberate architecture decision and must validate the exact runtime's lifecycle, security model, capabilities, update strategy, licensing and distribution size before implementation.

The current implementation sequence is defined in `docs/ARCHITECTURE.md` and `docs/BROWSER_CORE_ARCHITECTURE.md`.
