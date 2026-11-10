# OpenDroid Releases

See the full release documentation and changelogs in [docs/RELEASE.md](docs/RELEASE.md).

## v1.0.9 — Latest Release (November 9, 2026)

Current release. Sideload the APK for direct install, or use the AAB for Play Store distribution.

**New signing key.** v1.0.9 is signed with a new release key (certificate SHA-256 `45e7e11c6476b4f21387db1809c66c769878416f3f311e71b286f2d77db1e165`). Android refuses to update an earlier build in place: uninstall it first, then install this one.

v1.0.9 is a stability, privacy and safety release: 50 fixes across actions, auto-reply, storage, on-device models, social, memory and MCP. v1.0.8 was never published as a GitHub release, so everything in the v1.0.8 notes below also ships for the first time in this build.

### Highlights since v1.0.8

#### 🔒 Privacy & Security
* **Backups off again**: `android:allowBackup="false"` is restored, and app files are excluded from device-to-device transfer (#241).
* **Level 4 secrets kept out of saved plans**: sensitive plan parameters are kept out of the `plans` table (#240), and Level 4 label nodes are cleared, with collector errors passed through the stream wrapper (#237).
* **Workspace confinement**: file actions are confined to the workspace, refuse its root (#243), stay on the chosen custom SAF folder (#244), and refuse copy, move or zip when the source and destination overlap (#242).
* **MCP server hardening**: request headers are capped, and requests get a deadline and a bounded worker pool (#249). The MCP store no longer resets when decryption fails (#248).

#### 📲 Actions & Messaging
* **PAY_UPI** is no longer reported as sent, and its recipient and amount are validated (#206).
* **WhatsApp**: links carry the number's country code in E.164 (#208), the empty-input check uses WhatsApp's own hint, and fallbacks no longer re-send unconfirmed messages (#209). An open SMS composer counts as a hand-off, so the WhatsApp fallback can't send the message twice (#212).
* **Calls & contacts**: video calls go through the contact picker, saved picks are re-checked, and `#` is kept in tel URIs (#207).
* **TYPE_TEXT** no longer wipes a field it found by its own text (#210).
* **Do Not Disturb** turns on with the priority filter instead of total silence (#213).

#### 💬 Auto-Reply & Notifications
* Never replies through another conversation's notification (#214) and never replies into group conversations (#217).
* Pending replies are cancelled when you answer by hand (#218). Private context is kept out of the auto-reply prompt (#220).
* `AUTO_REPLY_TOGGLE` applies to every channel and the global switch (#223). The SMS toggle is limited to SMS apps (#215). Contact lookups are keyed on the app, not the bare name (#222).
* A pending settings edit is saved when you leave the screen (#219).
* Notification history is pruned after 30 days and the history screen is capped (#216). Learned patterns stay in fixed rows and are cleared with the history (#225). The learned profile is kept out of `READ_NOTIFICATIONS` results (#224).

#### 🧠 On-Device & Cloud Models
* On-device engine use is serialized under one mutex (#232). Decoding stops at the output cap (#231). The token estimate is more conservative, though still a heuristic (#230).
* The on-device planning prompt goes to the model unrewritten (#233). Only requests marked as planning are rewritten, not ones matched by prompt text (#221).
* RAM tiers are judged by what phones report, not advertised GB (#234).
* A stopped download keeps its partial file and releases the LiteRT engine (#236). Delete Unused Models keeps the selected on-device model (#235).
* Cloud provider responses are parsed without trusting their shape (#227). A huge `Retry-After` no longer overflows into an immediate retry (#226). An unknown saved provider fails instead of routing to Gemini (#229).
* Settings: a model pick is saved for the provider it was picked for (#238), and non-secret settings still save while credentials are unreadable (#228).

#### 🌐 Social
* YouTube publish and YouTube/LinkedIn replies fail visibly instead of faking success (#245).
* Publish and reply are idempotent across cancellation (#239).
* Send permissions are enforced and the right account is picked (#246). Refresh, re-sync triage, inbox replies and smaller bugs are fixed (#247).
* **Room migrations `MIGRATION_9_10` and `MIGRATION_10_11`**: the database schema goes from version 9 to 11, adding `social_comments.accountId` and `social_interactions.replyTargetId` and storing unreported metrics as unknown instead of 0.

#### 🗂️ Memory, Routines & UI
* Ordinary mentions of a spouse no longer overwrite the stored spouse name (#255). Contact picks are kept out of the prompt's user facts (#211). Startup cleanup no longer deletes user facts (#248).
* Each detected routine keeps one memory (#253). Paused routines stay on the Routines screen (#250). Routine sections come from one flow, with geocoding kept off the main thread (#254).
* The service stops instead of crashing when a foreground start is refused at boot (#252).
* Stale contact pickers are closed, and speech stops on cancel (#251).

### Release Assets
* **`app-debug.apk`** — Debug build APK for developer testing & logging.
* **`app-release.apk`** — Release APK (signed with V1+V2 signatures, ready to sideload).
* **`app-debug.aab`** — Debug Android App Bundle.
* **`app-release.aab`** — Release Android App Bundle.

### Checksums (SHA-256)
* **`app-debug.apk`**: `1ddf7efb22ed44e21f5ba60a48584207d9025370a4478a2d038bfca5c55443bb`
* **`app-release.apk`**: `cafe14e8f36c13fb99233f5fbd82bc6a4f6f0f5a0d977242acb9387b5b015615`
* **`app-debug.aab`**: `61a7452a4e545a14829b8605eeaffdf04275e0b1e1cbadab6e339e3b3a5b59a5`
* **`app-release.aab`**: `98ef49f2fd7039441837ec9269a1b71fa4519fe93503bbfa812b0b3966d6c98f`

### Build Configuration
* **Package**: `com.opendroid.aiagent`
* **Version Code**: 10
* **Version Name**: 1.0.9
* **Min SDK**: 26 (Android 8.0)
* **Target SDK**: 36 (Android 16)

---

## v1.0.8 (October 28, 2026)

Not published as a GitHub release. These changes first ship in v1.0.9.

### Highlights since v1.0.7

#### ⚡ On-Device AI Stability & Sliding Context Window
* **Sliding Window Token Budgeting (`PromptBudget.fitMessagesToBudget`)**:
  * Intelligently fits conversation turns within on-device KV-cache capacity while reserving generation tokens and preserving the system instructions and latest user prompt.
  * Solves native C++ process-aborting crashes (`SIGABRT` in `liblitertlm_jni`) caused by KV cache overflow on small-context models (e.g. Qwen 2.5 0.5B with 1280 KV cache).
  * Applied across `LiteRTLMProvider` and `GemmaProvider`.
* **Compact Core Planning Schema (`PlanningPrompts.buildOnDevicePlanningPrompt`)**:
  * Replaces 5,000-token prompt with a concise ~350-token schema tailored for local SLMs, leaving ample context for structured JSON planning.
* **Resilient JSON Plan Extraction (`extractJsonPayload`)**:
  * Isolates JSON plans even when wrapped in markdown code fences or conversational text generated by local models. Tested in `PlanExtractionTest`.
* **Realistic Device RAM & LMK Headroom Checks**:
  * Updated `OnDeviceModelRegistry.checkDeviceMemoryCompatibility` to evaluate actual working memory and kernel low-memory killer (LMK) thresholds, enabling safe inference on 4 GB–8 GB devices without false rejections.

#### 💬 Expanded Notification Auto-Reply & UI Enhancements
* **Telegram & Discord Support**: Auto-reply engine now detects and responds to Telegram and Discord notifications.
* **Instant & Custom Replies**: Added 0-minute delay and custom direct text options in `AutoReplySettingsScreen`.
* **Chat Screen Polish**: Multiline input support (up to 4 lines), widened chat bubbles (`340.dp`), and fixed scroll anchoring for thinking bubbles.

### Release Assets
* **`app-debug.apk`** — Debug build APK for developer testing & logging.
* **`app-release.apk`** — Release APK (signed with V1+V2 signatures, ready to sideload).
* **`app-debug.aab`** — Debug Android App Bundle.
* **`app-release.aab`** — Release Android App Bundle.

### Checksums (SHA-256)
* **`app-debug.apk`**: `b5db9de0b8d326a8b7c926337821eea7fcf716841ab19c4b8d2152e428f25882`
* **`app-release.apk`**: `d50ea5a90f3f38ce6c218bd515403dd7282b5ebacabaa1c0b8073d96f465b513`
* **`app-debug.aab`**: `63973ab2959c98ec97da62dafef1101e78fa56869237eaa2be5b5571aa77fdb3`
* **`app-release.aab`**: `e55cbad2dc745c790b4ac5f2b714069d0f41f229b87486d2873ae2505c864517`

### Build Configuration
* **Package**: `com.opendroid.aiagent`
* **Version Code**: 9
* **Version Name**: 1.0.8
* **Min SDK**: 26 (Android 8.0)
* **Target SDK**: 36 (Android 16)

---

## v1.0.7 (September 4, 2026)

Current release. Sideload the APK for direct install, or use the AAB for Play Store distribution.

### Highlights since v1.0.6

#### 🌐 Complete AI Social Media Management System
* **Multi-Platform Management**: Autonomous AI social assistant supporting 7 major platforms: **X (Twitter)**, **Instagram**, **LinkedIn**, **Facebook**, **YouTube**, **Telegram**, and **Discord**.
* **Unified Social Dashboard**: 10 purpose-built sub-tabs (`Overview`, `Accounts`, `Unified Inbox`, `Content Studio`, `Calendar`, `Comments`, `Campaigns`, `Analytics`, `Reports`, `Settings & Rules`).
* **Hardware-Backed Credential Security**: Android Keystore-backed AES-256-GCM encrypted vault (`SocialCredentialStore`) with Additional Authenticated Data (AAD) cryptographic binding per platform and account (`social_cred_aad:<platform>:<accountId>`), preventing cross-account substitution attacks.
* **Room Database Migration `MIGRATION_8_9`**: Upgraded Room schema to version 9 with 8 new relational entities and reactive DAOs (`social_accounts`, `social_posts`, `social_comments`, `social_interactions`, `social_analytics_snapshots`, `social_campaigns`, `social_automation_rules`, `social_audit_logs`).
* **Tri-Tier Automation & Human-in-the-Loop Safety**: Configurable automation levels (`SAFE`, `APPROVAL`, `AUTONOMOUS`) enforced by `SocialRuleEngine`. Any sensitive, financial, legal, refund, security vulnerability, breach, or negative sentiment interaction strictly triggers mandatory human review.
* **Grounded AI Intelligence Layer**:
  * `SocialContentComposer`: Crafts platform-tailored drafts respecting character budgets and hashtag norms.
  * `SocialInboxClassifier`: AI triage classifying interactions into categories (`LEAD`, `SUPPORT_REQUEST`, `QUESTION`, `FEEDBACK`, `SPAM`) with sentiment and priority scoring.
  * `SocialCommentReplyEngine`: Grounded community reply generator that retrieves verified brand knowledge memories without hallucinating or inventing fake dates.
  * `SocialScheduleWorker`: Background scheduled publishing coordinator via WorkManager.

#### 📁 Storage Management & Play Store Policy Compliance
* **Storage Access Framework (SAF) Integration**: Implemented `StorageWorkspaceProvider` to resolve Google Play Console All Files Access (`MANAGE_EXTERNAL_STORAGE`) policy requirements.
* **Granular Folder Selection**: Users can choose specific workspace folders via Android's document tree picker or operate seamlessly within app-scoped sandboxed storage without requiring broad external storage access.
* **Permissions UX Enhancements**: Modernized `PermissionsScreen` and `PermissionModel` with folder picking options for Android 11+ (SDK 30+).

#### 🎨 Pure OLED Black & Classic White Theme Redesign
* **Clean Contrast Styling**: Overhauled the entire UI theme to pure OLED black (`#000000`) in dark mode and classic pure white (`#FFFFFF`) in light mode.
* **Zero Green Accents**: Modernized all buttons, chips, and cards to sleek obsidian/titanium monochrome styling with subtle sky sapphire active badges.
* **Enhanced Visual Feedback & Animations**: Fluid sub-tab transitions, animated sync indicators, and responsive canvas charts (`SocialCharts.kt`).

#### 🧪 LiteRT Model Compatibility & Stability
* **Compatibility Fixes**: Resolved model verification failure when downloading LiteRT on-device models (e.g. Qwen 2.5, Gemma).
* **Failure Marker Matching & Memory Safety**: Enhanced probe evaluation and fallback behavior for on-device inference.

#### 🛠️ Version & Build Updates
* **Version Bump**: Updated app version to `1.0.7` (`versionCode 8`).
* **Comprehensive Test Suite**: Added `SocialPlatformTest`, `OpenDroidDatabaseMigrationTest` (validating schema v8 to v9 migration and data preservation), and `SocialRuleEngineTest` with 100% passing tests.

### Release Assets
* **`app-debug.apk`** — Debug build APK for developer testing & logging.
* **`app-release.apk`** — Release APK (sideload for testing).
* **`app-debug.aab`** — Debug Android App Bundle.
* **`app-release.aab`** — Release Android App Bundle.

### Checksums (SHA-256)
* **`app-debug.apk`**: `3cd060523174964465ac24a5c4f37ab4d11c4fa69f43931681b103557233fb80`
* **`app-release.apk`**: `eef3d9c61ae7783dbc6b0cb30fe6714b94f2c74ba7c182dd1e00a05da29de11b`
* **`app-debug.aab`**: `81461c621fc30dacb25099bdcd3382229ccdfb56f835d03d24563797d3e8c57f`
* **`app-release.aab`**: `03186f5d4410c7f758d0ec215dc49f61aa0945bb61e5ba78c47a804611eab29b`

### Build Configuration
* **Package**: `com.opendroid.aiagent`
* **Version Code**: 8
* **Version Name**: 1.0.7
* **Min SDK**: 26 (Android 8.0)
* **Target SDK**: 36 (Android 16)

### Install notes for testers
1. Download `app-release.apk` or `app-debug.apk` from the GitHub release.
2. Enable install from unknown sources for your browser/file manager.
3. Sideload the APK; uninstall any prior build with a different signing key if Android blocks the update.
4. Report issues against tag `v1.0.7`.

---

## v1.0.6 (August 20, 2026)

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

