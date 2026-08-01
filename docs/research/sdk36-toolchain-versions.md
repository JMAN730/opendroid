# SDK 36 toolchain versions (research, issue #56)

Researched 2026-08-01 against primary sources only (developer.android.com, gradle.org,
kotlinlang.org, dl.google.com maven metadata, official Dagger/KSP release pages).

## Current state (verified in repo)

| Item | Current | Where |
|---|---|---|
| compileSdk / targetSdk | 35 / 35 | `app/build.gradle:13`, `app/build.gradle:18` |
| minSdk | 26 | `app/build.gradle:17` |
| AGP | 8.8.2 | `build.gradle:9` |
| Gradle wrapper | 8.10.2 | `gradle/wrapper/gradle-wrapper.properties:3` |
| Kotlin / KGP / serialization / compose-compiler plugin | 2.4.0 | `build.gradle:11,13,14` |
| R8 classpath pin | `com.android.tools:r8:9.1.31` | `build.gradle:8` |
| Hilt plugin / runtime | 2.58 | `build.gradle:12`, `app/build.gradle:182-183` |
| Room plugin / runtime | 2.8.4 | `build.gradle:10`, `app/build.gradle:167-169` |
| Annotation processing | kapt (Room + Hilt) | `app/build.gradle:4,169,183` |
| JDK | toolchain 21, source/target 21 | `app/build.gradle:97-110` |

## Recommended pins

| Item | Pin | Change? |
|---|---|---|
| AGP | **8.13.2** | bump from 8.8.2 |
| Gradle wrapper | **8.14.5** (min for AGP 8.13 is 8.13) | bump from 8.10.2 |
| Kotlin / KGP / compose compiler | **2.4.0** (keep; 2.4.10 optional patch) | no bump required |
| R8 classpath pin | **keep `9.1.31`** | keep |
| Hilt | **2.58** (do NOT bump — 2.59+ requires AGP 9) | keep |
| Room + room-gradle-plugin | **2.8.4** (latest stable) | keep |
| KSP | n/a (project uses kapt); latest KSP is 2.3.10 if migrating | none |
| JDK | floor stays **17**; repo's 21 toolchain unchanged | none |
| compileSdk / targetSdk | **36** | bump |

## Per-item rationale

### 1. AGP: 8.13.2, not 9.3.1

- The literal latest stable AGP is **9.3.1** (google maven metadata:
  <https://dl.google.com/android/maven2/com/android/tools/build/gradle/maven-metadata.xml>);
  AGP 9.3 supports up to API 37 and requires Gradle >= 9.5.0, JDK >= 17
  (<https://developer.android.com/build/releases/gradle-plugin>).
- However AGP 9.x is a breaking migration for this repo:
  - AGP 9.0 enables **built-in Kotlin** by default, and **kapt is not compatible
    with built-in Kotlin** — this repo uses kapt for Room and Hilt
    (`app/build.gradle:4`). Options are kapt->KSP migration or the
    `android.builtInKotlin=false` escape hatch, which is transitional
    (<https://developer.android.com/build/releases/agp-9-0-0-release-notes>).
  - The Kotlin Gradle plugin 2.4.0–2.4.10 officially supports **AGP 8.5.2 through
    9.1.0** only (<https://kotlinlang.org/docs/gradle-configure-project.html>).
    AGP 9.2/9.3 are outside KGP 2.4.x's tested range.
  - Hilt's Gradle plugin 2.59+ **requires AGP 9** ("if you use the Hilt Gradle
    Plugin, AGP 9 is now a requirement", <https://github.com/google/dagger/releases>),
    so an AGP 9 move also forces Hilt 2.59/2.60.x and its behavior changes.
- **AGP 8.13.2** is the latest stable in the 8.x line (google maven metadata, above)
  and supports a maximum API level of **36.1**, i.e. compileSdk 36 with no warnings
  (<https://developer.android.com/build/releases/agp-8-13-0-release-notes>).
  It stays inside KGP 2.4.0's supported AGP range (8.5.2–9.1.0) and keeps kapt,
  Hilt 2.58, and the Groovy `buildscript` classpath working unchanged.
- Verdict: **pin AGP 8.13.2** for the SDK 36 bump; treat the AGP 9 migration
  (kapt->KSP, built-in Kotlin, Hilt >= 2.59, Gradle 9 wrapper) as a separate ticket.

### 2. Gradle wrapper: 8.14.5

- AGP 8.13 requires **Gradle >= 8.13** (minimum = default,
  <https://developer.android.com/build/releases/agp-8-13-0-release-notes>).
- Recommended pin: **8.14.5**, the latest 8.x release (2026-05-07,
  <https://gradle.org/releases/>). It satisfies AGP 8.13's floor and is inside
  KGP 2.4.x's supported Gradle range of 7.6.3–9.5.0
  (<https://kotlinlang.org/docs/gradle-configure-project.html>).
- Gradle 9.x is not needed until the AGP 9 migration (AGP 9.0 needs Gradle >= 9.1.0,
  AGP 9.3 needs >= 9.5.0).

### 3. Kotlin 2.4.0: no bump needed

- KGP 2.4.0 supports Gradle 7.6.3–9.5.0 and AGP 8.5.2–9.1.0
  (<https://kotlinlang.org/docs/gradle-configure-project.html>), which covers
  AGP 8.13.2 + Gradle 8.14.5. **No Kotlin bump is required.**
- Optional: latest 2.4.x patch is **2.4.10** (2026-07-14,
  <https://github.com/JetBrains/kotlin/releases>) with identical compatibility
  ranges; a low-risk patch bump if desired (also bump `kotlin-metadata-jvm`).
- Compose compiler: since Kotlin 2.0 it ships with Kotlin via
  `org.jetbrains.kotlin:compose-compiler-gradle-plugin` at the same version
  (already 2.4.0 at `build.gradle:14`) — no separate version to manage.

### 4. R8 pin `com.android.tools:r8:9.1.31`: KEEP

- Why the pin exists: commit `2fd0a0f` (2026-07-12, "build: override R8 compiler
  version to 9.1.31 to support Kotlin 2.4.0") added it because AGP 8.8.2's bundled
  R8 (8.8.x) cannot process Kotlin 2.4.0 metadata.
- Does AGP 8.13.2's bundled R8 cover it? **No.** The AGP 8.13.2 patch notes state
  it brings "Kotlin 2.3 support via R8 8.13.19"
  (<https://developer.android.com/build/releases/agp-8-13-0-release-notes>) —
  i.e. its bundled R8 tops out at Kotlin 2.3 metadata, and this project is on
  Kotlin 2.4.0 with `minifyEnabled true` release builds.
- Bump instead? No newer standalone R8 exists: **9.1.31 is the highest version
  published** to google maven
  (<https://dl.google.com/android/maven2/com/android/tools/r8/maven-metadata.xml>);
  no 9.2.x/9.3.x standalone artifacts have been released.
- Verdict: **keep the pin unchanged at 9.1.31**. It becomes droppable only after
  migrating to AGP >= 9.1 (whose bundled R8 is from the 9.1 train — the same train
  as the pin).

### 5. Forced plugin classpath bumps: none

- **Hilt 2.58: keep.** 2.59+ hard-requires AGP 9 (<https://github.com/google/dagger/releases>);
  bumping Hilt on AGP 8.13.2 would break the build. (Note for the future AGP 9
  ticket: Hilt 2.60 also raised Hilt's minSdk to 23 — fine here, minSdk 26.)
- **Room 2.8.4: keep.** It is the latest stable of both runtime and
  `room-gradle-plugin`
  (<https://dl.google.com/android/maven2/androidx/room/room-gradle-plugin/maven-metadata.xml>).
- **KSP: not applicable** — the project uses kapt. When kapt->KSP happens
  (AGP 9 prerequisite), latest KSP is **2.3.10**, which explicitly fixed
  compatibility with Kotlin 2.4.0 module names
  (<https://github.com/google/ksp/releases>).
- **kotlinx-serialization / compose-compiler plugins:** versioned with KGP;
  unchanged at 2.4.0.
- **Compose BOM 2026.06.01, ML Kit, LiteRT-LM:** no compileSdk-36-forced bumps found.

### 6. JDK: no change

- AGP 8.13 and AGP 9.x both require **JDK 17 minimum**
  (<https://developer.android.com/build/releases/agp-8-13-0-release-notes>,
  <https://developer.android.com/build/releases/gradle-plugin>).
- The repo already builds with a Java **21** toolchain (`app/build.gradle:97-110`),
  which Gradle 8.14.5 fully supports (<https://gradle.org/releases/>). Nothing to do.

## Exact edits

1. `build.gradle:9` — `classpath 'com.android.tools.build:gradle:8.8.2'` ->
   `classpath 'com.android.tools.build:gradle:8.13.2'`
2. `build.gradle:8` — leave `classpath 'com.android.tools:r8:9.1.31'` as-is
   (add a comment: needed until AGP >= 9.1; bundled R8 8.13.19 only covers Kotlin 2.3).
3. `gradle/wrapper/gradle-wrapper.properties:3` — `distributionUrl=...gradle-8.14.5-bin.zip`
   `gradle/wrapper/gradle-wrapper.properties:4` — `distributionSha256Sum=6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854`
   (checksum from <https://downloads.gradle.org/distributions/gradle-8.14.5-bin.zip.sha256>;
   or regenerate with `./gradlew wrapper --gradle-version 8.14.5 --gradle-distribution-sha256-sum 6f74b6...`)
4. `app/build.gradle:13` — `compileSdk 35` -> `compileSdk 36`
5. `app/build.gradle:18` — `targetSdk 35` -> `targetSdk 36` (audit API-36 behavior
   changes — e.g. edge-to-edge enforcement — before shipping;
   <https://developer.android.com/about/versions/16/behavior-changes-16>)
6. `build.gradle:10-14`, `app/build.gradle` dependencies — **no changes**
   (Kotlin 2.4.0, Hilt 2.58, Room 2.8.4 all stay).
7. Optional: Kotlin 2.4.0 -> 2.4.10 at `build.gradle:11,13,14` and
   `app/build.gradle:170` (`kotlin-metadata-jvm`).

## Follow-up ticket suggested (out of scope here)

AGP 9.x migration: kapt -> KSP (Room + Hilt), adopt built-in Kotlin, Hilt -> 2.60.x,
Gradle wrapper -> 9.5+, then **drop** the `com.android.tools:r8` classpath pin.
