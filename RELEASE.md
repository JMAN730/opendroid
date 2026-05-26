# OpenDroid Releases

This document tracks release updates, changelogs, and binary verification checksums for the OpenDroid project.

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

---

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
