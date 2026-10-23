## Why

A first-time user lands directly on the (empty) photo grid with only a "no server configured" hint and has to discover Settings on their own (issues #21 / #40, identical). A short, skippable two-page introduction that ends in a "Connect now" button removes the need for external documentation. Scope is deliberately minimal and follows the issue text: explain, then send the user to the existing Settings screen.

## What Changes

- New one-time onboarding at the route `onboarding` (no bottom bar) with exactly two pages: **Overview** (what the app does) and **Connect** (short server instructions and a "Connect now" button). **Skip** is available on both pages.
- "Connect now" persists the completed flag, then navigates so that the back stack is `photos` (root) with `settings` on top; Back from Settings lands on the photo grid. "Skip" persists the flag and shows the photo grid.
- A new `OnboardingStore` (own Preferences DataStore `app_preferences`, key `onboarding_completed`) behind an `OnboardingRepository` interface persists the flag. A read `IOException` (including a corrupt file) means "unset".
- Onboarding is shown iff the flag is unset AND the user is not an "existing user" (valid configuration, OR any stored server URL, OR `credentialState != Missing`). Existing users without the flag get it set silently.
- The start destination is resolved once by a pure `resolveStartDestination(...)` in a `StartupViewModel`, with a timeout fallback to the photo grid. While unresolved a neutral themed background is shown; the wrong destination is never shown.
- `app_preferences.preferences_pb` is excluded from cloud backup and device transfer, so a device restored without credentials shows onboarding again.
- Explicitly NOT in scope (deviations from the earlier 5-step draft): no extraction of composables from `SettingsScreen` / `PhotoGridScreen`, no photo-permission step (the grid keeps handling permissions), no in-flow login or base-folder step, no connection test in onboarding.

## Capabilities

### New Capabilities
- `onboarding`: when onboarding is shown, its two pages, skip / CTA / back behavior and resulting back stack, persistence, upgrade path, backup exclusion, start-destination resolution, accessibility and localisation.

### Modified Capabilities
<!-- none -->

## Impact

- Code: `KrhnlesApp.kt` (gated start destination, `onboarding` route), new `ui/onboarding/` (`OnboardingScreen`, `OnboardingViewModel`, `StartupViewModel`, `StartDestination.kt`), new `data/OnboardingStore.kt`, `OnboardingRepository` in `data/Repositories.kt`, `res/values/strings.xml` (only locale present), `res/xml/backup_rules.xml` and `data_extraction_rules.xml`.
- Docs: `README.md`, `docs/architecture.md`, `CLAUDE.md`.
- No new dependencies, permissions or network behavior.
