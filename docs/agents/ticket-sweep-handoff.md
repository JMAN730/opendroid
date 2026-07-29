# Ticket sweep handoff — 2026-07-29

Session goal: close every open ticket on the fork (`JMAN730/opendroid`), one fresh agent
session per ticket, grilling tickets self-grilled, PRs opened at the end.

Stopped early: the machine could not carry the parallel agent load. This file records
exactly where the work stands so it can be resumed on the workstation without re-deriving
anything.

**Tracker note.** `AGENTS.md` makes the fork the only issue tracker; `yashab-cyber/opendroid`
is off limits. `gh` defaults to upstream when both remotes are present, so every `gh issue`
and `gh pr` command below passes `--repo JMAN730/opendroid` explicitly, and `gh api` calls
carry the repository in the endpoint path instead. The two upstream bug reports (#13 "API
Failer", #9 "LOCAL IMPORT NOT WORKING") are the *origin* of this work but are not the
tracker — upstream #13 is what map #25 exists to fix.

## Done

Six design tickets closed, each with its full reasoning posted as a comment before closing:

| Ticket | Subject |
|---|---|
| #22 | Prototype the grant-all panel states |
| #23 | Define the JVM test surface for the permission model |
| #30 | Retry policy for transient provider failures |
| #33 | Streaming error propagation contract |
| #39 | Fate of executeWithFallback |
| #40 | Key redaction rule and Gemini key transport |

Both map bodies (#25, #18) have been updated with a "Decisions so far" entry per closed
ticket. Map #25's established-facts list also had a **factual error corrected** — see below.

Two open PRs received real commits before the session ended:

- **PR #36** (`JMAN730/implement-crash-log`) — `5b2c843 fix: redact credentials from crash
  records at capture time`, on top of a merge of `upstream/main`.
- **PR #38** (`JMAN730/to-spec-branch-name`) — three commits: `c47b372` migrate retired
  Claude 4.x/2.x IDs instead of forwarding them, `c6654bf` reset an unsupported Claude
  selection even when the model cache is fresh, `20b3121` surface Claude config errors as
  instructions rather than exception dumps.

Neither PR was merged and neither issue was closed — that is deliberate, they are yours to
review.

## Findings that outrank the ticket work

### 1. Live credential leak on `main` (from #40)

`OpenAIProvider.kt:65` interpolates the raw 401 response body into an `IOException`. Per
#27, that body echoes the key's **real first 8 and last 4 characters** — key material, not a
mask. The string then reaches:

- logcat
- the persisted `task_history` table (`AgentLoop.kt:593`)
- the in-app Logs screen, verbatim (`LogsScreen.kt:436-449`)
- a persisted chat message (`OpenAIProvider.kt:102` -> `AgentLoop.kt:442`)

and then **leaves the device**, because `AgentLoop.kt:413` feeds the last 10 chat messages
into the next `LLMRequest` (`:435`) — potentially to a different vendor than the key belongs
to.

Minimal fix: drop `$responseBody` from the throw in all 11 cloud providers. Not yet done —
it was queued behind PR #38 to avoid colliding in `ClaudeProvider.kt`. Tracked as **#46**.

### 2. Cross-vendor data exfiltration (from #39)

`CustomOpenAIProvider.isAvailable()` returns `true` unconditionally (`:103`) while its URL
defaults to `api.openai.com/v1` (`:38`). A user who selects Claude without a Claude key has
their traffic POSTed to OpenAI with an empty bearer — and that traffic includes a contact's
notification body (`AutoReplyEngine.kt:207`) and screenshots (`VisionEngine.kt:105`), not
just chat prompts.

### 3. Merge-order constraint

**PR #36 must not merge ahead of the redaction work.** The crash log ships a Share button
that dumps `Throwable.message` plus a 16k stack trace verbatim — `Throwable.message` is
exactly the carrier in finding 1. The `5b2c843` commit addresses capture-time redaction;
verify it covers the Share path before merging.

### 4. Map #25 contained a wrong premise

The map asserted that `executeWithFallback` (`LLMProviderFactory.kt:104`) ships the
cross-provider fallback. It has **zero call sites** and has never run. The live substitution
is `getActiveProvider` (`LLMProviderFactory.kt:92-102`), reached from all 8 LLM entry
points, and it triggers on a *missing key* rather than on failure. Map body now corrected.

### 5. Smaller bugs found, recorded on tickets, not fixed

- `DurationParser.kt` mis-parses `2m59.56s` as 176s — wrong `Retry-After` waits (#30).
- `streamComplete` is fake: it calls `complete()` first (`OpenAIProvider.kt:89-104`), so
  nothing actually streams today (#33).
- #28's safety net at `LLMProviderFactory.kt:147` catches nothing, because `streamComplete`
  returns a cold Flow — it must be Flow operators (#33).
- The unit-test CI (`b5dbc5c`) lives on the unmerged `implement-crash-log` branch, **not**
  `main`, so the test suite is not a merge gate yet (#23).

## Remaining work, in dependency order

14 issues still open, counting #46 filed for the security fix. `blocked_by` below is
GitHub's native issue-dependency state.

### Ready now, no blockers

- **#31** Chat error surfacing (`wayfinder:prototype`) — blocks #34 and #43. Must build on
  #33 (partial-text-then-error is a distinct render state; `NetworkErrorFormatter` retires
  into this ticket; agent row inserts lazily on first chunk), #40 (details block shows
  reconstructed allowlisted fields, never the raw body) and #30 (retry 1 silent, retry 2
  visible via `AgentState.Thinking`).
- **#32** Connection testing (`wayfinder:prototype`) — blocks #34. Cannot lean on
  `isAvailable()` at all (finding 2). Inherits the sole benchmark writer now that
  `updateLatencyBenchmark` dies with `getFallbackChain` (#39). Retry is off here (#30).
- **#24** Assemble the Grant-all spec and post it to issue 15 — **now unblocked**, both
  #22 and #23 closed. This is the last child of map #18.
- **#37** `@Embedded DeviceMetadata` refactor — stacks on PR #36's branch. The stated
  acceptance criterion is verifying Room emits the *same twelve column names*, because
  `DatabaseModule.kt:44` sets `.fallbackToDestructiveMigration()` and a mismatch silently
  drops the user's database rather than failing loudly.
- **#42** CI coverage gaps — stacks on PR #36's branch. Suggested order in the ticket:
  Robolectric migration test, lint + baseline, unsigned `assembleRelease`. The manual device
  smoke test and the `androidTest` source set cannot be done headless.
- **#46** Strip raw response bodies from exceptions in all 11 cloud providers. This is
  finding 1 below, filed as its own ticket so it can be claimed and closed like the rest.
  It touches `ClaudeProvider.kt`, which PR #38 also touches. **PR #38 has not merged** — its
  commits exist only on `JMAN730/to-spec-branch-name`. Either wait for #38 to merge and
  branch from `main`, or branch from `JMAN730/to-spec-branch-name` now. Branching from
  `main` today conflicts at merge time, or silently drops #38's model-ID migration in a
  rebase.

### Blocked

- **#43** AgentLoop response to each error category — blocked by #31 (#30 now closed).
- **#34** Assemble the API reliability spec — blocked by #31, #32, #43.
- **#15** Grant all permissions feature — implementation, wants #24's spec first.
- **#17** crash log — closes when PR #36 merges.
- **#16 / #35** Claude catalog — close when PR #38 merges. #35 is the binding spec (20 user
  stories); #16's scope section is superseded by it.
- **#25**, **#18** — maps, close when their children do. #18 needs only #24.

### One open question for you

**#34 is titled "Assemble the API reliability spec and post upstream."** `AGENTS.md`
forbids writing to `yashab-cyber/opendroid`. Map #25's Destination says the spec is posted
as a comment on upstream issue 13, and notes you have merge rights upstream — so the map and
the agent rules disagree. Nothing has been posted upstream. Decide before #34 runs.

## How to resume

Run one agent per ticket, but **serially or two at a time**, not ten — the parallel load is
what killed this session three times. Each agent should:

1. Read `AGENTS.md` and `docs/agents/issue-tracker.md`.
2. Pass `--repo JMAN730/opendroid` on every `gh issue` and `gh pr` call. Not on `gh api`,
   which has no such flag and exits with an unknown-flag error — put the repository in the
   endpoint path there instead (`repos/JMAN730/opendroid/...`).
3. Post its findings comment and close its issue **before** polishing, so an interruption
   cannot lose the result. Commit and push incrementally for code tickets.
4. Not edit map bodies — do those centrally, to avoid concurrent-edit races.
