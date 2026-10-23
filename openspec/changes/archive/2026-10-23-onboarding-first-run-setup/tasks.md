## 1. Persistence and start decision

- [x] 1.1 Add `OnboardingRepository` to `data/Repositories.kt` and `data/OnboardingStore.kt` (DataStore `app_preferences`, key `onboarding_completed`, internal `DataStore<Preferences>` constructor, `IOException` -> unset, corruption handler for production)
- [x] 1.2 Add `fakes/FakeOnboardingRepository`
- [x] 1.3 Add pure `isExistingUser` / `resolveStartDestination` and `StartupViewModel` (`first()` reads, timeout fallback to Photos, silent `markCompleted()` for existing users, default-factory creatable)
- [x] 1.4 Gate `KrhnlesApp` on the resolved route (`rememberSaveable`, neutral background while unresolved), add `ROUTE_ONBOARDING` and `NavController.completeOnboarding`, share path untouched
- [x] 1.5 Exclude `datastore/app_preferences.preferences_pb` in `backup_rules.xml` and both sections of `data_extraction_rules.xml`

## 2. Onboarding UI

- [x] 2.1 `OnboardingViewModel` (page in `SavedStateHandle`, `finish(exit)` with `Job` guard released in `finally`, persist-then-exit, rethrow `CancellationException`, default-factory creatable)
- [x] 2.2 `OnboardingScreen`: Overview and Connect pages, Skip on both, Next, "Connect now", `BackHandler` only on page 1, heading and pane-title semantics
- [x] 2.3 Strings in `strings.xml` (`onboarding_*`), no hardcoded text

## 3. Tests

- [x] 3.1 `OnboardingStoreTest` (default false, persists, corrupt file -> false, read IOException -> false)
- [x] 3.2 Truth-table test for `isExistingUser` / `resolveStartDestination`; `StartupViewModelTest` (fresh, upgrade sets flag, corrupted upgrade, completed, store failure, timeout fallback)
- [x] 3.3 `OnboardingViewModelTest` (page + restore, persist-then-exit ordering, re-entry guard, guard released after failure, cancellation not swallowed)
- [x] 3.4 Add the new ViewModels to `ViewModelFactoryRegressionTest`
- [x] 3.5 Robolectric Compose `OnboardingScreenTest`: both pages, semantics, Skip on both, Next, Connect now, Back on page 1, Back on page 0 exits without persisting
- [x] 3.6 Navigation test: `completeOnboarding` back stacks (Skip: photos root; Connect: photos, settings; Back from settings lands on photos)
- [x] 3.7 `BackupRulesTest` asserting (domain, path) pairs for credentials DataStore, `krhnles_secure.xml` and `app_preferences` in all three sections (names from constants)
- [x] 3.8 Review fixes: finish timeout + `done` latch tests, single-snapshot startup read, non-null `SavedStateHandle` with constructor/recreation tests, `KrhnlesAppTest` (unresolved, restored route, no bottom bar, Back on page 0)

## 4. Documentation and verification

- [x] 4.1 Update `README.md`
- [x] 4.2 Update `docs/architecture.md`
- [x] 4.3 Update `CLAUDE.md`
- [x] 4.4 `./gradlew test spotlessCheck detekt lintDebug jacocoTestReport` exits 0
