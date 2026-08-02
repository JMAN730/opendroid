# AGP 9 preparation: modern DSL and KSP

Behavior-preserving stage 2 and 3 of the migration plan resolved in
[issue #70](https://github.com/JMAN730/opendroid/issues/70), implemented for
[issue #73](https://github.com/JMAN730/opendroid/issues/73).

**Gradle, AGP, and the JDK are deliberately unchanged.** This change only removes
the DSL and annotation-processing blockers so that the toolchain bump (stages 4-6)
is a separate, revertible commit.

## Versions

| Component | Before | After | Note |
| --- | --- | --- | --- |
| Gradle | 8.14.5 | 8.14.5 | unchanged, wrapper checksum untouched |
| AGP | 8.13.2 | 8.13.2 | unchanged |
| Kotlin | 2.4.0 | 2.4.0 | unchanged |
| JDK / jvmTarget | 21 | 21 | unchanged |
| KSP | — | 2.3.10 | new; replaces `kotlin-kapt` |
| Room | 2.8.4 | 2.8.4 | compiler moved `kapt` → `ksp` |
| Hilt / Dagger | 2.58 | 2.58 | compiler moved `kapt` → `ksp` |
| `kotlin-metadata-jvm` | 2.4.0 (`kapt`) | 2.4.0 (`annotationProcessor`) | see below |
| R8 override | 9.1.31 | 9.1.31 | unchanged; removal is stage 5 |

## What changed

### `kotlinOptions` → `kotlin { compilerOptions { … } }`

AGP 9 removes `android { kotlinOptions { … } }`. The Kotlin Gradle plugin's own
`kotlin { compilerOptions { … } }` block is the supported replacement and is
already honoured by AGP 8.13.2, so the same flags apply before and after.

`android { kotlin { jvmToolchain(21) } }` was only resolving through Groovy's
dynamic dispatch to the project-level `kotlin` extension; it now sits in that
extension explicitly.

Evidence: the compiled class
`app/build/tmp/kotlin-classes/debug/com/opendroid/ai/OpenDroidApp.class` carries
bytecode major version **65** (Java 21), matching the pre-change target.

### `packagingOptions` → `packaging`

Straight rename; the `resources { excludes … }` body is unchanged.

### Groovy space-assignment → `=`

Gradle deprecated `propName value` in favour of `propName = value` (removal in
Gradle 10). Seven sites were flagged by `--warning-mode all` and converted:
`settings.gradle` `url`, and in `app/build.gradle` `namespace`,
`useSupportLibrary`, `shrinkResources`, `compose`, `abortOnError`,
`checkReleaseBuilds`.

Evidence: `./gradlew :app:help --warning-mode all --no-configuration-cache`
reports **no** deprecation warnings after the change; it reported seven before.

### `rootProject.buildDir` → `layout.buildDirectory`

`Project.buildDir` is removed in Gradle 9. The root `clean` task now uses
`rootProject.layout.buildDirectory` and is registered lazily with
`tasks.register`.

### kapt → KSP

`kotlin-kapt` is gone. `androidx.room:room-compiler` and
`com.google.dagger:hilt-compiler` moved to the `ksp` configuration. AGP 9's
built-in Kotlin cannot run `kotlin-kapt` at all, so this had to happen before the
toolchain bump.

## Compatibility evidence for KSP 2.3.10 on Kotlin 2.4.0

As of 2026-08-01 the newest KSP release on Maven Central is **2.3.10**; there is
no 2.4.x line yet, so KSP and the Kotlin compiler are not on matching version
numbers. That combination is not rejected — the KSP plugin does not require a
lockstep match with the Kotlin Gradle plugin — but it was verified rather than
assumed.

Evidence from `:app:kspDebugKotlin --rerun --info`, all processors loading and
running under Kotlin 2.4.0:

```
i: [ksp] loaded provider(s): [androidx.room.RoomKspProcessor$Provider,
dagger.hilt.processor.internal.root.KspRootProcessor$Provider,
dagger.internal.codegen.KspComponentProcessor$Provider, …]
```

`:app:kspDebugKotlin` and `:app:kspReleaseKotlin` both complete clean and every
generated type compiles. If a future Kotlin bump does break this, the symptom is
a hard failure in the `ksp*Kotlin` task, not silent miscompilation.

### The `kotlin-metadata-jvm` pin has to stay, on a different configuration

Hilt's aggregating step (`hiltJavaCompileDebug`) still runs its processor under
`javac`, not KSP, and Dagger 2.58 resolves a `kotlin-metadata-jvm` that rejects
Kotlin 2.4.0 metadata:

```
error: [Hilt] Provided Metadata instance has version 2.4.0, while maximum
supported version is 2.3.0. To support newer versions, update the
kotlin-metadata-jvm library.
```

The project already carried `kotlin-metadata-jvm:2.4.0` for exactly this reason,
on the `kapt` configuration. It moves to `annotationProcessor`, which is the
configuration feeding that javac step. Room needs no such pin under KSP.

## Preserved behavior

Each of these was re-verified after the change, not assumed:

- **Room schema export** — `app/schemas/…/6.json` and `7.json` are byte-identical
  after the KSP switch (`git status app/schemas` is clean). KSP and kapt produce
  the same schema.
- **Room migration tests** — `OpenDroidDatabaseMigrationTest` passes under
  Robolectric against those exported schemas.
- **Hilt bindings** — the full generated graph is present: application injector,
  `DaggerOpenDroidApp_HiltComponents_SingletonC`, the four Android entry points,
  and all nine `@HiltViewModel` module/factory pairs.
- **Lint baseline** — `:app:lintDebug` green against the unchanged
  `app/lint-baseline.xml`.
- **Release-signing guard** — a plain `:app:assembleRelease` still fails with
  "Release signing is not configured", and `-PallowUnsignedRelease` still builds
  an unsigned APK with the "must never be distributed" warning.
- **Configuration cache** — entry stored and then reused across runs.
- **JDK 21** — toolchain and bytecode target unchanged (see above).
- **CI** — `.github/workflows/android-ci.yml` needs no change: the same four
  gates run against the same commands, with `contents: read` and no signing
  secrets.

## Regression test

`app/src/test/java/com/opendroid/ai/build/AnnotationProcessorOutputTest.kt` loads
the generated Room and Hilt types by name. A processor that silently stops running
produces no generated class and the build still succeeds until something touches
the missing type at runtime — this test turns that into a unit-test failure. It
also asserts a schema JSON exists for the version declared on `@Database`.

## Still to do (out of scope here)

Stages 4-6 of the plan: Gradle 9.5 / AGP 9.3 wrapper and plugin bump, AGP built-in
Kotlin, and removal of the `com.android.tools:r8:9.1.31` override once the embedded
R8 passes release-equivalence checks.
