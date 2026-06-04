# OpenDroid Releases

This document tracks release updates, changelogs, and binary verification checksums for the OpenDroid project.

---

## v1.0.1 — Notification Intelligence & Theme Update

### 🔔 Notification Intelligence & Auto-Reply
*   **NotificationListenerService**: Intercepts all system notifications in real-time, persists them to a local Room database for analysis and recall.
*   **AI Auto-Reply Engine**: Automatically generates contextual replies for WhatsApp, SMS, and Email using the active LLM provider.
    *   Configurable 1–60 minute reply delay (default: 15 minutes).
    *   Per-app toggles (WhatsApp, SMS, Email) and global master toggle.
    *   Rate-limiting (max replies per contact per hour).
    *   Contact blacklist/whitelist support.
    *   Custom reply tone/style prompt.
*   **Reply Dispatcher**: Dispatches replies via Android `RemoteInput` (WhatsApp inline reply) and `SmsManager` (SMS).
*   **Pattern Learning**: `NotificationIntelligence` analyzes communication patterns (top contacts, peak hours, app usage) and stores them as semantic memories for adaptive agent behavior.
*   **New Actions**: `READ_NOTIFICATIONS` and `AUTO_REPLY_TOGGLE` added to ActionSchema, accessible via natural language ("read my notifications", "turn on auto reply").

### 🎨 Light & Dark Theme
*   **Dynamic Theme System**: Added `OpenDroidColors` palette with `CompositionLocal` provider for runtime theme switching.
*   **Light Mode**: Clean, GitHub-inspired light palette with proper contrast and readability.
*   **Dark Mode**: Existing dark theme preserved as default.
*   **Live Toggle**: Settings → Planning & Automation → Dark/Light Mode switch. Changes apply instantly without restart.
*   **Status Bar Adaptation**: Status bar and navigation bar icons automatically adjust for light/dark appearance.

### 📱 New UI Screens
*   **Auto-Reply Settings Screen**: Full configuration UI with toggles, delay slider, rate limit, and custom tone prompt.
*   **Notification History Screen**: View all captured notifications with filter chips (All/Message/Email/Social/Replied), stats dashboard, and auto-reply log.
*   **Settings Navigation**: Two new cards in Settings for "Auto-Reply Settings" and "Notification History".

### 🛠️ Technical Changes
*   **Database**: Room migration v2→v3 adding `notifications` table.
*   **DI**: `NotificationDao`, `AutoReplyEngine`, `NotificationIntelligence`, `NotificationActions` registered in Hilt.
*   **Manifest**: Registered `OpenDroidNotificationListener` service with `BIND_NOTIFICATION_LISTENER_SERVICE` permission.
*   **MemoryManager**: Now includes notification context and learned communication patterns in LLM context window.
*   **ActionDispatcher**: Registered `NotificationActions` (READ_NOTIFICATIONS, AUTO_REPLY_TOGGLE).

### 📦 Release Assets
*   **`app-release.apk`** — Signed production APK.
*   **`app-release.aab`** — Signed Android App Bundle.

### 🔑 Build Configuration
*   **Package**: `com.opendroid.ai`
*   **Version Code**: 2
*   **Version Name**: 1.0.1
*   **Min SDK**: 26 (Android 8.0)
*   **Target SDK**: 34 (Android 14)

---

## v1.0.0 — Production Release

First official production release of OpenDroid, targeting Google Play Store, Amazon Appstore, Samsung Galaxy Store, and other Android app marketplaces.

### 🚀 Key Features

#### 🤖 Multi-Provider LLM Agent
*   Supports **11 LLM providers**: OpenAI, Claude, Gemini, Mistral, DeepSeek, Groq, Cohere, Together AI, OpenRouter, Ollama (local), and Copilot.
*   Autonomous multi-step task planning with schema-enforced action execution.
*   Real-time plan visualization and re-evaluation engine.

#### 📸 Multimodal Vision Engine & Screenshot Fallback
*   Integrated **`ANALYZE_SCREENSHOT`** to capture active layouts.
*   **Dual-Tier fallback framework**: hardware screen capture → layout text-scraping fallback.
*   Guides the user with clear instructions to re-enable accessibility services if both methods fail.

#### 🛡️ Intent Safeguards & Compound Phrase Guard
*   **AliasResolver Guard**: word-guarding to prevent partial alias matching.
*   **ActionSchema enforcement**: hardcoded action schema system eliminates LLM action hallucinations.

#### 📞 Hardened Call & SMS Intents (Zero-Refusal Policies)
*   **`SEND_SMS` Fallback**: carrier sending → SMS composer intent fallback.
*   **`MAKE_CALL` Fallback**: direct dialing → dialer screen fallback.
*   **Contact Resolver Safety**: informative errors when contacts not found.

#### 🔦 Device Control
*   Flashlight toggle with hardware state tracking via `TorchCallback`.
*   Bluetooth, WiFi, brightness, volume, and Do Not Disturb controls.
*   Alarm, timer, reminder, and calendar event management.

#### 🏠 Smart Home & Transport
*   Smart home device control (lights, thermostat, door locks).
*   Ride booking (Uber, Ola) and navigation/directions.

#### 🧠 Memory & Macros
*   Persistent memory system for learning user preferences.
*   Macro recording and scheduled execution.

#### 🔐 Security
*   Encrypted API key storage using AndroidX Security Crypto.
*   Scoped network security — cleartext HTTP restricted to localhost only.
*   Backup exclusion for encrypted preferences.

### 📦 Release Assets
*   **`app-release.apk`** — Signed production APK (for sideloading and non-Play stores).
*   **`app-release.aab`** — Signed Android App Bundle (for Google Play Store upload).

### 🔑 Build Configuration
*   **Package**: `com.opendroid.ai`
*   **Version Code**: 1
*   **Version Name**: 1.0.0
*   **Min SDK**: 26 (Android 8.0)
*   **Target SDK**: 34 (Android 14)
*   **R8 minification**: Enabled
*   **Resource shrinking**: Enabled
*   **Signing**: APK Signature Scheme v2
