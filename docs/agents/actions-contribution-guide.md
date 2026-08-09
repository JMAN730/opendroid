# Contributing a new action

How to add or extend an action in `app/src/main/java/com/opendroid/ai/actions/`. This is the
on-ramp for outside contributors: the action tree is self-contained and doesn't touch the agent
core (planning, memory, the accessibility service). `docs/CONTRIBUTING.md` covers process (branch
naming, the PR test report); this doc covers the mechanics of wiring a new action in.

## The three places a new action touches

An action that doesn't exist yet in the schema and doesn't need new dependencies touches exactly
three files:

1. **A category class** under `actions/` — the `Action` implementation itself.
2. **`ActionSchema.kt`** (`app/src/main/java/com/opendroid/ai/core/agent/ActionSchema.kt`) — the
   entry that makes the planner aware the action exists, what params it takes, and whether it
   needs approval.
3. **A test** under `app/src/test/java/com/opendroid/ai/actions/`.

A new *category* (a whole new file like `FinanceActions.kt`) additionally needs a constructor
parameter and a `putAll(...)` line in `ActionDispatcher`. Adding one more action to an existing
category needs none of that — the category class's own `getActions()` is enough.

## 1. The action class

Every action implements the two-member `Action` interface
(`app/src/main/java/com/opendroid/ai/actions/base/Action.kt`):

```kotlin
interface Action {
    val name: String
    suspend fun execute(params: Map<String, String>, context: Context): ActionResult
}
```

Conventions used throughout the tree:

- `name` is the canonical, upper-snake-case action name (`"PAY_UPI"`, `"LOCK_DOOR"`) — it must
  match the `name` in the `ActionSchema.kt` entry exactly.
- Actions are grouped into category classes (`FinanceActions`, `SmartHomeActions`,
  `NotificationActions`, ...), one file per category, each `@Singleton @Inject constructor(...)`
  and exposing `fun getActions(): List<Action>`. Individual actions are usually `private class`
  (or `private inner class` when they need a dependency from the category, like
  `NotificationActions`) nested inside the category class.
- If an action needs to be testable without Robolectric, take its side-effecting dependency
  (a launcher, a composer, a controller) as a constructor parameter behind a small interface, and
  make the class non-private so a test in the same package can construct it directly with a fake.
  `CommunicationActions.SendEmailAction` (takes an `EmailComposer`) and
  `ToggleBluetoothAction` (takes a `controllerProvider`, `waitForState`, `launchIntent`) are the
  reference examples — both let tests substitute fakes and assert on exact success/failure
  wording without touching a real device.
- Return `ActionResult.Success`, `.Failure`, `.NeedsInput`, `.PendingUserAction`, or
  `.UserActionRequired` (see `app/src/main/java/com/opendroid/ai/actions/base/ActionResult.kt`) —
  never throw. `ActionDispatcher.safeExecute` catches exceptions and converts them to a generic
  `Failure`, but a handler that throws loses the specific, user-facing error message a `Failure`
  can carry.
- **Only report `Success` for a verified side effect.** Several closed tickets in this tracker
  were exactly this bug: an action returned `Success` because it *started* a flow (opened an app,
  launched an intent) without checking whether the flow actually completed (sent, toggled, dialed).
  If the action can't verify completion in-process, return `PendingUserAction` or
  `UserActionRequired` instead of `Success`, the way `ToggleBluetoothAction` waits for the
  observed adapter state before claiming success, and `SendEmailAction` only reports `Success` on
  a provider-confirmed `VERIFIED_SENT` outcome.
- Errors are short and user-facing (`"Couldn't open the food app. Try again?"`), not stack traces.
  Log the real exception via `Log.e` first, then return the friendly `Failure`.

## 2. `ActionSchema.kt`

Add an `ActionDefinition` under the right `// ── CATEGORY ──` banner comment in `ALL_ACTIONS`:

```kotlin
ActionDefinition(
    name = "SPLIT_BILL",
    description = "Splits a bill among people",
    params = listOf(
        ParamDefinition("totalAmount", ParamType.STRING, true, "Total bill amount"),
        ParamDefinition("people", ParamType.STRING, true, "Number of people or names"),
        ParamDefinition("description", ParamType.STRING, false, "Bill description")
    ),
    examples = listOf("split bill 1000 among 4", "split dinner bill"),
    category = ActionCategory.FINANCE
),
```

Notes:

- `category` must be one of the existing `ActionCategory` values; add a new one only for a
  genuinely new category of action, not per-action.
- `params` drives real validation: `ActionDispatcher.trySchemaExecution` calls
  `ActionSchema.validateParams`, which fills in `defaultValue`s and otherwise returns
  `NeedsInput` for anything `required = true` and missing — you don't hand-roll that
  null-check-and-ask-for-input logic in the action itself unless the action needs custom phrasing.
- Set `neverAutoApprove = true` for anything that moves money, is destructive, or is otherwise
  irreversible (see `PAY_UPI`, `LOCK_DOOR`) — this forces the approval modal even in Auto mode.
- `examples` should be a few realistic phrasings; they show up in prompts and docs, not just
  comments.

If `ActionSchema.isValid(name)` is true (i.e. you added the entry), `ActionDispatcher` finds and
executes your handler on the first pass — no alias or semantic-matching layer is needed. Aliases
in `ActionAutoMapper.kt` (`app/src/main/java/com/opendroid/ai/actions/ActionAutoMapper.kt`) exist
to catch LLM wording variants of *existing* canonical actions (`"CALL"` → `"MAKE_CALL"`); add an
entry there only if you expect the planner to frequently hallucinate a different name for your new
action, not as a routine step. Natural-language voice-command aliasing is a separate, unrelated
mechanism in `AliasResolver.kt` under `core/agent/` — don't confuse the two.

## 3. Wiring `ActionDispatcher`

If your action needs internet, add its schema `name` to `internetRequiredActions` in
`ActionDispatcher` — this applies whether the action lands in a new category or an existing one.
`ActionDispatcher` only runs its offline precheck for names in that set, so skipping this step
means the new handler runs with no connectivity guard.

The rest of this section is only needed if you're adding a whole new category file, not another
action in an existing one:

1. Add `private val yourActions: YourActions` to `ActionDispatcher`'s constructor.
2. Add `putAll(yourActions.getActions().associateBy { it.name })` in the `actionsMap` builder.

## 4. Tests

As of this writing, `FinanceActions`, `FoodShoppingActions`, `SmartHomeActions`, `MacroActions`,
and `NotificationActions` have **no** unit test coverage at all — everything else in this section
is aspirational for those categories until someone adds it (see the good-first-issues filed
alongside this guide).

Put the test in `app/src/test/java/com/opendroid/ai/actions/`, following one of the two patterns
already in the tree:

- **Robolectric, real context** — for actions that only need a `Context` and don't have external
  side-effecting dependencies worth faking. See `ToggleBluetoothActionTest`'s
  `@RunWith(RobolectricTestRunner::class)` + `ApplicationProvider.getApplicationContext()`.
- **Plain JUnit, fake collaborator** — for actions that take an injected interface (composer,
  controller, DAO). Construct the action directly with a fake implementing that interface and a
  `nullContext()`/`ContextWrapper` stand-in; no Robolectric needed. See `EmailActionTest`.

Minimum coverage for a new action:

- The success path, asserting on the exact `result.data` string (not just `result.success`) —
  wording changes are easy to miss otherwise.
- Each `required = true` param missing → `NeedsInput` from `ActionSchema.validateParams` (or the
  `ActionDispatcher.trySchemaExecution` path). `trySchemaExecution` returns `NeedsInput` before
  your handler ever runs, so test the schema/dispatcher path here, not the handler directly —
  a handler-level check would duplicate validation the schema already does and assert on
  behavior production dispatch never reaches.
- Any branch that distinguishes "app installed" vs "app not installed" fallback behavior, if the
  action launches an external app via `Intent`.
- If the action can only claim `Success` after observing a real state change (see the "only report
  Success" rule above), a test that the unverified/pending path does **not** return `Success`.

Run the suite with:

```bash
./gradlew testDebugUnitTest --tests "com.opendroid.ai.actions.*"
```

## Checklist

- [ ] `Action` implementation added to (or new) category class, `name` in upper-snake-case
- [ ] Side-effecting dependency taken as a constructor param behind an interface, if you want the
      action unit-testable without Robolectric
- [ ] `Success` returned only for a verified side effect, not just a launched intent
- [ ] `ActionDefinition` added to `ActionSchema.kt` under the right category banner, with accurate
      `required`/`defaultValue`, `neverAutoApprove` set if the action is destructive or moves money
- [ ] Internet-dependent action: schema `name` added to `internetRequiredActions` in
      `ActionDispatcher`
- [ ] New category only: constructor param + `putAll(...)` added to `ActionDispatcher`
- [ ] Unit test covering the success path and each required-param-missing case
- [ ] `./gradlew assembleDebug` and the actions test suite both pass
