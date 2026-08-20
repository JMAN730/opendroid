# Macros, Routines and Execution History

Saved macros and habit routines run as an ordered list of actions through the same action dispatcher used by agent plans. Parameters may reference an earlier step with `$stepId` (the legacy `$$stepId` form is also accepted). Execution stops at the first failed step. A configured fallback action is attempted at most once for that step; a fallback failure is still a macro failure.

## Habit Routines to Macro Bridge

OpenDroid's `HabitRoutineEngine` continuously observes repeated daily sequences (e.g. Gmail $\to$ Calendar $\to$ Slack $\to$ Chrome on weekday mornings). When a pattern repeats $\ge 3$ times, it generates a suggested routine.
- **One-Click Approval**: Approving a suggested routine converts it into an active, scheduled macro in `MacroDao` with cron triggers (e.g., `0 9 * * 1-5` for weekdays at 9:00 AM).
- **Execution**: Scheduled macros execute sequentially via `ActionSequenceExecutor` and can be triggered on demand using `RUN_ROUTINE`.

## Saving Tasks from System Logs

The System Logs screen offers an explicit **Save completed task as macro** action. It is available only for a task whose recorded steps all succeeded. The saved representation uses deterministic step IDs, order numbers, and sorted parameter keys so equivalent history produces stable JSON.

## Privacy and Retention

- Execution history, habit event logs, and saved macros stay in the app's local Room database (`habit_events`, `habit_routines`, `macros`). They are not uploaded to remote servers.
- History remains until the user clears it from System Logs/Routines screen or uninstalls the app.
- The recorder sanitizes parameter keys and values before serialization. API keys, access/auth tokens, bearer credentials, passwords, secrets, credential fields, and recognized provider-token formats are replaced with `[REDACTED]` and are never copied into a recorded macro.
- Email parameters are redacted as a whole. A recorded macro may therefore need its redacted values replaced before it can perform that action.
- Users should clear execution history when it is no longer needed, especially on shared or backed-up devices. Clearing history does not delete macros that were already saved; delete those separately from the Macros screen.
