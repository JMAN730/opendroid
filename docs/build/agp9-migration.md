# AGP 9 / Gradle 9 migration

Stages 4-6 of the migration plan resolved in
[issue #70](https://github.com/JMAN730/opendroid/issues/70), implemented for
[issue #74](https://github.com/JMAN730/opendroid/issues/74). The behavior-preserving
DSL and KSP preparation that had to land first is documented in
[agp9-preparation.md](agp9-preparation.md).

This is the toolchain change itself: Gradle wrapper, AGP, AGP's built-in Kotlin, and
the standalone R8 pin. It is one commit so it can be reverted as one commit.

## Versions

| Component | Before | After | Why this exact version |
| --- | --- | --- | --- |
| Gradle | 8.14.5 | 9.6.1 | current stable; AGP 9.3.1 requires >= 9.5.0 |
| AGP | 8.13.2 | 9.3.1 | newest stable AGP 9.x |
| Kotlin | 2.4.0 | 2.4.0 | unchanged; pinned on the buildscript classpath, see below |
| KSP | 2.3.10 | 2.3.10 | unchanged; newest KSP release |
| Dagger / Hilt | 2.58 | 2.60.1 | 2.58's Gradle plugin does not run under AGP 9 |
| Room | 2.8.4 | 2.8.4 | unchanged |
| JDK / jvmTarget | 21 | 21 | unchanged |
| `com.android.tools:r8` pin | 9.1.31 | *removed* | AGP 9.3.1's bundled R8 verified equivalent |
| `kotlin-metadata-jvm` pin | 2.4.0 | *removed* | Dagger 2.60.1 no longer needs it |

### Compatibility evidence

The minimum Gradle version is not a guess: `VersionCheckPlugin` inside
`com.android.tools.build:gradle:9.3.1` carries the single version string `9.5.0`
and the message "Minimum supported Gradle version is". Gradle 9.6.1 is the
current stable release (`https://services.gradle.org/versions/current`) and
satisfies it.

The wrapper is pinned by checksum, both of which were checked against
`services.gradle.org`:

- distribution `gradle-9.6.1-bin.zip` —
  `9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14`
- `gradle/wrapper/gradle-wrapper.jar` —
  `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`

`validateDistributionUrl=true` is unchanged, and the wrapper jar checksum is
*enforced*, not just recorded: `gradle/actions/setup-gradle@v4` defaults to
`validate-wrappers: true`, which fails any CI job whose wrapper jar is not a
published Gradle release. That is why no separate wrapper-validation step was
added to the workflow.

The wrapper task was run twice
(once from Gradle 8.14.5, once from 9.6.1) because the first run only rewrites
`gradle-wrapper.properties` and ships the *old* distribution's wrapper jar; the
second run is what makes the jar match the published checksum above.

Upgrading only the wrapper is not possible: Gradle 9.6.0 removed
`org.gradle.api.problems.internal.InternalProblems`, which AGP 8.13.2 uses, so
Gradle 9.6.1 with AGP 8.x fails at plugin application. Hence the atomic commit.

## Built-in Kotlin

`org.jetbrains.kotlin.android` is gone from `app/build.gradle`. AGP 9 refuses to
apply alongside it ("The 'org.jetbrains.kotlin.android' plugin is no longer
required for Kotlin support since AGP 9.0"), and no `android.builtInKotlin`
opt-in flag was needed — built-in Kotlin is the default path once the plugin is
absent.

**AGP does not bring its own Kotlin version.** It compiles with whichever Kotlin
Gradle plugin is on the buildscript classpath. AGP 9.3.1 depends on KGP 2.2.10,
so leaving that to resolve would have silently *downgraded* the project from
Kotlin 2.4.0. The explicit `classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0'`
in `build.gradle` is what prevents that; `./gradlew buildEnvironment` shows the
resolution:

```
+--- com.android.tools.build:gradle:9.3.1
|    +--- org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10 -> 2.4.0
```

The compose-compiler and serialization plugins stay at 2.4.0 to match. The
`kotlin { jvmToolchain(21); compilerOptions { … } }` block is unchanged: under
built-in Kotlin, AGP registers that same extension, so the compiler flags and the
Java 21 target carry over as written.

No `android.builtInKotlin=false` or `android.newDsl=false` opt-out is set. Both
are temporary flags that AGP 10 removes; neither is an acceptable end state.

## Dagger / Hilt 2.58 → 2.60.1

Hilt 2.58's Gradle plugin fails hard under AGP 9:

```
Failed to apply plugin 'dagger.hilt.android.plugin'.
> Could not find the Android Gradle Plugin (AGP) base extension.
```

It reads `BaseExtension`, which AGP 9 removed. 2.60.1 uses the current APIs.

The `annotationProcessor 'org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0'` pin
that the preparation commit carried is **removed**. It existed because Dagger
2.58's aggregating javac step resolved a `kotlin-metadata-jvm` that rejected
Kotlin 2.4.0 metadata ("maximum supported version is 2.3.0"). `:app:assembleDebug
--rerun-tasks` without the pin builds clean on 2.60.1, `hiltJavaCompileDebug`
included, so the workaround is gone rather than carried forward.

## R8 pin removal, with release-equivalence evidence

The root `classpath 'com.android.tools:r8:9.1.31'` override is removed; the
release build now uses the R8 bundled with AGP 9.3.1, which the release
`mapping.txt` header identifies as **R8 9.3.16**. Keeping the old pin would
therefore *downgrade* R8 below what this AGP ships, which is the opposite of what
the pin was for — it existed only because AGP 8.13.x bundled an R8 (8.13.19) too
old for Kotlin 2.4.0 metadata.

Removal was still not assumed safe — the minified release APK was built before and
after and compared:

| Measure | AGP 8.13.2 + R8 9.1.31 | AGP 9.3.1 bundled R8 |
| --- | --- | --- |
| Unsigned release APK | 57,081,563 bytes | 57,015,667 bytes |
| Classes in `mapping.txt` | 26,948 | 26,917 |
| `com.opendroid.*` classes | 1,155 | 1,154 |
| `Hilt_*` types | 6 | 6 |
| Room `*_Impl` types | 20 | 20 |
| `Dagger*` component types | 12 | 12 |
| kotlinx-serialization serializers | 56 | 56 |
| `com.google.mlkit.*` public API classes | 46 | 46 |
| `com.google.ai.edge.*` (LiteRT-LM) classes | 17 | 18 |
| `okhttp3` + `retrofit2` classes | 479 | 479 |

Every count that reflection depends on is identical, and LiteRT-LM retains one
class *more* than before. The `com.opendroid.*` and overall deltas are synthetic
lambda/anonymous classes (`Foo$0`, `Foo$3`) being renumbered, plus 66 library
classes that the newer R8 inlines away — Kotlin multifile facades
(`DBUtil__DBUtilKt`, `SavedStateReaderKt__SavedStateReaderKt`) and unreferenced
AndroidX services. **No named application class, and no public ML Kit or LiteRT
API class, is missing from the new mapping.** The only reflection-adjacent names
that moved are `com.google.android.gms.internal.mlkit_genai_prompt.zz*` — GMS's
own pre-obfuscated internals, where 28 names disappear and 16 appear because R8
merged them; the public `com.google.mlkit.*` entry points those internals sit
behind are unchanged.

This is static evidence from the mapping files. It is **not** a runtime smoke
test — see "Not done here" below. If a reflection regression does appear, restore
the pin by re-adding the `com.android.tools:r8` classpath line at a version
>= 9.3.16 (never back to 9.1.31, which is older than the bundled one) and file an
R8-specific issue; the comparison above is the procedure to repeat.

## Lint

The AGP 9 lint release changed the wording of
`PermissionImpliesUnsupportedChromeOsHardware` (`required="false"` →
`android:required="false"`), so the five baseline entries for it stopped matching
and the check failed the build. They were **fixed, not re-baselined**:
`app/src/main/AndroidManifest.xml` now declares

```xml
<uses-feature android:name="android.hardware.telephony" android:required="false"/>
<uses-feature android:name="android.hardware.camera" android:required="false"/>
```

and the five entries were deleted from `app/lint-baseline.xml`. The baseline was
not regenerated; it only shrank.

This does change Play Store device filtering, in the widening direction. Without
any `<uses-feature>` tag, Play *infers* a hard telephony/camera requirement from
`CALL_PHONE`, `SEND_SMS` and `CAMERA` and hides the app from devices lacking that
hardware; `required="false"` states explicitly that the app runs without it. The
alternative — re-baselining the five reworded findings — is ruled out by the
policy comment on the `lint` block in `app/build.gradle` ("never regenerate it to
absorb new findings").

## Preserved behavior

Re-verified after the change, not assumed:

- **Unit tests** — `:app:testDebugUnitTest` green, including the Room migration
  tests and `AnnotationProcessorOutputTest` (which fails if Room or Hilt
  processors silently stop generating).
- **Room schema export** — `git status app/schemas` clean; `6.json` and `7.json`
  are byte-identical under the new toolchain.
- **Lint** — `:app:lintDebug` green against the shrunk baseline.
- **Release-signing guard** — plain `:app:assembleRelease` still fails with
  "Release signing is not configured"; `-PallowUnsignedRelease` still builds the
  unsigned APK with the "must never be distributed" warning.
- **Configuration cache** — entry stored and reused across runs (it is enabled in
  `gradle.properties`, so every build and every CI job exercises it).
- **JDK 21** — `compileOptions`, `jvmToolchain(21)` and the CI `setup-java`
  version are unchanged.
- **CI** — `.github/workflows/android-ci.yml` needs no change. Same four gates,
  same wrapper-only `./gradlew` invocations, `permissions: contents: read`, no
  signing secrets, no new actions or repositories.

## Not done here

- **API 36 device smoke check.** Could not be run on the development machine: the
  emulator refuses to create the API 36 userdata partition (needs ~7.2 GB, the
  host had ~5.6 GB free). This is the one acceptance item without local evidence,
  and it is the item that would exercise the R8 change *at runtime* — the
  CONTRIBUTING scenarios (WhatsApp send, email, multi-step plan) are exactly the
  reflection-heavy paths a shrinker regression would break. Run
  `:app:installRelease` (or `installDebug` plus a minified release install) and
  those scenarios on an API 36 image before this ships in a release build.
- **`androidx.core:core:1.19.0`.** Its AGP 9.1+ requirement is now met, but it
  also needs `compileSdk 37`. An SDK bump is out of scope for this ticket, so
  `androidx.core:core-ktx:1.18.0` stays.

## Rollback

Revert this single commit. It restores the Gradle 8.14.5 wrapper and its
checksum, AGP 8.13.2, the `org.jetbrains.kotlin.android` plugin, Hilt 2.58, the
R8 and `kotlin-metadata-jvm` pins, and the five lint baseline entries together —
none of them work in isolation. No unsigned artifact built during the migration
was distributed.
