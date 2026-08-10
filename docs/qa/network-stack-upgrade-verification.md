# Network stack upgrade verification (Retrofit 3.0.0 / OkHttp 5.4.0)

Record for issue #72. Everything below was produced from this branch on
`compileSdk 36`, AGP 8.13.2, Kotlin 2.4.0, JDK 21.

## What changed

| Coordinate | Before | After |
| --- | --- | --- |
| `com.squareup.okhttp3:okhttp-bom` | *(absent)* | `5.4.0` |
| `com.squareup.okhttp3:okhttp` | `4.12.0` (pinned) | BOM-managed `5.4.0` |
| `com.squareup.okhttp3:logging-interceptor` | `4.12.0` (pinned) | BOM-managed `5.4.0` |
| `com.squareup.retrofit2:retrofit` | `2.9.0` | `3.0.0` |
| `com.squareup.retrofit2:converter-gson` | `2.9.0` | `3.0.0` |
| `com.squareup.okhttp3:mockwebserver3` (test) | *(absent)* | BOM-managed `5.4.0` |
| `com.squareup.okhttp3:okhttp-tls` (test) | *(absent)* | BOM-managed `5.4.0` |

Individual OkHttp versions are gone: the BOM is the single source of truth, so the
runtime, the logging interceptor and the test artifacts cannot drift apart again.

## Resolved dependency graph

`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`, network subset:

```
+--- com.squareup.okhttp3:okhttp-bom:5.4.0
|    +--- com.squareup.okhttp3:logging-interceptor:5.4.0 (c)
|    +--- com.squareup.okhttp3:okhttp:5.4.0 (c)
|    \--- com.squareup.okhttp3:okhttp-android:5.4.0 (c)
+--- com.squareup.okhttp3:okhttp -> 5.4.0
|    \--- com.squareup.okhttp3:okhttp-android:5.4.0
+--- com.squareup.okhttp3:logging-interceptor -> 5.4.0
+--- com.squareup.retrofit2:retrofit:3.0.0
|    +--- com.squareup.okhttp3:okhttp:4.12.0 -> 5.4.0
+--- com.squareup.retrofit2:converter-gson:3.0.0
     +--- com.squareup.retrofit2:retrofit:3.0.0
     +--- com.google.code.gson:gson:2.13.2
```

Every remaining OkHttp 4.12.0 request is a transitive one and is lifted to 5.4.0 by
the BOM (`./gradlew :app:dependencyInsight --dependency com.squareup.okio:okio-jvm`):

```
com.squareup.okio:okio-jvm:3.17.0
     \--- com.squareup.okhttp3:okhttp-android:5.4.0
          \--- com.squareup.okhttp3:okhttp:5.4.0
               +--- releaseRuntimeClasspath (requested com.squareup.okhttp3:okhttp)
               +--- io.coil-kt:coil-base:2.5.0 (requested com.squareup.okhttp3:okhttp:4.12.0)
               +--- com.squareup.retrofit2:retrofit:3.0.0 (requested com.squareup.okhttp3:okhttp:4.12.0)
```

Coil 2.5.0 and Retrofit 3.0.0 were both compiled against OkHttp 4.12.0 and run on
5.4.0 here. OkHttp 5 keeps binary compatibility for the API surface both use (the
`Response.body` change is a Kotlin nullability change, not a JVM signature change),
and the minified release build below links cleanly with no missing classes.

## Vulnerability scan

OSV.dev batch query (`POST https://api.osv.dev/v1/querybatch`) over the resolved
network coordinates:

| Package | Version | Advisories |
| --- | --- | --- |
| `com.squareup.okhttp3:okhttp` | 5.4.0 | none |
| `com.squareup.okhttp3:okhttp-android` | 5.4.0 | none |
| `com.squareup.okhttp3:logging-interceptor` | 5.4.0 | none |
| `com.squareup.okhttp3:mockwebserver3` | 5.4.0 | none |
| `com.squareup.okhttp3:okhttp-tls` | 5.4.0 | none |
| `com.squareup.retrofit2:retrofit` | 3.0.0 | none |
| `com.squareup.retrofit2:converter-gson` | 3.0.0 | none |
| `com.squareup.okio:okio-jvm` | 3.17.0 | none |
| `com.google.code.gson:gson` | 2.13.2 | none |

```
{"results":[{},{},{},{},{},{},{},{},{}]}
```

The superseded versions (`okhttp 4.12.0`, `logging-interceptor 4.12.0`,
`retrofit 2.9.0`, `converter-gson 2.9.0`) also return no advisories, so this upgrade
does not close a known CVE. Its value is leaving versions that are out of support -
Retrofit 2.9.0 shipped in 2020 and its OkHttp 3.14 baseline was unsupported for
roughly four years - for the currently maintained line.

## API audit

Searched `app/src` for the OkHttp 5 removals and for internals:

- No `okhttp3.internal.*` import anywhere in the app or its tests.
- No use of `OkHttpClient.clone()`/`Cloneable`, `AsyncDns`, `ConnectionListener`,
  `AddressPolicy`, or `RequestBody.gzip` (the last moved to `Request.Builder`).
- No custom `Interceptor`, `EventListener`, `ConnectionSpec`, `sslSocketFactory` or
  `hostnameVerifier` in production code, so TLS and certificate validation stay on
  OkHttp's platform defaults exactly as before.
- Retrofit is a declared dependency with no `retrofit2` import in `app/src`; the
  bump keeps the coordinate current for future use and is otherwise inert.

The one source-level break is `Response.body` becoming non-null. Call sites in
`ProviderErrorDetail`, `ModelFetcher`, `ModelDownloadWorker`, `TextToSpeechEngine`
and the twelve providers were migrated off `?.`/`?:`. Where the elvis branch carried
a real intent ("empty response body from X") the guard was kept as an explicit
`isBlank()` check rather than dropped - under OkHttp 4 that branch was already
unreachable for network responses, so the check now does what its message claims.

## Tests

`app/src/test/java/com/opendroid/ai/core/net/OkHttpNetworkStackTest.kt` - shared
client behaviour against MockWebServer:

- same-origin redirect is followed and keeps `Authorization`
- cross-origin redirect is followed and drops `Authorization`
- redirect loop terminates with `ProtocolException: Too many follow-up requests`
- `Call.cancel()` aborts an in-flight throttled streaming body
- an untrusted server certificate fails with `SSLHandshakeException`
- a certificate signed by a trusted CA completes the handshake over `https`

`app/src/test/java/com/opendroid/ai/core/llm/providers/CustomOpenAIProviderNetworkTest.kt`
- one provider end to end over a real socket:

- 200 is parsed into `LLMResponse`; the request carries `Bearer <key>` to
  `/v1/chat/completions`
- 401 with a vendor error body becomes `LLMException(AuthInvalid, status=401)` whose
  message, detail and code contain neither the API key, the user prompt, the vendor
  message, nor the endpoint host
- a 40 kB error body exceeds the classification budget and yields no vendor detail

Test credentials are throwaway literals; no test logs a credential, and the
redaction assertions check for *absence* rather than printing the secret.

The MockWebServer instances bind IPv4 loopback explicitly. On a dual-stack host
`localhost` produces two routes and OkHttp's fallback to the second one masks the
handshake failure the TLS tests assert on; the TLS URLs are also pinned to the
literal `127.0.0.1` the certificates name, because the canonical name of the
loopback address is whatever the host's resolver returns.

## Commands run

```
./gradlew :app:testDebugUnitTest    # BUILD SUCCESSFUL
./gradlew :app:lintDebug            # BUILD SUCCESSFUL - 72 warnings, 1 hint, 0 new errors
./gradlew :app:assembleDebug        # BUILD SUCCESSFUL
./gradlew :app:assembleRelease -PallowUnsignedRelease=true   # BUILD SUCCESSFUL
```

The release build is minified (`minifyEnabled true`, R8 9.1.31) and unsigned; it
produced `app/build/outputs/apk/release/app-release-unsigned.apk` and reported no
missing classes for the new OkHttp/Retrofit artifacts. Lint reports no new errors
against `lint-baseline.xml`.
