# Macros and execution history

Saved macros run as an ordered list of actions through the same action dispatcher used by agent plans. Parameters may reference an earlier step with `$stepId` (the legacy `$$stepId` form is also accepted). Execution stops at the first failed step. A configured fallback action is attempted at most once for that step; a fallback failure is still a macro failure.

The System Logs screen offers an explicit **Save completed task as macro** action. It is available only for a task whose recorded steps all succeeded. The saved representation uses deterministic step IDs, order numbers, and sorted parameter keys so equivalent history produces stable JSON.

## Privacy and retention

- Execution history and saved macros stay in the app's local Room database. They are not uploaded by the macro recorder.
- History remains until the user clears it from System Logs or uninstalls the app; this feature does not add an automatic retention period.
- The recorder sanitizes parameter keys and values before serialization. API keys, access/auth tokens, bearer credentials, passwords, secrets, credential fields, and recognized provider-token formats are replaced with `[REDACTED]` and are never copied into a recorded macro.
- Email parameters are redacted as a whole. A recorded macro may therefore need its redacted values replaced before it can perform that action.
- Users should clear execution history when it is no longer needed, especially on shared or backed-up devices. Clearing history does not delete macros that were already saved; delete those separately from the Macros screen.
