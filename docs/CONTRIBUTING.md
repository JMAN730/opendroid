# Contributing to OpenDroid

Thank you for your interest in contributing to OpenDroid! We welcome community contributions to help improve the agent's performance, stability, and compatibility.

## 🚀 Getting Started

1.  **Fork** the repository and clone it to your local machine.
2.  Set up the Android development environment (Android Studio, JDK 17+).
3.  Ensure the Android build runs successfully:
    ```bash
    ./gradlew assembleDebug
    ```

## 🛠️ Development Guidelines

### Code Style
*   Follow standard **Kotlin Style Guide** conventions.
*   Keep functions small, focused, and well-tested.
*   Ensure all actions implement the standard `Action` interface and handle error states gracefully (returning clean `ActionResult`s instead of crashing).

### Adding Actions
If you are implementing a new action:
1.  Add it to the corresponding action group class under `app/src/main/java/com/opendroid/ai/actions/`.
2.  Register the action inside the list returned by `getActions()`.
3.  Add aliases in `AliasResolver.kt` if the action is commonly triggered via basic voice commands.
4.  Add appropriate test coverage where applicable.

## 📥 Submitting Changes

1.  Create a descriptive branch name (e.g., `feature/sms-composer-fallback` or `bugfix/flashlight-toggle-state`).
2.  Commit your changes with clear messages detailing *why* the changes were made.
3.  Ensure the build completes without errors:
    ```bash
    ./gradlew build
    ```
4.  Submit a Pull Request (PR) describing the changes, testing performed, and any resolved issues.
