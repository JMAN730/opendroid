# OpenDroid AI Agent — User & System Guide

OpenDroid is a robust, local-first Android AI Agent designed to execute complex user tasks via structured plans, natural language command processing, and advanced system automation tools.

---

## 📖 Table of Contents
1. [Core Features](#1-core-features)
2. [Communication Commands (WhatsApp, Telegram, Calls, SMS)](#2-communication-commands)
3. [Habit & Routine Detection](#3-habit--routine-detection)
4. [Screen Understanding & Memory ("Read & Remember")](#4-screen-understanding--memory)
5. [Personal Growth Memory & Knowledge Graph](#5-personal-growth-memory--knowledge-graph)
6. [System & Settings Actions](#6-system--settings-actions)
7. [Advanced Control & File System Actions](#7-advanced-control--file-system-actions)
8. [Macros: Creation & Automation](#8-macros-creation--automation)
9. [On-Device AI Models (LiteRT-LM)](#9-on-device-ai-models)
10. [Troubleshooting & Permission Guides](#10-troubleshooting--permission-guides)

---

## 1. Core Features

OpenDroid leverages a hybrid execution flow to run tasks reliably:
* **Direct Command Aliasing**: Common phrases (e.g., "toggle flashlight", "settings", "detect routines") are intercepted and resolved locally without needing an LLM call.
* **Structured Planning**: Complex queries (e.g., "take a screenshot and message it to Dad on Telegram") are planned by an LLM into sequential steps.
* **Self-Contained Actions**: Critical intents (Calls, WhatsApp, Telegram, SMS) do not require opening their apps first. The agent handles deep links or fallback intents natively.
* **Action Auto-Mapper**: Automatically corrects minor typo variations or hallucinated actions before execution.

---

## 2. Communication Commands

OpenDroid features robust, multi-channel communication controls:

| Action Name | Parameters | Examples & Behavior |
| :--- | :--- | :--- |
| **`SEND_TELEGRAM`** | `contact`, `message` | *"Send hello to @durov on Telegram"*, *"Message Alice on Telegram saying I'll be 10 min late"*. Supports direct `@username` handles, contact names, and international phone numbers. |
| **`OPEN_TELEGRAM`** | `contact` *(optional)* | *"Open Telegram"*, *"Open Telegram chat with @channel"*. Launches Telegram or opens a specific chat/channel. |
| **`SEND_WHATSAPP`** | `contact`, `message` | *"WhatsApp Mom I'm on my way"*. Opens WhatsApp directly, populates the text, and automates tapping Send. |
| **`SEND_WHATSAPP_GROUP`** | `groupName`, `message` | *"Send hello to Family Group on WhatsApp"*. Navigates to WhatsApp groups. |
| **`MAKE_CALL`** | `contact` | *"Call Dad"*, *"Call +1234567890"*. Resolves contact using fuzzy matching and dials directly or via dialer intent. |
| **`SEND_SMS`** | `contact`, `message` | *"Text Bob I'm outside"*. Sends SMS directly if permitted, or opens pre-filled composer. |
| **`SEND_EMAIL`** | `to`, `subject`, `body` | *"Email boss subject Sick Leave body I won't make it today"*. Opens pre-filled email draft. |
| **`READ_MESSAGES`** | `app` *(sms, whatsapp, telegram)* | *"Read my messages on Telegram"*. Opens messaging conversations. |

---

## 3. Habit & Routine Detection

OpenDroid automatically detects repeated usage patterns and offers one-click routine automations.

### How Habit Mining Works:
* **Background Observation**: As you use your device normally, OpenDroid records app switches and times of day.
* **Pattern Detection**: When a sequence of actions repeats (e.g. every weekday at 9:00 AM you open *Gmail $\to$ Calendar $\to$ Slack $\to$ Chrome*), OpenDroid generates a suggested routine.
* **Proactive Card**: *"I noticed you usually do these tasks every weekday morning. Would you like me to automate them?"*

### Routine Commands:
* **`GET_MORNING_BRIEFING`**: Composes a structured morning briefing with calendar events, unread notifications, and task reminders.
  * *Parameters*: `section` (`"full"`, `"schedule"`, `"notifications"`), `speak` (`"true"`/`"false"`).
* **`RUN_ROUTINE`**: Executes an approved automated routine by name or ID (*"Run morning routine"*).
* **`DETECT_ROUTINES`**: Analyzes recent habit history on demand (*"Detect routines"*).
* **`APPROVE_ROUTINE`**: Approves a suggested habit routine and converts it into a scheduled macro (*"Approve morning routine"*).

---

## 4. Screen Understanding & Memory ("Read & Remember")

Extract and remember important information directly from your screen:

* **`READ_AND_REMEMBER_SCREEN`**: Captures screen contents (via vision model + accessibility tree) and extracts meeting dates, locations, action items, or notes directly into memory.
  * *Examples*: *"Read this screen and save the meeting to my notes"*, *"Remember this WhatsApp message"*.
* **`RECALL_MEMORY`**: Queries saved screen notes, facts, and memories using semantic natural language search.
  * *Examples*: *"What did I save about the team meeting?"*, *"Read my notes about flight details"*.

---

## 5. Personal Growth Memory & Knowledge Graph

OpenDroid builds a 4-tier Personal Knowledge Graph of your preferences and habits:
1. **⚡ Level 1 (Temporary)**: Current task plan and active session variables.
2. **🧠 Level 2 (Long-Term)**: Explicit facts (*"My wife is Sarah"*).
3. **📈 Level 3 (Learned Patterns)**: Inferred behaviors with dynamic confidence scoring (50% $\to$ 85% $\to$ 95%).
4. **🔒 Level 4 (Sensitive Data)**: Confidential secrets (passwords, PINs) encrypted with Android Keystore AES-256-GCM.

### Knowledge Commands:
* **`QUERY_KNOWLEDGE_GRAPH`**: *"What are my favorite music apps?"*, *"What are my active routines?"*
* **`UPDATE_PREFERENCE`**: *"Set my preferred ride app to Uber"*.
* **`SAVE_SENSITIVE_INFO`**: *"Save locker pin 4921 securely"*.

---

## 6. System & Settings Actions

* **`TOGGLE_FLASHLIGHT`**: Toggles camera torch.
* **`TOGGLE_WIFI`**: Switches Wi-Fi on/off/toggle.
* **`TOGGLE_BLUETOOTH`**: Switches Bluetooth on/off/toggle.
* **`TOGGLE_DND`**: Toggles Do Not Disturb.
* **`TOGGLE_HOTSPOT`**: Enables or disables mobile hotspot.
* **`OPEN_APP`**: Launches any app by name (*"Open Spotify"*, *"Open Telegram"*).
* **`TAKE_SCREENSHOT`**: Captures device screen.

---

## 7. Advanced Control & File System Actions

### Screen Automation:
* **`CLICK_TEXT`**: Clicks element containing text.
* **`CLICK_ID`**: Clicks view by exact resource ID.
* **`TYPE_TEXT`**: Types text into an editable input.
* **`SCROLL`**: Scrolls `forward` or `backward`.
* **`GET_SCREEN_TEXT`**: Scrapes all visible text from the current screen.

### File Management:
* **`LIST_FILES`**, **`READ_FILE`**, **`WRITE_FILE`**, **`DELETE_FILE`**, **`CREATE_DIRECTORY`**, **`ZIP_FILES`**, **`UNZIP_FILE`**.

---

## 8. Macros: Creation & Automation

Create custom automated workflows or schedule them via cron triggers:

```json
[
  { "action": "LIST_CALENDAR_TODAY", "params": {} },
  { "action": "GET_MORNING_BRIEFING", "params": { "section": "full" } },
  { "action": "OPEN_TELEGRAM", "params": {} }
]
```

---

## 9. On-Device AI Models (LiteRT-LM)

* Download offline models (`Gemma 4 e2b-it`, `Qwen 2.5`, `Llama 3.2`) in Settings $\to$ On-Device Models.
* Supports background downloads over Wi-Fi or Cellular networks (with data warning confirmation).
* Verified integrity with SHA-256 validation and JNI compatibility probing.
