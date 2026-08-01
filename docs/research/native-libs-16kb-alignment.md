# Native libs 16KB page-size alignment audit

**Ticket:** [#58](https://github.com/JMAN730/opendroid/issues/58)
**Date:** 2026-08-01
**Verdict: PASS — no version bumps or build changes required.**

Google Play requires that apps targeting Android 15+ (API 35+) support 16KB page
sizes on 64-bit devices (enforced for new apps and updates since 2025-11-01).
Threadfin has `compileSdk 35` / `targetSdk 35` (`app/build.gradle`), so the
requirement applies. The concrete ELF requirement: every `PT_LOAD` segment in
every shipped `.so` must have `p_align >= 0x4000` (16384).

## Dependencies audited

Confirmed in `app/build.gradle` (lines 202 and 205) — coordinates match the ticket:

- `com.google.mlkit:genai-prompt:1.0.0-beta2`
- `com.google.ai.edge.litertlm:litertlm-android:0.14.0`

## Method

AARs taken from the local Gradle cache
(`~/.gradle/caches/modules-2/files-2.1/...`, SHA-addressed copies of the
artifacts published on `dl.google.com/dl/android/maven2`). Each AAR was unzipped
and every `jni/<abi>/*.so` had its ELF64 program header table parsed directly
with a Python 3.14 script (struct-parses `e_phoff`/`e_phentsize`/`e_phnum`, then
reads `p_align` at offset 0x30 of each `PT_LOAD` entry). The Android NDK's
`llvm-readelf` was not installed on this machine; direct ELF parsing is
equivalent — `p_align` is read from the same program-header field `readelf -l`
reports.

## Results

### com.google.mlkit:genai-prompt:1.0.0-beta2

| File | ABI | PT_LOAD p_align | Verdict |
|---|---|---|---|
| *(none)* | — | — | **N/A — ships no native code** |

The AAR contains only `AndroidManifest.xml`, `R.txt`, `classes.jar`,
`proguard.txt`, and license files. There is no `jni/` directory and no `.so`
embedded in `classes.jar` (verified by listing both archives). This is expected:
the GenAI Prompt API delegates inference to the on-device **AICore** system
service; the native code runs in Google's system-updated component, not in the
app's APK. Its cached transitive ML Kit artifacts (`genai-common:1.0.0-beta3`,
`common:18.11.0`) also contain zero `.so` entries.

**Verdict: trivially 16KB-safe (no native libs shipped).**

### com.google.ai.edge.litertlm:litertlm-android:0.14.0

The AAR ships exactly two native libs (`arm64-v8a` and `x86_64` only — no
32-bit ABIs, consistent with the QA test plan note in
`docs/qa/qwen-litert-test-plan.md`):

| File | ABI | PT_LOAD segments | p_align (each) | Verdict |
|---|---|---|---|---|
| `jni/arm64-v8a/liblitertlm_jni.so` (20,790,656 B) | arm64-v8a | 3 | `0x4000` (16384) | **PASS** |
| `jni/x86_64/liblitertlm_jni.so` (24,649,544 B) | x86_64 | 3 | `0x4000` (16384) | **PASS** |

Every `PT_LOAD` segment in both libraries reports `p_align = 0x4000`, exactly
the 16KB minimum. **Verdict: 16KB-safe.**

## Packaging / AGP side

- AGP is **8.8.2** (`build.gradle` line 9, Gradle 8.10.2). AGP **8.5.1+**
  performs 16KB zip alignment (`zipalign -P 16` equivalent) of uncompressed
  native libs automatically, so no manual zipalign step is needed.
- `android.packaging.jniLibs.useLegacyPackaging` is **not set** anywhere in the
  project (the only `packagingOptions` block excludes META-INF resources).
  With `minSdk 26` the default is `false`, i.e. `.so` files are stored
  uncompressed and page-aligned in the APK, and `extractNativeLibs=false` — the
  correct, required configuration. **No change needed.**

## Newer versions (checked live on Google Maven, 2026-08-01)

No bump is required for 16KB compliance, but for reference:

- `litertlm-android`: latest is **0.15.0** (0.14.0 is one behind).
- `genai-prompt`: latest is **1.0.0-beta4** (beta2 is two behind).

Any future bump should re-run the same `p_align` check on the new AAR.

## Sources

- [Support 16 KB page sizes — developer.android.com](https://developer.android.com/guide/practices/page-sizes)
  (PT_LOAD `p_align >= 2**14` requirement; AGP 8.5.1+ auto-alignment;
  `useLegacyPackaging` fallback for AGP <= 8.5; Play deadline 2025-11-01 for
  apps targeting API 35+)
- [litertlm-android maven-metadata](https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml)
- [genai-prompt maven-metadata](https://dl.google.com/dl/android/maven2/com/google/mlkit/genai-prompt/maven-metadata.xml)
