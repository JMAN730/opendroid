# Test Plan — Qwen 2.5 0.5B (LiteRT-LM) on-device inference

**Target:** `qwen-2.5-0.5b-it-litert` running through `LiteRTLMProvider` / `HybridOnDeviceProvider`.
**Build under test:** `app-debug.apk` from `main` (branch `JMAN730/update-pull-build-launch`).
**Author of plan:** QA pass following the 2026-07-29 emulator run.

---

## 1. Why this plan exists

The 2026-07-29 run got as far as: model installed, provider switched to On-Device AI, message sent — then the app process was killed by `mem-pressure-event` before any token was produced. Zero inference completed. Everything below section 4 is therefore **untested**, not "passing".

The plan separates *environment blockers* (section 3) from *product behaviour* (sections 5+) so a future run does not repeat that conflation.

---

## 2. Scope

**In scope**

- Model acquisition: in-app download, resume, cancel, checksum, local import
- Model storage layout and status reporting (`ModelStoragePaths`, `ModelRepository`)
- Inference: single turn, multi turn, streaming, cancellation
- Context-window behaviour at `contextWindow = 1280` (`PromptBudget`)
- Failure paths: OOM, corrupt file, missing file, backend unsupported
- Model switching between LiteRT variants and to/from cloud providers

**Out of scope**

- AI Core (Gemma 4 / 3n) — reports "Not supported on this device" on emulator; needs a Play-Services device
- Cloud providers (Gemini, OpenAI, …) except where used as a fallback control
- Agent/autonomous tooling (Plan, Macros) beyond confirming they receive model output

---

## 3. Environment gate — run this first

Inference will not complete unless these hold. **Do not log product bugs until section 3 is green.**

| ID | Check | Requirement | How |
|----|-------|-------------|-----|
| E1 | Guest RAM | ≥ 4 GB, ≥ 2.5 GB available at idle | `adb shell cat /proc/meminfo` |
| E2 | Host RAM headroom | ≥ 2 GB free with emulator running | `free -m` |
| E3 | ABI | `liblitertlm_jni.so` present for the device ABI | confirmed for `x86_64` and `arm64-v8a` in `litertlm-android:0.14.0` |
| E4 | Disk | ≥ 1.5 GB free on the model partition | Settings → Storage Cleanup |
| E5 | Model integrity | `sha256 = e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2`, size `546660344` | `sha256sum` on host before push |

**Known-good config:** physical arm64 device with ≥ 6 GB RAM, or emulator launched with `-memory 4096` on a host with ≥ 8 GB. The 6.7 GB host used on 2026-07-29 cannot satisfy E1 and E2 simultaneously.

**Environment defect already found (E6):** `adb push` into the app's external files dir leaves files owned by `shell`; the app then reports the model as **Not Downloaded** and Storage Cleanup shows 0 B. Workaround: `adb root` then `chown -R <app-uid>` on the ext4 path `/data/media/0/Android/data/com.opendroid.aiagent/files/models`. Decide whether this warrants a product fix (a readability check with a clear error) or stays a test-harness note.

---

## 4. Setup procedure

1. Build: `./gradlew assembleDebug` with `JAVA_HOME` = JDK 21, `local.properties` containing `sdk.dir`. On memory-constrained hosts add `-Dkotlin.compiler.execution.strategy=in-process`.
2. Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. Complete onboarding (name + birthday, both required), grant permissions.
4. Install the model by **one** of:
   - **Path A (preferred, exercises production code):** Settings → LiteRT-LM → Qwen 2.5 → Download
   - **Path B (fast, harness only):** host download + `adb push` + the E6 chown workaround
5. Settings → Active Brain Provider → **On-Device AI**; Active LLM Model → **Qwen 2.5 0.5B-it (LiteRT)**.
6. Confirm ON-DEVICE AI STATUS reads `Active: Qwen 2.5 0.5B-it (LiteRT)` / `Backend: LiteRT-LM`.

---

## 5. Model acquisition

| ID | Case | Steps | Expected |
|----|------|-------|----------|
| A1 | Fresh download | Path A on a device with no model present | Progress advances monotonically; ends `Downloaded`; Storage Cleanup shows ~521 MB |
| A2 | Download survives backgrounding | Start download, home out 60 s, return | Progress continued (WorkManager); no restart from 0 |
| A3 | Pause / resume | Pause mid-download, wait 10 s, resume | Resumes from the existing byte offset, not from 0 |
| A4 | Cancel | Cancel mid-download | Status → `Not Downloaded`; model dir and `litert_models/<id>.litertlm` ref file removed; temp file in cacheDir removed |
| A5 | Network loss | Enable airplane mode mid-download | Clear error surfaced, no silent stall; resumable afterwards |
| A6 | Checksum mismatch | Truncate/corrupt the file, restart app | App must not report `Downloaded` for a bad file. **Note:** current `initModelsInDatabase` only gates on `MIN_VERIFIED_BYTES` (10 MB) plus manifest presence — a 400 MB truncated file may pass. Expect this to fail; file as a real defect if so |
| A7 | No-auth repo | Confirm Qwen downloads with no Hugging Face token set | Succeeds; HF token remains `Token Required` and does not block |
| A8 | Local import | Settings → import a `.task` from device storage | `Engine.initialize()` compat check runs; manifest.json and ref file written; a non-LiteRT file is rejected with a readable message and deleted |

## 6. Inference — core

Send from Chat with the model active. Record wall-clock to first token and to completion for each.

| ID | Case | Input | Expected |
|----|------|-------|----------|
| B1 | Smoke | `Hello who are you in one sentence` | Non-empty coherent reply; no crash; app process survives |
| B2 | Cold vs warm | Repeat B1 immediately | Second call markedly faster (engine cached, not re-initialised) |
| B3 | Multi-turn | B1, then `What did I just ask you?` | Reply references the prior turn — confirms history is fed to the model |
| B4 | Streaming | Any prompt | Tokens render incrementally, not one final block |
| B5 | Cancellation | Send a long prompt, cancel mid-stream | Generation stops promptly; UI returns to idle; next message still works |
| B6 | Deterministic-ish | Same prompt twice at low temperature | Output stable enough to be recognisably the same answer |
| B7 | Empty / whitespace input | Send `   ` | Send is rejected or no-ops; no crash, no empty bubble |
| B8 | Unicode + emoji | `Explain 日本語 briefly 🙂` | No mojibake, no tokenizer crash |
| B9 | Long single prompt | ~1,000-token paste | Either answers or reports a budget error — must not crash natively |
| B10 | Context overflow | Converse until input + output approaches `contextWindow = 1280` | `PromptBudget` truncates or errors cleanly. **A native crash here is a P1** — the spec comment states exceeding `maxNumTokens` crashes natively |

## 7. Failure paths

| ID | Case | Setup | Expected |
|----|------|-------|----------|
| C1 | Memory pressure | Run on a device meeting E1 but with other apps loaded | Graceful error or slow completion — not a silent process kill. Compare against the 2026-07-29 `mem-pressure-event` kill |
| C2 | Insufficient-RAM guard | Select Gemma 4 E2B (expects 6 GB) on a 4 GB device | `checkDeviceMemoryCompatibility` throws the "Insufficient device memory… use a smaller model like Qwen 2.5" message *before* loading |
| C3 | Model file deleted underneath | Delete the `.task` while app is running, send a message | `checkModelReady` / `verifyModelFileIntegrity` produce a readable error, not `FileNotFoundException` in the UI |
| C4 | Zero-byte file | Replace model with a 0-byte file | Rejected by integrity check; status corrected to `Not Downloaded` |
| C5 | Unsupported backend | Select an AI Core model on a device without AI Core | "Not supported on this device"; no crash; a fallback path is offered |
| C6 | Crash logging | Force any of the above | Crash log captured and redacted per `CrashLogRedactorTest` — no file paths or tokens leaked |

## 8. Switching and persistence

| ID | Case | Expected |
|----|------|----------|
| D1 | LiteRT → cloud provider mid-session | Switch applies to the next message; no stale engine reply |
| D2 | Cloud → LiteRT mid-session | Engine initialises on demand; first reply slower, subsequent fast |
| D3 | Model choice persists | Kill and relaunch the app | Provider and model selection restored from settings |
| D4 | `deleteUnusedModels` | Install two LiteRT models, run cleanup | Only the non-active model's dir and ref file are removed; active model untouched |
| D5 | Airplane mode | Enable, then send a message | On-device inference still works — this is the core offline promise |

## 9. Exit criteria

- Section 3 fully green on the test device, recorded in the run log.
- B1–B5 pass. Any failure among them blocks release of on-device Qwen.
- B10 and C1 produce a handled error rather than a process kill or native crash.
- A4, A6, C3, C4 leave the app in a self-consistent state — reported status always matches what is on disk.
- Every defect filed carries: device, RAM, ABI, model sha256, and the relevant logcat window.

## 10. Recording results

For each case log: ID, pass/fail, device, wall-clock timings for section 6, and the logcat slice filtered to `LiteRTLMProvider|ModelRepository|ModelDownloadWorker|ActivityManager`. Treat "app process disappeared" as a distinct outcome from "app showed an error" — the 2026-07-29 run showed only the former, and the two have very different root causes.
