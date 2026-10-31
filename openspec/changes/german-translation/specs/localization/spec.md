# Spec Delta

## Purpose

Defines which languages the app's user interface is offered in, how the language is selected, and the completeness and consistency guarantees every translation must keep, so German-speaking users get a fully German UI and new texts cannot ship untranslated.

## ADDED Requirements

### Requirement: German user interface
The app SHALL show its entire user interface in German when the effective app language is German in any region (for example de-DE, de-AT, de-CH), and in English for every other language. English SHALL remain the default and fallback language.

#### Scenario: German device language
- **WHEN** the device language is German (Germany) and the user opens the photo grid, Settings, Albums or the onboarding
- **THEN** every label, button, dialog, banner, error message and content description is shown in German

#### Scenario: Regional German variant
- **WHEN** the device language is German (Austria) or German (Switzerland)
- **THEN** the app shows the same German texts as for German (Germany)

#### Scenario: Unsupported language falls back to English
- **WHEN** the device language is French
- **THEN** the app shows its English texts

### Requirement: Localized background texts
Upload notifications, their actions, the upload notification channel name and description, and texts shown by the share target SHALL use the effective app language at the time they are shown or the channel is (re)created.

#### Scenario: Upload result notification in German
- **WHEN** the app language is German and a background upload finishes with failures
- **THEN** the result notification title, text, failure reasons and the retry action are German

#### Scenario: Channel name follows the language
- **WHEN** the app language changes from English to German and a new upload starts
- **THEN** the upload notification channel is shown with its German name in the system notification settings

### Requirement: Per-app language selection
The app SHALL declare its supported languages (English and German) to the system so that the user can choose the app language independently of the device language in the Android per-app language settings.

#### Scenario: App listed in per-app language settings
- **WHEN** the user opens the system settings for app languages
- **THEN** the app is listed and offers English and German (plus the system default)

#### Scenario: Choosing German for the app only
- **WHEN** the device language is English and the user selects German for this app
- **THEN** the app UI switches to German while other apps stay English, and screen state such as the current selection is kept

### Requirement: Complete translations
Every translatable text, plural and plural quantity of the default language SHALL have a German translation, and the German resources SHALL contain no texts that do not exist in the default language. The build checks SHALL fail when a text is missing or extra.

#### Scenario: New string without translation
- **WHEN** a change adds an English text without a German translation
- **THEN** the unit tests and Android lint fail and name the missing text

#### Scenario: Plural quantity missing
- **WHEN** an English plural has the quantities one and other but its German translation has only other
- **THEN** the unit tests fail and name the plural and the missing quantity

#### Scenario: Obsolete German text
- **WHEN** an English text is removed but its German translation remains
- **THEN** the unit tests and Android lint fail

### Requirement: Placeholder parity
Each German text SHALL contain exactly the same format placeholders as its English original (same argument positions and conversion types, each as often), so that formatting never fails or shows wrong values in German. Positional placeholders MAY appear in a different order in German.

#### Scenario: Matching placeholders
- **WHEN** the English text "Photo %1$d of %2$d, taken on %3$s" is translated
- **THEN** the German text contains %1$d, %2$d and %3$s, and formatting it with two numbers and a date succeeds

#### Scenario: Wrong conversion type
- **WHEN** a German text uses %1$s where the English text uses %1$d
- **THEN** the unit tests fail and name the text

#### Scenario: Every German text formats
- **WHEN** every German text and every quantity of every German plural is formatted with arguments of its declared types
- **THEN** no formatting error occurs

### Requirement: Non-translatable texts
Texts that must be identical in every language (the app's brand name, example server URLs and folder placeholders, and pure format templates without words) SHALL be marked as not translatable and SHALL NOT have a German copy.

#### Scenario: Brand name stays the same
- **WHEN** the app language is German
- **THEN** the app name is shown as "Kröhnle's Image Management"

#### Scenario: Copied non-translatable text
- **WHEN** a German resource file contains a text that is marked as not translatable
- **THEN** the unit tests and Android lint fail

### Requirement: Consistent German wording
German texts SHALL address the user informally ("du", lowercase) throughout, never formally ("Sie", "Ihnen", "Ihr"), and SHALL use the project glossary consistently. Removing local photos (system trash) and deleting on the server SHALL stay distinguishable in German ("entfernen" / "Papierkorb" versus "löschen").

#### Scenario: Informal address
- **WHEN** a German text addresses the user, for example the hint to log in again
- **THEN** it uses "du" / "dein" and contains no formal "Sie", "Ihnen" or "Ihr" in the middle of a sentence

#### Scenario: Formal address is rejected
- **WHEN** a German text contains "Bitte prüfen Sie die Serveradresse"
- **THEN** the unit tests fail and name the text

#### Scenario: Local removal is not called deleting
- **WHEN** the user opens "Remove uploaded photos from device" in German
- **THEN** the texts speak of "entfernen" and "Papierkorb", while the album photo deletion on the server speaks of "löschen"

### Requirement: No user-facing text outside string resources
Every text the app shows to the user SHALL come from a localized string resource. Raw exception messages, server or library messages and hardcoded literals SHALL NOT be shown.

#### Scenario: Login flow fails in German
- **WHEN** the app language is German and the Nextcloud browser login fails or times out
- **THEN** Settings shows a German error message from the app's resources, not the login library's message

#### Scenario: Manual configuration incomplete
- **WHEN** the app language is German and the user saves the manual WebDAV configuration with an empty field
- **THEN** a German error message is shown

#### Scenario: Local photos cannot be loaded
- **WHEN** the app language is German and loading the local photos fails
- **THEN** the photo grid shows a German error message and never the exception text

#### Scenario: Hardcoded literal is rejected
- **WHEN** a UI source file sets a content description, text or error to a string literal containing words
- **THEN** the unit tests fail and name the file and line
