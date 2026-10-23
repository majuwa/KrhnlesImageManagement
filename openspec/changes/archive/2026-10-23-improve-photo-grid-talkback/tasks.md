## 1. Strings and model

- [x] 1.1 Add to `strings.xml`: `cd_photo_position_taken_on` ("Photo %1$d of %2$d, taken on %3$s"), `cd_photo_position_date_unknown` ("Photo %1$d of %2$d, date unknown"), `cd_photo_position` ("Photo %1$d of %2$d"), `cd_photo_state_uploaded` ("%1$s, already uploaded"), `date_unknown` ("Unknown date"), `action_select_photo`, `action_deselect_photo`, `action_select_day`, `action_deselect_day`. No new plural. Grep all of `res/` and tests, then remove `cd_already_uploaded`; keep `cd_selected` / `cd_not_selected`
- [x] 1.2 `model/Photo.kt`: add `Photo.UNKNOWN_DATE` (`LocalDate.EPOCH`); `MediaRepository`: map null (`isNull`) / 0 `DATE_TAKEN` to it (no zone conversion; negative = real pre-1970 date); one `LocalDate.isKnownDate()` helper checks the sentinel; `ShareIntentParser` unchanged (today is known); unit-test the mapping (null, 0, negative) and pin the upload folder of unknown / pre-1970 dates in `UploadBatchTest`

## 2. Shared description helper

- [x] 2.1 Add `photoPositionDescription(position, total)` composable helper (`cd_photo_position`) for remote grids and viewer

## 3. Local grid semantics (`ui/photogrid/PhotoGridScreen.kt`)

- [x] 3.1 Make `PhotoItem`, `DateHeader`, `PhotoGrid` `internal`; compute the date string once per date with `remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale) }`, `locale = LocalConfiguration.current.locales[0]`; unknown date -> `date_unknown` for the header
- [x] 3.2 `PhotoItem`: `toggleable(isSelected, role = Role.Checkbox)` + `semantics { contentDescription (position/date or date unknown); stateDescription (+uploaded); onClick(label) { onClick(); true } }`; no `selected`, no redundant `mergeDescendants`; verify the labelled action wins and fires the callback once
- [x] 3.3 `AsyncImage`, uploaded badge and selection icon `contentDescription = null`
- [x] 3.4 `DateHeader`: same pattern (`stateDescription`, day labels), `heading()`, description from merged texts only; `Checkbox(onCheckedChange = null, Modifier.minimumInteractiveComponentSize())` (header height unchanged); date strings precomputed with `remember(photosByDate.keys, dateFormatter, unknownDateText)`

## 4. Remote grids and viewer

- [x] 4.1 Replace `contentDescription = photo.displayName` in `AlbumDetailScreen` (`PhotoGridItem` becomes `internal`, takes position/total), `BlurReviewScreen`, `DuplicateReviewScreen`, `FullscreenViewerScreen` with `photoPositionDescription`

## 5. Tests

- [x] 5.1 `ui/PhotoGridAccessibilityTest.kt` (Robolectric + Compose; `LocalAsyncImagePreviewHandler` or fake `ImageLoader` so `AsyncImage` has no side effects): item unselected / selected / uploaded / selected+uploaded (description, state description, `ToggleableState.On/Off`, `Role.Checkbox`, click label)
- [x] 5.2 Toggling a hoisted state changes state and label but not the description (not "description/label"); `performSemanticsAction(SemanticsActions.OnClick)` fires the callback exactly once for items and headers
- [x] 5.3 Position scenario (a 3-item day rendered via `PhotoGrid` yields "Photo 1/2/3 of 3, taken on ..."); unknown-date item and header; `@Config(qualifiers = "de")` German date and locale-change re-render
- [x] 5.4 Header: merged texts contain "1 photo" / "3 photos"; On/Off state strings and day labels; heading; exactly one checkbox/clickable node in the merged tree; activating header selects/deselects the day in a hoisted grid
- [x] 5.5 Click-node count asserted on the merged tree; filename absence asserted on the unmerged tree for both `ContentDescription` and `Text`
- [x] 5.6 Grid test: `PhotoGrid` with two days within one screen (or `performScrollToNode`); headers and items reachable, counts match
- [x] 5.7 Remote: album-detail `PhotoGridItem` description "Photo N of M" without filename; `photoPositionDescription` unit test; node assertions in `PhotoDeletionOutcomeUiTest` for the duplicate review ("Photo 1/2 of 2" within the group), blur review and `FullscreenViewerScreen` ("Photo 2 of 2", page+1), each also asserting the file name is absent
- [x] 5.8 `NoFilenameContentDescriptionRegressionTest`: scan of `contentDescription` / `stateDescription` right-hand sides for `displayName` / `href` over all `ui/**` Kotlin files via `krhnles.moduleDir`, plus matcher self-tests (templates, `stringResource` args, `if`); `RobolectricAnalysisDispatcherRegressionTest` ignores KDoc / block-comment lines
- [ ] 5.9 MANUAL: TalkBack check on a device/emulator (heading + checkbox announcement, duplicated state, uploaded wording); adjust and document the result. Left unchecked, cannot be automated

## 6. Documentation

- [x] 6.1 `docs/architecture.md`: accessibility section (toggleable pattern, labelled real action, decorative children, locale-aware date, unknown-date sentinel, source-scan guard, tests)
- [x] 6.2 `README.md`: TalkBack support note
- [x] 6.3 `CLAUDE.md`: short accessibility convention

## 7. Verification (CLAUDE.md "After Every Code Change")

- [x] 7.1 `./gradlew test` passes
- [x] 7.2 `./gradlew spotlessCheck` passes
- [x] 7.3 `./gradlew detekt` has no new issues (otherwise ask the user)
- [x] 7.4 `./gradlew lintDebug` passes with zero issues, no baseline, no suppressions
- [x] 7.5 `./gradlew jacocoTestReport` generated
