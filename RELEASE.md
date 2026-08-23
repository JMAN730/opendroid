# OpenDroid Releases

See the full release documentation and changelogs in [docs/RELEASE.md](docs/RELEASE.md).

## v1.0.6 — Latest Release (August 20, 2026)

### Highlights since v1.0.5

#### 🔄 Habit & Routine Detection Engine
* **Proactive Pattern Recognition**: OpenDroid tracks app usage habits over time and detects repeated daily/weekly workflows (e.g., every weekday at 9:00 AM: *Gmail → Calendar → Slack → Chrome*).
* **Proactive Automation Prompts**: Surfaces smart suggestions with confidence metrics: *"I noticed you usually do these tasks every weekday morning. Would you like me to automate them?"*
* **Multi-Step Morning Routine Automation**: Automatically synthesizes structured morning briefings:
  1. Read today's calendar (`LIST_CALENDAR_TODAY`)
  2. Summarize upcoming meetings (`GET_MORNING_BRIEFING`, `section = "schedule"`)
  3. Check important notifications (`READ_NOTIFICATIONS`)
  4. Prepare task list from notes (`READ_NOTES`)
  5. Read selected messages (`READ_NOTIFICATIONS`)
  6. Deliver spoken or text morning briefing (`GET_MORNING_BRIEFING`, `section = "full"`)
* **One-Click Approval & Macro Scheduling**: User approval converts detected routines into recurring scheduled macros in `MacroDao` and logs knowledge nodes in `PersonalGrowthEngine`.
* **Dedicated Routines Screen**: Added modern UI screen (`RoutinesScreen.kt`) with suggestion approval cards, active routines list, template presets (*Morning Routine*, *Work Focus*, *Evening Wrap-up*), and learning analytics.
* **Room Database Migration `MIGRATION_7_8`**: Added `habit_events` and `habit_routines` tables, upgrading schema to version 8.

#### ✈️ Telegram Control & Automation
* **Full Telegram Automation**: OpenDroid now supports end-to-end messaging and chat control on Telegram alongside WhatsApp and SMS.
* **`SEND_TELEGRAM` & `OPEN_TELEGRAM` Actions**: Direct handling of `@username` handles, contact address book lookups, international phone numbers, and chat links (`tg://resolve`, `https://t.me/`).
* **`TelegramAutomator`**: Automatic accessibility typing and sending across official Telegram, Telegram Web/FOSS, Plus Messenger, and NekoX.
* **Habit Engine Integration**: Package recognition tracks Telegram workflows in routine mining.

#### 🧪 LiteRT Model Compatibility & Probe Fixes
* **Model Verification Fix**: Fixed a bug where downloaded LiteRT models (such as `Gemma 4 e2b-it` and `Qwen 2.5`) falsely reported `FORMAT_INVALID` during initialization verification.
* **Failure Marker Matching**: Corrected probe verification logic from strict `.all` failure matching to `.any` marker matching and expanded backend-specific error classification for GPU/NPU/CPU fallbacks.

#### 🛠️ Version & Build Updates
* **Version Bump**: Updated app version to `1.0.6` (`versionCode 7`).
* **Comprehensive Test Coverage**: Added `HabitRoutineEngineTest`, `RoutineActionsTest`, and `TelegramActionsTest` with 100% passing test suite.

### Release Assets
* **`app-debug.apk`** — Debug build APK for developer testing & logging.
* **`app-release.apk`** — Release APK (sideload for testing).
* **`app-debug.aab`** — Debug Android App Bundle.
* **`app-release.aab`** — Release Android App Bundle.

### Checksums (SHA-256)
* **`app-debug.apk`**: `07025caea20b4c9e32c7889549777266605e94361985ec4ce790f988920b4d63`
* **`app-release.apk`**: `c10b8ec614d38aab2f0bbaa254d38bc7ec7bde7b296963b74ddda86e12748a6e`
* **`app-debug.aab`**: `e2025affd0d0f344085b16eb486295f7fca9b513cf5159a3781b9c839eb1c149`
* **`app-release.aab`**: `de9bd177e4274cc97ec45913d2ce2e59389291bed45d0eddbd770bbe9e956f23`

### Build Configuration
* **Package**: `com.opendroid.aiagent`
* **Version Code**: 7
* **Version Name**: 1.0.6
* **Min SDK**: 26 (Android 8.0)
* **Target SDK**: 36 (Android 16)

