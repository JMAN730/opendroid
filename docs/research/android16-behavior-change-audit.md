# Android 16 (API 36) Behavior-Change Audit for Threadfin

Resolves wayfinder ticket #57. Audit date: 2026-08-01. Baseline: `compileSdk 35` / `targetSdk 35`
(`app/build.gradle:13,18`), minSdk 26, single-activity Compose app, WorkManager 2.9.0.

Sources (official):

- Behavior changes: apps targeting Android 16+ — https://developer.android.com/about/versions/16/behavior-changes-16
- Behavior changes: all apps — https://developer.android.com/about/versions/16/behavior-changes-all

Verdicts: **AFFECTED** (code change required), **NOT AFFECTED** (evidence shows the change cannot
bite), **NEEDS RUNTIME CHECK** (no code change proven necessary, but must be verified on an
Android 16 device/emulator before the targetSdk 36 bump ships).

## Summary table

| # | Behavior change | Verdict | Effort |
|---|---|---|---|
| 1 | Edge-to-edge opt-out removed | AFFECTED (cleanup + verification) | S–M |
| 2 | Predictive back on by default | NEEDS RUNTIME CHECK | S |
| 3 | Large-screen orientation/resizability restrictions ignored | NOT AFFECTED (manifest) / NEEDS RUNTIME CHECK (layout) | S |
| 4 | elegantTextHeight deprecated/disabled | NOT AFFECTED | — |
| 5 | Health/fitness granular permissions | NOT AFFECTED | — |
| 6 | Bluetooth bond-loss intents & bond handling | NOT AFFECTED | — |
| 7 | MediaStore version lockdown | NOT AFFECTED | — |
| 8 | Safer Intents (`intentMatchingFlags`, opt-in) | NOT AFFECTED | — |
| 9 | Local Network Permission (future enforcement) | NEEDS RUNTIME CHECK (real exposure via LAN LLM endpoints) | M (later) |
| 10 | App-owned photos pre-selected in photo picker | NOT AFFECTED | — |
| 11 | `scheduleAtFixedRate` missed-execution change | NOT AFFECTED | — |
| 12 | JobScheduler/WorkManager quota optimizations (all apps) | AFFECTED | M |
| 13 | Abandoned-job stop reason (all apps) | NOT AFFECTED | — |
| 14 | `setImportantWhileForeground` fully deprecated | NOT AFFECTED | — |
| 15 | Ordered-broadcast priority no longer global | NOT AFFECTED | — |
| 16 | ART internal changes | NOT AFFECTED | — |
| 17 | 16 KB page size compatibility | NEEDS RUNTIME CHECK (native libs from LiteRT-LM / ML Kit) | S–M |
| 18 | Disruptive accessibility announcements deprecated | NOT AFFECTED | — |
| 19 | Automatic themed app icons (QPR2) | AFFECTED (minor) | S |
| 20 | Intent-redirection hardening (all apps) | NOT AFFECTED | — |
| 21 | Companion device pairing timeout changes | NOT AFFECTED | — |
| 22 | GPU syscall filtering | NOT AFFECTED | — |

---

## 1. Edge-to-edge opt-out removed — AFFECTED (cleanup + per-screen verification)

Source: https://developer.android.com/about/versions/16/behavior-changes-16#edge-to-edge

`R.attr#windowOptOutEdgeToEdgeEnforcement` is ignored when targeting 36; every activity is
edge-to-edge, and `Window.setStatusBarColor` / `setNavigationBarColor` are deprecated no-ops.

Evidence:

- The app does **not** use `windowOptOutEdgeToEdgeEnforcement` anywhere (no matches in
  `app/src/main`), and targets 35 today — so it is already living under Android 15's
  edge-to-edge enforcement on API 35 devices. The opt-out removal itself therefore cannot
  regress it further; the remaining work is hygiene and verification.
- `app/src/main/java/com/opendroid/ai/ui/theme/Theme.kt:52-53` sets
  `window.statusBarColor` / `window.navigationBarColor` — deprecated no-ops on 15+/16.
  Dead code once targeting 36; the intended bar backgrounds must come from the content
  drawn behind the bars instead.
- No `enableEdgeToEdge()` call in `app/src/main/java/com/opendroid/ai/MainActivity.kt`
  (whole file reviewed; `setContent` at line 35). No `fitsSystemWindows`, no
  `WindowInsets`/`systemBarsPadding`/`imePadding` usage anywhere in `app/src/main`.
- Inset handling today relies entirely on Material 3 defaults: 18 screens use `Scaffold`
  with `TopAppBar` (e.g. `ui/Navigation.kt:256`, `ui/screens/ChatScreen.kt:195`,
  `ui/screens/SettingsScreen.kt:119`, `ui/screens/OnboardingScreen.kt:53`, …) and the root
  tab bar is an M3 `NavigationBar` inside `Scaffold(bottomBar = ...)`
  (`ui/Navigation.kt:256-292`). M3 `Scaffold`/`TopAppBar`/`NavigationBar` consume
  status/navigation-bar insets by default, which is why the app has survived targetSdk 35.

Required fix:

1. Call `enableEdgeToEdge()` in `MainActivity.onCreate` (makes behavior explicit and correct
   pre-16, and gives proper transparent system-bar scrims).
2. Delete the deprecated `statusBarColor`/`navigationBarColor` writes in `Theme.kt:52-53`
   (keep the `isAppearanceLight*` calls at lines 55-56 — still valid); ensure the tab-bar and
   top-bar container colors extend behind the bars.
3. Manual pass over all 18 Scaffold screens on an API 36 emulator (gesture + 3-button nav,
   IME open in ChatScreen's input row and SettingsScreen text fields) to confirm nothing sits
   under the bars or keyboard; add `imePadding()`/`navigationBarsPadding()` only where a screen
   actually clips. `AlertDialog` usages (ChatScreen.kt:607,634; SettingsScreen.kt:1462,2065,2096;
   PermissionsScreen.kt:246; CrashLogScreen.kt:74; BenchmarkScreen.kt:93) are inset-safe by default.

Effort: S–M (0.5–1 day incl. device verification).

## 2. Predictive back enabled by default — NEEDS RUNTIME CHECK

Source: https://developer.android.com/about/versions/16/behavior-changes-16#predictive-back

When targeting 36, predictive-back system animations are on by default; `onBackPressed()` is not
called and `KEYCODE_BACK` is not dispatched.

Evidence:

- Manifest (`app/src/main/AndroidManifest.xml`) has no `android:enableOnBackInvokedCallback`.
- Zero matches for `onBackPressed`, `OnBackPressedCallback`, `BackHandler`, `OnBackInvoked`,
  `KEYCODE_BACK` in `app/src/main` — the app never intercepts back, so nothing breaks
  functionally when the legacy path goes away.
- Top-level tab switching is plain state (`currentTab` in `ui/Navigation.kt`), not back-stack
  based; `navigation-compose:2.7.7` (`app/build.gradle:164`) predates in-app predictive-back
  animation support (2.8+), but no destination relies on back interception.

Required fix: none mandatory. Recommended: bump `navigation-compose` to 2.8+ for proper
predictive-back animations, and verify back-to-home animation + tab behavior on API 36.
Effort: S.

## 3. Orientation/resizability/aspect-ratio restrictions ignored on ≥600dp — NOT AFFECTED (manifest), NEEDS RUNTIME CHECK (layout)

Source: https://developer.android.com/about/versions/16/behavior-changes-16#adaptive-layouts

Evidence: `AndroidManifest.xml` declares no `android:screenOrientation`, `resizeableActivity`,
`minAspectRatio`/`maxAspectRatio`; zero matches for `setRequestedOrientation` in `app/src/main`.
There is nothing for the platform to ignore. Layouts are Compose and already resizable; a smoke
test on a tablet/desktop-window emulator profile is prudent but no restriction-related work exists.
Effort: S (verification only).

## 4. elegantTextHeight deprecated and disabled — NOT AFFECTED

Source: https://developer.android.com/about/versions/16/behavior-changes-16#elegant-fonts

Evidence: zero matches for `elegantTextHeight` in `app/src/main`; UI is 100% Compose (no
`TextView` layouts), and the app ships no Arabic/Thai/etc. localized UI vertical-metric hacks.

## 5. Health & fitness granular permissions — NOT AFFECTED

Source: https://developer.android.com/about/versions/16/behavior-changes-16#health-fitness-permissions

Evidence: `AndroidManifest.xml:6-50` declares no `BODY_SENSORS`/`BODY_SENSORS_BACKGROUND`, and
there is no heart-rate/sensor usage in code.

## 6. Bluetooth bond-loss / encryption-change intents — NOT AFFECTED

Sources: https://developer.android.com/about/versions/16/behavior-changes-16#bond-loss,
https://developer.android.com/about/versions/16/behavior-changes-all#bluetooth-bond-loss

Evidence: the only Bluetooth code toggles the adapter —
`actions/SystemActions.kt:340` (`BluetoothAdapter.getDefaultAdapter()`) and `:372`
(`ACTION_REQUEST_ENABLE`). No bonding, no `ACTION_BOND_STATE_CHANGED` listener, no CDM usage.

## 7. MediaStore version lockdown — NOT AFFECTED

Source: https://developer.android.com/about/versions/16/behavior-changes-16#mediastore-version

Evidence: `MediaStore` usage is limited to launching camera / play-from-search intents
(`actions/MediaActions.kt:51-53,169,194`, `actions/AdvancedControlActions.kt:330`); no
`MediaStore.getVersion()` calls.

## 8. Safer Intents (`android:intentMatchingFlags`) — NOT AFFECTED

Source: https://developer.android.com/about/versions/16/behavior-changes-16#safer-intents

Opt-in in Android 16; the manifest does not set `intentMatchingFlags`. The app fires many
*outgoing* implicit intents (MediaActions, SystemActions, etc.), which this change does not
restrict. No action.

## 9. Local Network Permission — NEEDS RUNTIME CHECK (real future exposure)

Source: https://developer.android.com/about/versions/16/behavior-changes-16#local-network-protection

Not enforced in Android 16 by default (opt-in via `RESTRICT_LOCAL_NETWORK` compat flag;
enforcement planned 25Q2–26Q2). It is genuinely relevant here: Threadfin lets users point the
LLM provider at a LAN endpoint — Settings explicitly suggests
`http://192.168.1.50:11434` (Ollama) at `ui/screens/SettingsScreen.kt:488` and
`http://192.168.1.50:4141` at `:1211`, with `core/util/UrlUtils.kt:7` normalizing raw LAN IPs.
OkHttp/Retrofit unicast HTTP to a LAN IP will require the new runtime permission once enforced.

Required fix (future ticket): test with `adb shell am compat enable RESTRICT_LOCAL_NETWORK
com.opendroid.aiagent`; plan to declare and request the local-network runtime permission when
the enforcement release lands, gated to when the base URL resolves to a private address.
Effort: M, deferred until Google ships enforcement.

## 10. App-owned photos pre-selected in photo picker — NOT AFFECTED

Source: https://developer.android.com/about/versions/16/behavior-changes-16#app-owned-photos

Evidence: zero matches for `PickVisualMedia`/photo-picker APIs; media access is
`MANAGE_EXTERNAL_STORAGE`/legacy storage permissions (`AndroidManifest.xml:40-42`) and model-file
imports via SAF `Uri` (`data/repository/ModelRepository.kt:152`).

## 11. `scheduleAtFixedRate` missed executions — NOT AFFECTED

Source: https://developer.android.com/about/versions/16/behavior-changes-16#fixed-rate-work-scheduling

Evidence: zero matches for `scheduleAtFixedRate`/`fixedRateTimer`/`Timer(` in `app/src/main`;
periodic work is coroutine-based.

## 12. JobScheduler quota optimizations (all apps) — AFFECTED

Source: https://developer.android.com/about/versions/16/behavior-changes-all#job-quota-optimization

Android 16 tightens regular/expedited job runtime quotas by standby bucket and only exempts jobs
started while the app is visible or running an eligible FGS.

Evidence:

- The multi-GB on-device model download runs as a plain `CoroutineWorker` —
  `core/llm/ModelDownloadWorker.kt:25-28` — enqueued as ordinary `OneTimeWorkRequest` with **no
  constraints, no `setExpedited`, no `setForeground`/`ForegroundInfo`**
  (`data/repository/ModelRepository.kt:139-149`; repo-wide grep for `setForeground|setExpedited`
  has zero matches). Under Android 16 quotas a background-bucket app can have this worker stopped
  well before a large model finishes.
- Mitigating: the worker already checkpoints and resumes (`ModelDownloadWorker.kt:244` saves
  progress on stop), so quota stops degrade to slow, chunked progress rather than corruption.
- WorkManager is 2.9.0 (`app/build.gradle:173`); Google recommends current WorkManager for the
  new stop-reason reporting (`WorkInfo.getStopReason()`), and 2.9.x predates several JobScheduler
  quota-era fixes.

Required fix:

1. Convert `ModelDownloadWorker` to a long-running worker: implement `getForegroundInfo()` and
   call `setForeground()` with a `dataSync`-type FGS notification (add
   `FOREGROUND_SERVICE_DATA_SYNC` permission + `android:foregroundServiceType="dataSync"` via
   WorkManager's built-in `SystemForegroundService` manifest merge). Downloads are user-initiated,
   so FGS-start restrictions are satisfied.
2. Add a `NetworkType.CONNECTED` constraint while at it (currently none).
3. Upgrade `androidx.work` 2.9.0 → 2.10.x and log `WorkInfo.stopReason` in the existing download
   flow logging for field diagnosis.

Effort: M (~1 day incl. notification UX).

## 13. Abandoned-job stop reason — NOT AFFECTED

Source: https://developer.android.com/about/versions/16/behavior-changes-all#abandoned-jobs

Docs state no action needed for WorkManager users (it manages `JobParameters` lifecycles); the app
has no direct `JobScheduler`/`JobService` code (zero matches).

## 14. `JobInfo#setImportantWhileForeground` fully deprecated — NOT AFFECTED

Source: https://developer.android.com/about/versions/16/behavior-changes-all#deprecate-important-while-foreground

Evidence: zero matches for `setImportantWhileForeground` / `JobInfo` in `app/src/main`.

## 15. Ordered-broadcast priority no longer global — NOT AFFECTED

Source: https://developer.android.com/about/versions/16/behavior-changes-all#ordered-broadcast-priority

Evidence: single-process app (no `android:process` anywhere); receivers are an unordered
`BOOT_COMPLETED` receiver (`AndroidManifest.xml:99-105`) and a runtime screen-state receiver
(`accessibility/OpenDroidAccessibilityService.kt:103`); no `android:priority` or
`IntentFilter.setPriority` matches.

## 16. ART internal changes — NOT AFFECTED

Source: https://developer.android.com/about/versions/16/behavior-changes-all#art

No reflection into ART/libcore internals found; reflection surface is Hilt/Retrofit/Gson via
public APIs with ProGuard rules already exercised in CI (`app/build.gradle:37-41`). Keep
dependencies current; no action.

## 17. 16 KB page size compatibility mode — NEEDS RUNTIME CHECK

Source: https://developer.android.com/about/versions/16/behavior-changes-all#16kb-page-size-compat

The app ships no first-party NDK code, but bundles native libraries transitively:
`com.google.ai.edge.litertlm:litertlm-android:0.14.0` (`app/build.gradle:205`, on-device LLM
inference — large `.so` payload) and `com.google.mlkit:genai-prompt:1.0.0-beta2`
(`app/build.gradle:202`). If any bundled `.so` is only 4 KB-aligned, Android 16 on 16 KB devices
runs the app in compat mode and shows a warning dialog.

Required fix: run `zipalign -c -P 16 -v 4 app-release.apk` (or Android Studio's APK analyzer
alignment check) against a release build; if misaligned, upgrade the offending AAR (recent
LiteRT releases are 16 KB-ready). After bumping `compileSdk` to 36, optionally set
`android:pageSizeCompat` to suppress the dialog only as a stopgap. Effort: S–M (build check;
possible dependency bump).

## 18. Disruptive accessibility announcements deprecated — NOT AFFECTED

Source: https://developer.android.com/about/versions/16/behavior-changes-all#disruptive-a11y

Evidence: zero matches for `announceForAccessibility` / `TYPE_ANNOUNCEMENT`. The app *hosts* an
`AccessibilityService` (`accessibility/OpenDroidAccessibilityService.kt`) that consumes events;
the deprecation targets apps *sending* announcements, which Threadfin never does.

## 19. Automatic themed app icons (Android 16 QPR2) — AFFECTED (minor)

Source: https://developer.android.com/about/versions/16/behavior-changes-all#themed-icons

Evidence: both adaptive icons lack a monochrome layer —
`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml` define only
`<background>` + `<foreground>`. QPR2 will auto-generate a themed icon from the foreground,
which may render the neon-green droid mark poorly.

Required fix: add an `android:monochrome` layer with a purpose-drawn single-color glyph.
Effort: S (asset + 2-line XML).

## 20. Intent-redirection hardening (all apps) — NOT AFFECTED

Source: https://developer.android.com/about/versions/16/behavior-changes-all#intent-redirect

Evidence: the app never launches nested Intents extracted from other apps' extras — zero
matches for `getParcelableExtra` in `app/src/main`. `ReplyDispatcher.kt:54` fires notification
`RemoteInput` PendingIntents, which is the supported path and unaffected.

## 21. Companion device pairing timeout changes — NOT AFFECTED

Source: https://developer.android.com/about/versions/16/behavior-changes-all#cdm-timeout

Evidence: no `CompanionDeviceManager` usage (zero matches).

## 22. GPU syscall filtering — NOT AFFECTED

Source: https://developer.android.com/about/versions/16/behavior-changes-16#gpu-apis

No native GPU code; Mali IOCTL filtering targets direct GPU users on Pixel 6-9. Compose/Skia
goes through supported drivers.

---

## Prioritized fix list (ticket-ready)

1. **[Prereq] Bump `compileSdk`/`targetSdk` 35 → 36** (`app/build.gradle:13,18`), fix any new
   lint/deprecation fallout. Everything below assumes this. Effort: S.
2. **[Ticket] Model download vs. Android 16 job quotas (#12)** — long-running worker with
   `setForeground()` + dataSync FGS, network constraint, WorkManager 2.9.0 → 2.10.x, stop-reason
   logging. Biggest real-user risk (multi-GB downloads silently throttled). Effort: M.
3. **[Ticket] Edge-to-edge finalization (#1)** — `enableEdgeToEdge()` in MainActivity, remove
   dead `statusBarColor`/`navigationBarColor` in `Theme.kt:52-53`, verify all 18 Scaffold screens
   + IME on API 36. Effort: S–M.
4. **[Ticket] 16 KB alignment verification (#17)** — zipalign check of release APK; bump
   LiteRT-LM/ML Kit if misaligned. Effort: S–M.
5. **[Small, can fold into #3's ticket] Monochrome launcher icon (#19)**. Effort: S.
6. **[Verification-only] Predictive back (#2)** — API 36 smoke test; optional
   `navigation-compose` 2.7.7 → 2.8+. Effort: S.
7. **[Deferred/watch] Local Network Permission (#9)** — test under `RESTRICT_LOCAL_NETWORK`;
   implement runtime permission for LAN LLM endpoints (Ollama/proxy) when Google ships
   enforcement. Effort: M, not blocking targetSdk 36.
