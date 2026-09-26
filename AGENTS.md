# Yumgo

Android app for tracking the groceries and food in your fridge. Users add items after shopping, and can also add things that were already in the fridge. Users are everyday people who want it to feel modern, simple, and premium.

It is **not** a complicated app. Before adding a screen, setting, dependency, or layer, ask whether a first-time user would miss it. Say no to accounts and any backend service other than PocketBase unless the user asks for them.

## Data

The database is **[PocketBase](https://pocketbase.io)**. Fridge items live in PocketBase collections, and the app reaches them only through the repository in `data/`, so screens and ViewModels never talk to PocketBase directly.

- Where PocketBase is hosted, its URL, and whether items are tied to a user account are **undecided**. Ask before choosing them, adding auth, or committing a server URL or admin credentials. Never put admin credentials in the app.
- Typing an item must still work offline (see Adding items), so the app keeps a local copy and syncs with PocketBase when it is reachable.
- Collection schema changes go in PocketBase migrations (`pb_migrations/`), checked into the repo.

## Adding items

There are four ways to add an item: a photo of the food, a receipt parser, AI recognition, or typing it in. The goal is fast entry, not perfect data.

- Typing must always work, offline and without permissions, because it is the fallback when the other methods fail.
- Photo, receipt, and AI results are guesses. The user must be able to fix them before they are saved to the fridge.
- The receipt parser and AI provider are **undecided**, whether on-device or cloud. Ask the user before choosing one, adding an API key, or sending photos off the device.
- To pick an existing photo, use the system Photo Picker, which needs no storage permission. To take a photo, ask for camera permission only when the user taps capture, and let them continue by typing if they deny it.

## Gotchas

- **Never change `applicationId`** (`com.dwk.yumgo`). A new ID installs a separate app and leaves the user's data behind in the old one.
- **AGP 9 has Kotlin built in.** Do not apply `org.jetbrains.kotlin.android` or use `kotlinOptions {}`; configure Kotlin through the `kotlin {}` block. Add dependencies through `gradle/libs.versions.toml`. The configuration cache is on, so build logic must stay compatible with it.
- **Navigation uses Navigation 3.** Do not add `navigation-compose`. Every destination is an `@Serializable` `NavKey` in `NavigationKeys.kt` and gets registered in the `entryProvider` in `Navigation.kt`. Load `navigation-3` for anything beyond adding a simple entry.
- **Edge-to-edge is always on:** `enableEdgeToEdge()` plus `targetSdk` 36. Each screen handles its own insets. Check that the bottom bar, FAB, and IME are not covered.
- **No DI framework.** ViewModels are built in `viewModel { }` factories that receive their repository. Keep this manual wiring unless the user asks for Hilt or Koin.
- **The user's items are the only valuable data.** Schema changes, in PocketBase collections or the local copy, must migrate existing records and never wipe them; never use destructive migration fallbacks or drop and recreate a collection.
- **E2E runs wipe app data.** `connectedDebugAndroidTest` uninstalls the app when it finishes. The attached device may be the user's own phone, which has real data on it. Run E2E on an emulator; use a physical device only when the user asks. Never run `adb uninstall` or `pm clear` on a physical device without asking.
- **UI is Jetpack Compose with Material 3 Expressive.** The Compose BOM pins `material3` 1.4.0, and that version lacks `MaterialExpressiveTheme`, `LoadingIndicator`, `ButtonGroup`, and the other Expressive components. Expressive needs an explicit `material3` 1.5 alpha version in `libs.versions.toml`, overriding the BOM. Its APIs require `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`. Before claiming an API is missing or inventing one, check the resolved version: `./gradlew -q :app:dependencyInsight --dependency androidx.compose.material3:material3 --configuration debugRuntimeClasspath`
- **Premium feel lives in the theme.** `YumgoTheme` in `theme/` is the single source for color, type, shape, and motion; it should wrap `MaterialExpressiveTheme`. Screens read from `MaterialTheme`, so do not hard-code these values in screens. Prefer Expressive components and motion over custom recreations.

## Testing

Write E2E tests for real user workflows. Do not write unit tests. The template's `app/src/test/` and its placeholder `MainScreenTest` are scaffold leftovers; do not extend them.

- E2E: `./gradlew :app:connectedDebugAndroidTest`. It needs a running emulator (see the gotcha above). No real workflow tests exist yet.
- Build gate: `./gradlew :app:assembleDebug`

## Skill map (`.agents/skills/`)

| Skill | Load when |
|---|---|
| `navigation-3` | Adding screens, back stack changes, deep links, or dialog/bottom-sheet destinations |
| `edge-to-edge` | Content overlaps system bars or the keyboard, or you are fixing insets |
| `adaptive` | Supporting tablets, foldables, or large screens |
| `navigation-event` | Custom back handling or predictive-back animations |
| `testing-setup` | Setting up E2E test infrastructure. Ignore its unit-test and screenshot-test guidance. |
| `android-cli` | Creating or running emulators, installing the app, taking screenshots, or inspecting UI from the shell |
| `android-profiler` | Jank, slow startup, or memory issues |
| `android-permissions-security`, `android-intent-security` | Adding a permission, exported component, or intent handling |
| `styles` | Only when the user asks for the Compose Styles API. It requires upgrading dependencies. |
| `camerax` | Building the in-app camera for food or receipt photos, or connecting ML Kit to it |

Do not load `kotlin-tooling-native-build-performance`. It covers KMP/iOS, and this is a single Android app.

## Maintain

Prune instead of append: when a gotcha stops being true, delete it. Each rule lives in one place; point to it everywhere else. The installed skills are vendored through `skills-lock.json`, so do not edit them by hand.
