# OpenDroid Releases

This document tracks release updates, changelogs, and binary verification checksums for the OpenDroid project.

---

## v1.0.0 (Updated) — Stability and Multimodal Release

This update introduces critical bug fixes, reliability hardening, and the official deployment of the Multimodal Vision Engine.

### 🚀 Key Improvements

#### 📸 Multimodal Vision Engine & Screenshot Fallback
*   Integrated **`ANALYZE_SCREENSHOT`** to capture active layouts.
*   **Dual-Tier fallback framework**: If the system's hardware screen capture (`takeScreenshotAndEncode()`) fails (e.g. on older Android APIs or due to secure windows), the engine automatically falls back to layout text-scraping (`getScreenText()`) to feed UI information to the LLM.
*   Guides the user with clear instructions to re-enable accessibility services if both methods fail.

#### 🛡️ Intent Safeguards & Compound Phrase Guard
*   **AliasResolver Guard**: Implemented word-guarding (`send`, `message`, `call`, etc.) to prevent partial alias matching. For example, a command like *"open whatsapp and send a message"* is correctly delegated to the LLM planner rather than triggering a simple `OPEN_APP` launch.

#### 📞 Hardened Call & SMS Intents (Zero-Refusal Policies)
*   **`SEND_SMS` Fallback**: Overhauled direct carrier sending. If cellular/telephony capabilities are missing or disabled, the action gracefully launches the native SMS composer intent (`ACTION_SENDTO`) pre-filled with the contact's number and message.
*   **`MAKE_CALL` Fallback**: Handles direct dialing restrictions. If background dialing permissions (`CALL_PHONE`) are denied, it falls back to the dialer screen (`ACTION_DIAL`) with the number pre-populated.
*   **Contact Resolver Safety**: Replaced empty fallbacks with clear, informative errors when a contact cannot be found in the user's address book (e.g., `Contact 'dad' not found in your contacts`) instead of failing silently or launching empty windows.

#### 🔦 Flashlight Control Toggling
*   Overhauled the **`TOGGLE_FLASHLIGHT`** state tracker. The action now correctly monitors the physical hardware states via `TorchCallback` (correctly toggles the light `on` or `off` based on current hardware status instead of blindly forcing it to `on`).

#### 📝 Honest Success & Planning Reports
*   Refactored `AgentLoop.speakAndSaveSummary()` to extract descriptive results directly from individual Action executions instead of delivering generic "successfully executed plan" notifications.

---

### 📦 Release Assets
*   **`app-debug.apk`** — Debug build of the Android agent.
