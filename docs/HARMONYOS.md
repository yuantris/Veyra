# HarmonyOS integration notes

Veyra plans to use CPF-KMP-CMP for the HarmonyOS application shell and ArkTS `Web` / ArkWeb for web rendering.

The community `compose-webview-multiplatform` HarmonyOS adaptation is a separate integration path from the upstream Android/iOS/Desktop artifact. Its current notes describe a Kotlin/Compose compatibility matrix and an ArkTS Web + N-API bridge; do not assume the upstream artifact can simply be added to the HarmonyOS source set.

## Integration checklist

- Start from the CPF-KMP-CMP sample matching the installed toolchain.
- Keep the HarmonyOS host project under `harmonyApp/` and keep signing/device configuration out of source control.
- Verify Compose UI export and HAP packaging before wiring in browser APIs.
- Use ArkWeb for the initial engine adapter.
- Define a small bridge protocol for navigation events, JS evaluation, cookies, downloads and permissions.
- Do not promise identical engine behavior across platforms; document capability differences.

Reference:
- https://atomgit.com/org/CPF-KMP-CMP/discussions/15
- https://gitcode.com/oh-tpc/compose-webview-multiplatform
- https://gitcode.com/CPF-KMP-CMP/kmp-cmp-example
