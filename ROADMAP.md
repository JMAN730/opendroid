# OpenDroid Roadmap

Living document. Reflects the state of `JMAN730/opendroid` as of **2026-07-30**, shipping
version **1.0.2** (`versionCode 3`).

Items are grouped by release, ordered by deadline pressure inside each group. Every item
names the file or ticket it touches so it can be picked up without re-deriving the context.

---

## Where we are

| Dimension | State |
|---|---|
| **App** | 171 Kotlin sources under `app/src/main/java/com/opendroid/ai` |
| **SDK** | `minSdk 26`, `compileSdk`/`targetSdk 35` |
| **Toolchain** | Gradle 9.6.1, AGP 9.3.1, Kotlin 2.4.0 (AGP built-in Kotlin), JDK 21 (pinned) |
| **Tests** | 26 JVM unit-test classes; **no instrumentation source set** (`app/src/` has only `main` and `test`) |
| **CI** | 3 jobs — unit tests + `assembleDebug`, `lintDebug`, unsigned `assembleRelease` (R8) |
| **Lint** | 29 findings frozen in `app/lint-baseline.xml`; policy (baseline / `error` / warning tiers) lives in the `lint {}` block of `app/build.gradle` |
| **Distribution** | GitHub Releases; marketing site auto-deploys to Pages from `website/` |
| **Open work** | Issue #15 (grant-all onboarding action), PR #50 (JDK 21 daemon pin) |

The last two months of work concentrated on **reliability and safety of the LLM layer** —
crash logging with credential redaction, provider retry policy, streaming error
propagation, auto-approval policy, and the CI coverage to hold those in place. That
foundation is solid. The gaps below are mostly at the edges: platform compliance, the
on-device model path, and test surface breadth.

---

## 1.1.0 — Compliance and correctness

The next release. Two of these are dated obligations, not preferences.

### P0 — `targetSdk 36` before 2026-08-31
Google Play requires new apps and updates to target **Android 16 (API 36)** from
2026-08-31; an extension can be requested through 2026-11-01. OpenDroid is on 35.
Even distributing outside Play, staying a release behind costs behavior parity on new
devices.

Touches `app/build.gradle` (`compileSdk`, `targetSdk`), and needs a pass over the
Android 16 behavior changes that bite this app specifically:
- foreground service types (the accessibility/agent service in `core/service`)
- notification and full-screen-intent restrictions
- edge-to-edge enforcement across the Compose screens
- predictive back

**Definition of done:** builds at 36, CI green, manual smoke on an API 36 emulator across
onboarding → permission grant → one full agent task.

### P0 — Real artifact sizes in the on-device model registry
`core/llm/OnDeviceModelRegistry.kt:132` and `:146` carry placeholder
`expectedSize` values (2 GB / 4 GB, marked `TODO`). Download integrity checks compare
against these, so any user on the LiteRT-LM path is verifying against a made-up number.
Replace with the published artifact sizes, or drop the size check and rely solely on the
SHA-256 verification that already exists.

### P1 — Close issue #15 (grant-all permissions onboarding)
Design is settled (ticket #22 prototyped the panel states, #23 defined the JVM test
surface). Implementation and tests remain.

### P1 — Merge PR #50, then document the JDK boundary in CONTRIBUTING
The daemon pin fixes the symptom; contributors still need to know why 21 and not "21+".

---

## 1.2.0 — Test surface and toolchain

### Instrumentation tests for the accessibility path
The riskiest code in the project — `accessibility/` and `core/service/` — has no
device-level coverage at all, because there is no `androidTest` source set. The unit tests
cover parsers, mappers, policies, and catalogs; none of them exercise the actual
accessibility node traversal or gesture dispatch.

Start with three scenarios rather than a framework: service binds and reports ready; a
known screen scrapes to the expected node tree; one gesture dispatches and is observed.
Add a CI job on an emulator matrix (API 26 as the floor, API 36 as the ceiling).

### Burn down the lint baseline
49 frozen findings. The distribution is worth knowing before deciding what to fix:

| Count | Issues |
|---|---|
| 6 each | `Recycle`, `IconLocation`, `IconLauncherShape` |
| 5 each | `UnusedResources`, `PermissionImpliesUnsupportedChromeOsHardware`, `ObsoleteSdkInt`, `InlinedApi` |
| 1–2 each | `MonochromeLauncherIcon`, `ClickableViewAccessibility`, `SdCardPath`, `NonObservableLocale`, `ModifierParameter` |

`Recycle` (leaked `TypedArray`/`Cursor`) is the only cluster with a runtime cost — take
that one first. `ObsoleteSdkInt` and `InlinedApi` will shift under the `targetSdk 36` work
anyway, so sequence them after it. The icon and resource findings are cosmetic; batch them.

Rule already in `app/build.gradle`: shrink the baseline, never regenerate it.

### Dependency drift
The Compose BOM is current (`2026.06.01`) but several direct dependencies are years behind
it and were likely never revisited after the initial scaffold:

- `androidx.core:core-ktx:1.12.0`, `navigation-compose:2.7.7`, `activity-compose:1.9.3`,
  `work-runtime-ktx:2.10.5`
- `retrofit:2.9.0` (2020) and `okhttp:4.12.0` — the networking layer under every provider
- **`androidx.security:security-crypto:1.1.0-alpha06`** — an alpha, and it is what
  encrypts stored API keys. This one deserves its own decision: track the stable line, or
  move key storage to a directly-managed Keystore path.

### Gradle 9 / AGP 9 migration
Unblocks building on JDK 24+ and gets off a toolchain that will stop receiving AGP fixes.
Not urgent, and genuinely breaking: removed Gradle 8 APIs (`rootProject.buildDir` in
`build.gradle:19`), deprecated `kotlinOptions`/`packagingOptions` blocks in
`app/build.gradle`, plus AGP 9's own changes. Own PR, CI-validated, no other work riding
along.

---

## 2.0 — Product depth

Directional, not scheduled. These are the areas where the README promises more than the
code currently delivers end-to-end.

- **On-device inference as a first-class path.** The LiteRT-LM downloader, integrity
  verification, and local import exist. What is missing is the story after the model is
  READY: latency expectations, which actions are safe to route locally, and the fallback
  contract when a local model cannot plan a step.
- **Procedural memory / user macros.** The memory tiers are modeled in `core/memory`;
  user-defined macro workflows are the tier with the least surface in the UI.
- **Vision reliability.** Screenshot → vision-LLM works; the accessibility-tree fallback
  needs a stated accuracy bar and a test that holds it.
- **Action catalog breadth.** `actions/` is where contributors can land work without
  touching the agent core — the best on-ramp for outside contributions. Worth a
  `good first issue` sweep once `docs/agents/domain.md` covers the pattern.

---

## Health metrics worth tracking

Current values so drift is visible later:

| Metric | Today | Target |
|---|---|---|
| Lint baseline size | 29 | 0 |
| Unit-test classes | 26 | grows with each feature PR |
| Instrumentation tests | 0 | ≥3 accessibility scenarios |
| `targetSdk` gap to latest | 1 release behind | 0 |
| Placeholder `TODO`s in `main` | 2 | 0 |
| Open issues | 1 | — |

---

## Sources

- [Target API level requirements for Google Play apps](https://support.google.com/googleplay/android-developer/answer/11926878)
- [Gradle compatibility matrix](https://docs.gradle.org/current/userguide/compatibility.html)
