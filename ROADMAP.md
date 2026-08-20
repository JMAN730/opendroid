# OpenDroid Roadmap

Living document. Reflects the state of `JMAN730/opendroid` as of **2026-08-20**, shipping
version **1.0.6** (`versionCode 7`).

---

## Where we are

| Dimension | State |
|---|---|
| **App** | Production-ready autonomous Android AI agent with Habit & Routine Detection, Telegram Automation, Screen Understanding, and 4-tier Knowledge Graph |
| **SDK** | `minSdk 26`, `compileSdk`/`targetSdk 36` |
| **Toolchain** | Gradle 9.7.0, AGP 9.3.1, Kotlin 2.4.0 (AGP built-in Kotlin), JDK 21 (pinned) |
| **Database** | Room DB v8 with explicit sequential migrations (`MIGRATION_1_2` through `MIGRATION_7_8`) |
| **Tests** | Comprehensive JVM unit-test suite (`HabitRoutineEngineTest`, `RoutineActionsTest`, `TelegramActionsTest`, `LiteRtCompatibilityTest`, etc.) and `androidTest` accessibility/keystore test harness |
| **CI** | 4 jobs — unit tests + `assembleDebug`, `lintDebug`, `connectedDebugAndroidTest` on an API 26/36 emulator matrix, unsigned `assembleRelease` (R8) |
| **Distribution** | GitHub Releases (APK / AAB), FOSS packaging |

---

## Recently Shipped

### v1.0.6 (August 20, 2026)
- **Habit & Routine Detection Engine**: Continuous background observation of debounced app switches via accessibility events, 30-minute session clustering, sequence mining (e.g., *Gmail → Calendar → Slack → Chrome* at 9:00 AM on weekdays), proactive suggestions (*"I noticed you usually do these tasks every weekday morning. Would you like me to automate them?"*), 6-step Morning Routine synthesis and execution, and one-click macro scheduling.
- **Telegram Control & Automation**: End-to-end messaging and navigation (`SEND_TELEGRAM`, `OPEN_TELEGRAM`) with `@username` handle resolution, contact address book lookups, international numbers, deep link schemes (`tg://resolve`, `https://t.me/`), and automated accessibility dispatch via `TelegramAutomator`.
- **LiteRT-LM Compatibility Verification Fix**: Corrected probe verification logic from strict `.all` failure matching to `.any` marker matching in `LiteRtCompatibility.kt`, resolving false `FORMAT_INVALID` failures during verification of LiteRT models like Gemma 4 e2b-it and Qwen 2.5.
- **Database Schema v8**: `MIGRATION_7_8` added `habit_events` and `habit_routines` tables.

### v1.0.5 (August 18, 2026)
- **Screen Understanding ("Read & Remember")**: Multimodal screen analysis extracting structured meeting details (*Title, Date, Time, Location, Participants, Action Items*), summaries, and notes (`READ_AND_REMEMBER_SCREEN`, `RECALL_MEMORY`).
- **4-Tier Personal Knowledge Graph (`PersonalGrowthEngine`)**:
  - ⚡ Level 1 (Temporary): Working plan state.
  - 🧠 Level 2 (Long-Term): Explicit user facts & preferences.
  - 📈 Level 3 (Learned Patterns): Inferred behaviors with dynamic confidence scoring (50% → 85% → 95%).
  - 🔒 Level 4 (Sensitive Data): Hardware-encrypted secrets (AES-256-GCM via Android Keystore).
- **Cellular Network Model Downloads**: Download LiteRT models over mobile data with user confirmation dialogs and carrier warning alerts.

### v1.0.4 (August 10, 2026)
- **Dynamic Model Discovery**: Real-time provider model list fetching from remote endpoints (OpenAI, Gemini, Ollama, Groq, Cohere, OpenRouter) with capability-based chat model filtering.

### v1.0.3 (August 5, 2026)
- **Direct Keystore Credential Storage**: Android Keystore AES-256-GCM authenticated envelopes for API keys and profile data.
- **Build & Toolchain**: Target SDK 36, Gradle 9.7.0, AGP 9.3.1, Retrofit 3.0.0, OkHttp 5.4.0 BOM.
