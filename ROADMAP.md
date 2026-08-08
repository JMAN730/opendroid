# OpenDroid Roadmap

Living document. Reflects the state of `JMAN730/opendroid` as of **2026-08-05**, shipping
version **1.0.2** (`versionCode 3`).

Since the last revision of this document (2026-07-30), most of what was listed here as
upcoming work has shipped, tracked through **[issue #55, "Map: execute the OpenDroid
roadmap"](https://github.com/JMAN730/opendroid/issues/55)** — a running log of 38 linked
sub-issues (37 closed as of this writing) that superseded this file without it being
updated. This revision folds that log back in, corrects the drift, and files fresh
tickets for what's still genuinely open. Treat **#55 as the live index going forward**;
this file is the periodic snapshot.

---

## Where we are

| Dimension | State |
|---|---|
| **App** | 187 Kotlin sources under `app/src/main/java/com/opendroid/ai` |
| **SDK** | `minSdk 26`, `compileSdk`/`targetSdk 36` |
| **Toolchain** | Gradle 9.6.1, AGP 9.3.1, Kotlin 2.4.0 (AGP built-in Kotlin), JDK 21 (pinned) |
| **Tests** | 39 JVM unit-test files; **`androidTest` source set exists** — 5 files, including an accessibility-service harness and Keystore cipher instrumentation tests |
| **CI** | 4 jobs — unit tests + `assembleDebug`, `lintDebug`, `connectedDebugAndroidTest` on an API 26/36 emulator matrix, unsigned `assembleRelease` (R8) |
| **Lint** | 16 findings frozen in `app/lint-baseline.xml`; three-tier gate (baseline / `error` list / `warningsAsErrors true`) lives in the `lint {}` block of `app/build.gradle` |
| **Distribution** | GitHub Releases; marketing site auto-deploys to Pages from `website/` |
| **Open work** | See [Open items](#open-items) below and [issue #55](https://github.com/JMAN730/opendroid/issues/55) |

The last month of work closed out reliability/safety of the LLM layer (carried over from
the previous revision) **and** cleared almost the entire compliance and test-surface
backlog that used to sit here: `targetSdk 36`, the Gradle 9 / AGP 9 migration, the
`androidTest` source set and its CI matrix, the AndroidX/Retrofit/OkHttp dependency
refresh, the direct-Keystore credential migration, and three full passes of lint-baseline
burn-down. What's left is narrower and mostly at the edges: two doc/data gaps, one QA
sign-off, and the four still-unscoped 2.0 product questions.

---

## Recently shipped (since the 2026-07-30 revision)

Kept here for traceability; delete this section once nothing in it is likely to be
re-litigated.

- **`targetSdk 36`** — [#59](https://github.com/JMAN730/opendroid/issues/59) landed
  `compileSdk`/`targetSdk 36` with green CI; edge-to-edge and themed-icon fixes for
  Android 16 landed in [#60](https://github.com/JMAN730/opendroid/issues/60); model
  downloads were hardened against Android 16 JobScheduler quotas in
  [#62](https://github.com/JMAN730/opendroid/issues/62); native libs were confirmed
  16 KB-page-size safe in [#58](https://github.com/JMAN730/opendroid/issues/58).
  Device smoke test itself is still open — see [Open items](#open-items).
- **Gradle 9 / AGP 9 migration** — [#73](https://github.com/JMAN730/opendroid/issues/73)
  moved kapt to KSP and replaced `kotlinOptions`/`packagingOptions`/`rootProject.buildDir`
  with their AGP 9 successors; the toolchain bump to Gradle 9.6.1/AGP 9.3.1 followed as
  its own commit. `docs/build/agp9-migration.md` and `docs/build/agp9-preparation.md`
  have the detail.
- **Instrumentation tests for the accessibility path** —
  [#66](https://github.com/JMAN730/opendroid/issues/66) prototyped the harness,
  [#105](https://github.com/JMAN730/opendroid/issues/105) extracted node traversal into
  an injectable `AccessibilityNodeTraversal` and landed the readiness + known-screen-scrape
  tests, and CI now runs the full `androidTest` source set across an API 26/36 emulator
  matrix. Two real bugs surfaced along the way and were fixed:
  [#104](https://github.com/JMAN730/opendroid/issues/104) (Keystore AES-GCM IV reuse) and
  [#107](https://github.com/JMAN730/opendroid/issues/107) (the floating accessibility
  button swallowing taps meant for the app underneath).
- **Grant-all permissions onboarding** — issue **#15 is closed**. A single onboarding
  action now batches every missing, SDK-visible runtime permission; Accessibility, Write
  Settings, and all-files access stay per-card with an explicit Settings hand-off. A
  device-QA acceptance gate remains open — see [Open items](#open-items).
- **JDK 21 daemon pin** — PR #50 is merged: `gradle/gradle-daemon-jvm.properties` pins the
  daemon to JDK 21 and `README.md` / `docs/development_guide.md` were corrected to say
  "JDK 21 exactly," not "21+." One file was missed — see [Open items](#open-items).
- **Lint baseline burn-down** — three sequential passes
  ([#81](https://github.com/JMAN730/opendroid/issues/81),
  [#88](https://github.com/JMAN730/opendroid/issues/88),
  [#89](https://github.com/JMAN730/opendroid/issues/89),
  [#90](https://github.com/JMAN730/opendroid/issues/90),
  [#100](https://github.com/JMAN730/opendroid/issues/100),
  [#111](https://github.com/JMAN730/opendroid/issues/111),
  [#112](https://github.com/JMAN730/opendroid/issues/112)) took the baseline from 49 to
  16 entries, promoted nine checks to the `error` list, deleted five unused drawables, and
  flipped `warningsAsErrors true` once the ungated count reached zero. What's left in the
  baseline: 6 `IconLauncherShape`, 5 `ObsoleteSdkInt`, and one each of `SdCardPath`,
  `NonObservableLocale`, `ModifierParameter`, `IconLocation`, `AppCompatCustomView` — plus
  an `InlinedApi` global-actions cluster and one `AutoboxingStateCreation` finding that are
  still live in source but masked by the baseline (noted as Fog in #55; retiring them means
  shrinking the baseline by hand, not promoting a check).
- **Dependency refresh** — [#69](https://github.com/JMAN730/opendroid/issues/69) /
  [#71](https://github.com/JMAN730/opendroid/issues/71) /
  [#72](https://github.com/JMAN730/opendroid/issues/72) took `activity-compose` to
  1.13.0, `navigation-compose` to 2.9.8, `work-runtime-ktx` to 2.11.2, and Retrofit/OkHttp
  to 3.0.0 on the 5.4.0 BOM, each with redirect/cancellation/TLS/credential-redaction
  tests. `core-ktx` stays at 1.18.0 deliberately — `androidx.core:core:1.19.0` needs
  `compileSdk 37` and AGP 9.1, so that coordinate swap moved into a future SDK bump.
- **Secure credential storage** — [#78](https://github.com/JMAN730/opendroid/issues/78) /
  [#79](https://github.com/JMAN730/opendroid/issues/79) moved every credential off
  `security-crypto` and onto direct AES-256/GCM AndroidKeyStore envelopes. The dependency
  itself stays for now, behind a read-only legacy importer — see
  [#98](https://github.com/JMAN730/opendroid/issues/98), already open, for the removal
  precondition.
- **LiteRT-LM artifact integrity (partial)** — the Gemma 3n E2B/E4B and Qwen 2.5 entries
  in `OnDeviceModelRegistry.kt` now carry a pinned revision, a real byte size, and a
  publisher SHA-256 ([#65](https://github.com/JMAN730/opendroid/issues/65),
  [#82](https://github.com/JMAN730/opendroid/issues/82)). The Gemma 4 (non-3n) entries do
  not yet — see [Open items](#open-items).

---

## Open items

Concrete, actionable gaps found in this audit that were not already covered by an open
issue. Filed as children of [#55](https://github.com/JMAN730/opendroid/issues/55).

| # | Item | Why it's open |
|---|---|---|
| P0 | [#129](https://github.com/JMAN730/opendroid/issues/129) Gemma 4 (E2B/E4B) LiteRT artifacts still lack SHA-256 / pinned-revision metadata | `OnDeviceModelRegistry.kt`'s two `gemma-4-*-it-litert` entries carry only a bare `expectedSize` (one of them a suspiciously round 3,660,000,000) — no `managedArtifact`, no hash. Same risk the original P0 flagged, now half-resolved: the Gemma 3n pair and Qwen got the full treatment, these two didn't. |
| — | [#130](https://github.com/JMAN730/opendroid/issues/130) `docs/CONTRIBUTING.md` still says "JDK 17+" | PR #50 corrected `README.md` and `docs/development_guide.md` to "JDK 21 exactly" but missed this file, so the two docs now actively disagree with each other. |
| — | Device-QA sign-off for grant-all permissions (#15) | Tracked in [#75](https://github.com/JMAN730/opendroid/issues/75) — an emulator OOM aborted the one attempted automated run; needs a real device or a bigger box. |
| — | Manual smoke test on API 36 | Tracked in [#61](https://github.com/JMAN730/opendroid/issues/61) — the definition-of-done item from the original `targetSdk 36` task. |
| — | Drop `security-crypto` once a migrating release has shipped | Tracked in [#98](https://github.com/JMAN730/opendroid/issues/98) — a release-cadence call, not something resolvable from the repo alone. |

All six items above (including the four 2.0 scoping tickets below) are filed as children of [#55](https://github.com/JMAN730/opendroid/issues/55): [#129](https://github.com/JMAN730/opendroid/issues/129), [#130](https://github.com/JMAN730/opendroid/issues/130), [#131](https://github.com/JMAN730/opendroid/issues/131), [#132](https://github.com/JMAN730/opendroid/issues/132), [#133](https://github.com/JMAN730/opendroid/issues/133), [#134](https://github.com/JMAN730/opendroid/issues/134).

---

## 2.0 — Product depth

Directional, not scheduled — these are the areas where the README promises more than the
code currently delivers end-to-end. The project's own convention (per #55) is to leave
these as "Fog" until their questions are sharp enough to scope; this revision files a
scoping ticket for each one so they're tracked rather than only living in prose.

- **On-device inference as a first-class path.**
  ([#131](https://github.com/JMAN730/opendroid/issues/131)) The LiteRT-LM downloader,
  integrity verification, and local import exist. Missing: latency expectations, which
  actions are safe to route locally, and the fallback contract when a local model can't
  plan a step.
- **Procedural memory / user macros.** ([#132](https://github.com/JMAN730/opendroid/issues/132))
  `core/memory/ProceduralMemory.kt`, `data/models/Macro.kt`, `MacroViewModel`,
  `MacrosScreen`, and `MacroActions` already exist end-to-end for manually authored
  macros (name, trigger, ordered `PlanStep` list). What's missing: recording a macro from
  observed agent actions instead of hand-entering steps, and any conditional/branching
  step beyond a flat linear list.
- **Vision reliability.** ([#133](https://github.com/JMAN730/opendroid/issues/133))
  Screenshot → vision-LLM works (`VisionEngine.kt`); the accessibility-tree fallback has
  no stated accuracy bar and no test that holds one.
- **Action catalog breadth.** ([#134](https://github.com/JMAN730/opendroid/issues/134))
  `actions/` is the best on-ramp for outside contributions. Gated on
  `docs/agents/domain.md` covering the pattern — it currently doesn't (it's a generic
  domain-docs/CONTEXT.md meta-guide, not an `actions/` contribution guide) — so the "good
  first issue" sweep stays blocked on that first.

---

## Health metrics worth tracking

Current values so drift is visible later:

| Metric | 2026-07-30 | 2026-08-05 | Target |
|---|---|---|---|
| Lint baseline size | 29 (doc said 29 and 49 in two places) | 16 | 0 |
| Unit-test files | 26 | 39 | grows with each feature PR |
| Instrumentation tests | 0 | 5 files, CI matrix API 26/36 | ≥3 accessibility scenarios (met) |
| `targetSdk` gap to latest | 1 release behind | 0 | 0 |
| Placeholder/unverified artifact metadata in `main` | 2 (marked `TODO`) | 2 (Gemma 4 pair, no longer marked `TODO` but still unhashed) | 0 |
| Open issues (roadmap-relevant) | 1 | 5 open + #55 map | — |

---

## Sources

- [Target API level requirements for Google Play apps](https://support.google.com/googleplay/android-developer/answer/11926878)
- [Gradle compatibility matrix](https://docs.gradle.org/current/userguide/compatibility.html)
- [Issue #55 — Map: execute the OpenDroid roadmap](https://github.com/JMAN730/opendroid/issues/55)
