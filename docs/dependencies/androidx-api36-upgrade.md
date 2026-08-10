# AndroidX platform dependency upgrade (API 36)

Record of the AndroidX batch from issue #71, planned by the research ticket #69.
Baseline at the time of the upgrade: `compileSdk`/`targetSdk` 36, `minSdk` 26,
Kotlin 2.4.0, AGP 8.13.2, Gradle 8.14.5.

## Declared changes

| Dependency | Before | After | Note |
|---|---|---|---|
| `androidx.core:core-ktx` | 1.12.0 | 1.18.0 | Coordinate swap to `androidx.core:core:1.19.0` deferred, see below |
| `androidx.activity:activity-compose` | 1.9.3 | 1.13.0 | Reinvokes `enableEdgeToEdge` on configuration changes; carries the 1.12.4 Photo/Video picker URI-security fix |
| `androidx.navigation:navigation-compose` | 2.7.7 | 2.9.8 | Predictive-back `NavHost` race/NPE fix |
| `androidx.work:work-runtime-ktx` | 2.10.5 | 2.11.2 | Current stable; AndroidX minSdk floor 23, below the app's 26 |
| `androidx.navigation:navigation-testing` | – | 2.9.8 (test only) | Drives the real graph in JVM back-stack tests |

### Deferred: `androidx.core:core:1.19.0`

The research ticket recommended replacing `core-ktx` with `androidx.core:core:1.19.0`,
where the Kotlin extensions were merged into `core` itself. That version cannot be used
on this baseline — the build fails with:

```
Dependency 'androidx.core:core:1.19.0' requires libraries and applications that
depend on it to compile against version 37 or later of the Android APIs.
:app is currently compiled against android-36.
...
Dependency 'androidx.core:core:1.19.0' requires Android Gradle plugin 9.1.0 or higher.
```

Issue #71 explicitly excludes AGP/Gradle migration work (tracked by #73 and #74), so Core
is pinned at 1.18.0 — the newest release this AGP 8.13.2 / compileSdk 36 build accepts —
and the coordinate swap moves to the AGP 9 migration.

## Resolved graph

`./gradlew :app:dependencies --configuration debugRuntimeClasspath` (and the release
counterpart) before and after the change, compared per module:

| Module | Before | After |
|---|---|---|
| `androidx.activity:activity` | 1.9.3 | 1.13.0 |
| `androidx.activity:activity-compose` | 1.9.3 | 1.13.0 |
| `androidx.activity:activity-ktx` | 1.9.3 | 1.13.0 |
| `androidx.core:core` | 1.16.0 | 1.18.0 |
| `androidx.core:core-ktx` | 1.16.0 | 1.18.0 |
| `androidx.navigation:navigation-common` | 2.7.7 | 2.9.8 |
| `androidx.navigation:navigation-compose` | 2.7.7 | 2.9.8 |
| `androidx.navigation:navigation-runtime` | 2.7.7 | 2.9.8 |
| `androidx.navigation:navigation-*-ktx` | 2.7.7 | removed (merged into the base artifacts) |
| `androidx.navigation:navigation-*-android` | – | 2.9.8 (new KMP target artifacts) |
| `androidx.navigationevent:navigationevent*` | – | 1.0.0 (new, pulled by Navigation 2.9) |
| `androidx.work:work-runtime` | 2.10.5 | 2.11.2 |
| `androidx.work:work-runtime-ktx` | 2.10.5 | 2.11.2 |

Every other module in both the debug and release runtime classpaths resolved to the same
version as before.

Verification of the resolved graph:

- **No downgrades.** No module resolves lower than it did before the change, and no module
  resolves below any version requested in the graph.
- **No dynamic versions.** No `+`, `latest.*`, or range coordinates are declared or resolved.
- **Repositories.** `settings.gradle` declares `google()`, `mavenCentral()` and `jitpack.io`
  with `FAIL_ON_PROJECT_REPOS`. Every artifact in this batch is an official AndroidX release
  served by `google()`; no snapshot or pre-release coordinate is declared or resolved.
- Core already resolved transitively to 1.16.0 before this change even though 1.12.0 was
  declared, so the declared bump mostly makes the real version explicit.

## Verification run

| Check | Command | Result |
|---|---|---|
| Unit tests | `./gradlew testDebugUnitTest` | pass |
| Debug build | `./gradlew assembleDebug` | pass |
| Lint (baseline enforced) | `./gradlew lintDebug` | pass, no new findings |
| Unsigned release (R8) | `./gradlew assembleRelease -PallowUnsignedRelease=true` | pass |
| API 36 smoke test | manual | outstanding — tracked by #61 |

## Regression tests added

- `app/src/test/.../ui/OpenDroidNavigationBackStackTest.kt` — entry-flow `popUpTo` rules,
  secondary-destination stacking, and saved-state restoration against the real Navigation
  runtime (`TestNavHostController`).
- `app/src/test/.../ui/theme/OpenDroidEdgeToEdgeTest.kt` — system bars keep the in-app theme
  when Activity 1.13 reapplies `enableEdgeToEdge` on a configuration change, an in-app theme
  switch repaints them, and both bars stay transparent on API 29+.
- `app/src/test/.../core/llm/ModelDownloadSchedulingTest.kt` — download work carries only the
  unmetered-network constraint and never runs as expedited (quota-consuming) work.

## Security notes

No permission check, foreground-service constraint, endpoint validation, or redaction rule
was touched. The Retrofit/OkHttp stack (#72) and the Gradle/AGP 9 migration (#73, #74) are
deliberately out of scope; the retired encrypted-preferences dependency is removed separately by #98.
