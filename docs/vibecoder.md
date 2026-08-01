# 🧬 VibeCoder — OpenDroid Contributor Philosophy

> **"Don't just write it. Prove it works."**

This document defines the contributor mindset for OpenDroid. If you're contributing code, you're a **VibeCoder** — someone who doesn't ship assumptions, but ships **battle-tested, real-world-proven features**.

---

## 🤖 Model Requirement: Opus 4.8+ Level

All contributors to OpenDroid **must** use a **frontier-class AI model** — Claude Opus 4.8 or equivalent tier — when:

| Activity | Why Opus 4.8+ |
|----------|---------------|
| Writing new Actions | Frontier models understand Android accessibility APIs, edge cases, and multi-step planning far better than smaller models |
| Debugging agent failures | Complex reasoning chains require a model that can hold the full execution context |
| Designing multi-step workflows | Only frontier models reliably decompose "send a WhatsApp message AND set an alarm" into correct sequential steps |
| Reviewing PRs | Opus-level models catch subtle logic errors, race conditions, and missing error handling that smaller models miss |
| Writing tests & scenarios | Generating realistic, adversarial test scenarios requires deep understanding of real-world phone usage |

> [!IMPORTANT]
> **Why not a smaller model?** OpenDroid is an *autonomous agent* — a single missed edge case can send the wrong message to the wrong person, delete data, or brick a user's workflow. The cost of frontier reasoning is a rounding error compared to the cost of shipping broken autonomy.

---

## 🧪 The Golden Rule: Always Test the Scenario

**Every feature you integrate MUST be tested against real-world scenarios before submitting a PR.**

This is non-negotiable. If you add a capability, you prove it works end-to-end.

---

### 📱 Mandatory Test Scenarios

#### 1. WhatsApp Message Sending
```
Scenario: "Send a WhatsApp message to John saying I'll be 10 minutes late"
```

**What to verify:**
- ✅ WhatsApp opens correctly
- ✅ Correct contact is selected (not a similarly-named contact)
- ✅ Message text is entered accurately — no truncation, no typos
- ✅ Message is actually sent (not just typed and left unsent)
- ✅ Agent confirms success or reports failure gracefully
- ✅ Works when WhatsApp is already open on a different chat
- ✅ Works when the contact has multiple numbers

**Edge cases to test:**
- Contact not found → agent should inform the user, NOT send to a random contact
- No internet → agent should detect and report, not hang silently
- WhatsApp not installed → graceful fallback or clear error message

---

#### 2. Email Composition & Sending
```
Scenario: "Email my boss the quarterly report summary with subject 'Q3 Update'"
```

**What to verify:**
- ✅ Email app opens (Gmail, Outlook, or default mail client)
- ✅ Recipient field is populated correctly
- ✅ Subject line matches the user's request exactly
- ✅ Body content is composed accurately
- ✅ Email is sent (not left as a draft)
- ✅ Attachments are handled if mentioned

**Edge cases to test:**
- Multiple email accounts configured → correct account is used
- Recipient not in contacts → manual email entry works
- Long email body → no truncation or loss of content
- CC/BCC handling when requested

---

#### 3. Complex Multi-Step Tasks
```
Scenario: "Check if it's going to rain tomorrow, and if so, text my wife that
I'll pick up the kids, then set an alarm for 5:30 PM"
```

**What to verify:**
- ✅ Task is decomposed into correct sequential steps
- ✅ Conditional logic is respected (only texts if rain is predicted)
- ✅ Each step verifies its own success before proceeding
- ✅ If Step 2 fails, Step 3 still attempts (unless dependent)
- ✅ Final summary reports what was done and what wasn't
- ✅ Re-planning kicks in if an intermediate step fails

**Edge cases to test:**
- Weather API returns ambiguous data → agent handles uncertainty
- SMS app not set as default → agent adapts
- Alarm time is in the past → agent clarifies with user
- One step succeeds but another fails → partial completion is reported clearly

---

#### 4. Your Custom Feature
```
Scenario: Whatever YOUR feature does — test it like a real user would.
```

**What to verify:**
- ✅ Happy path works flawlessly
- ✅ All error states are handled (no crashes, no silent failures)
- ✅ Accessibility service interactions are clean (no orphaned UI states)
- ✅ Feature works alongside existing features (no regressions)
- ✅ Performance is acceptable (no 30-second hangs)
- ✅ The agent's natural language response makes sense to a non-technical user

---

## 🔁 The VibeCoder Testing Loop

Every PR must follow this loop before submission:

```
┌─────────────────────────────────────────────────┐
│                                                 │
│   1. BUILD the feature                          │
│       ↓                                         │
│   2. WRITE realistic test scenarios             │
│       ↓                                         │
│   3. RUN on a real device or emulator           │
│       ↓                                         │
│   4. BREAK it — try every edge case you can     │
│       ↓                                         │
│   5. FIX what breaks                            │
│       ↓                                         │
│   6. RE-RUN all scenarios                       │
│       ↓                                         │
│   7. DOCUMENT what you tested in the PR         │
│       ↓                                         │
│   8. SHIP with confidence                       │
│                                                 │
└─────────────────────────────────────────────────┘
```

---

## 📋 PR Test Report Template

Every Pull Request **must** include a test report. Copy and fill in:

```markdown
## 🧪 VibeCoder Test Report

**Feature:** [Brief description]
**Model Used:** [e.g., Claude Opus 4.8]
**Device/Emulator:** [e.g., Pixel 7 / API 34 Emulator]

### Scenarios Tested

| # | Scenario | Result | Notes |
|---|----------|--------|-------|
| 1 | [Description] | ✅ Pass / ❌ Fail | [Details] |
| 2 | [Description] | ✅ Pass / ❌ Fail | [Details] |
| 3 | [Description] | ✅ Pass / ❌ Fail | [Details] |

### Edge Cases Tested

| # | Edge Case | Result | Notes |
|---|-----------|--------|-------|
| 1 | [Description] | ✅ Handled / ❌ Unhandled | [Details] |

### Regression Check
- [ ] Existing WhatsApp actions still work
- [ ] Existing email actions still work
- [ ] Existing alarm/reminder actions still work
- [ ] No new crashes in logcat
- [ ] Accessibility service remains stable
```

---

## 🚫 What Gets Your PR Rejected

| Anti-Pattern | Why It's Rejected |
|-------------|-------------------|
| "It works on my end" with no proof | We need reproducible test evidence |
| Tested only the happy path | Real users hit edge cases constantly |
| Used a small/fast model for complex logic | Frontier models catch errors that smaller models miss |
| No test report in the PR | If you didn't document it, it didn't happen |
| Feature breaks existing scenarios | Regressions are unacceptable in an autonomous agent |
| Silent failures (no error message to user) | Users must always know what happened and why |

---

## 🧠 The VibeCoder Mindset

```
A VibeCoder doesn't ask "does it compile?"
A VibeCoder asks "would I trust this to send a message to my boss?"
```

We're building an agent that **acts on behalf of real people** on their most personal device. Every action we ship has the potential to:

- 📩 Send a message to the wrong person
- 🗑️ Delete something that shouldn't be deleted
- ⏰ Set an alarm at the wrong time
- 📧 Email the wrong content to the wrong recipient

**This is why we demand frontier-model reasoning and exhaustive real-world testing.** Not because we're perfectionists — because our users are trusting us with their digital lives.

---

## 🤝 Join the Vibe

If this resonates with you — welcome. You're a VibeCoder.

Fork the repo, pick an issue, fire up Opus 4.8, and build something you'd be proud to run on your own phone.

> *"Ship it like your grandma is going to use it."*

---

<p align="center">
  <strong>OpenDroid — Your phone. Your rules. Your AI.</strong>
</p>
