# Catch

[![Android CI](https://github.com/ahmadZMVD/Catch/actions/workflows/ci.yml/badge.svg)](https://github.com/ahmadZMVD/Catch/actions/workflows/ci.yml)
[![Release Multi-ABI APKs](https://github.com/ahmadZMVD/Catch/actions/workflows/release.yml/badge.svg)](https://github.com/ahmadZMVD/Catch/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**Catch** is an Android utility that identifies songs playing inside foreground video and social media apps (such as Instagram Reels, Telegram video messages, TikTok, and YouTube Shorts) with a single tap from the Quick Settings panel, without pausing foreground video playback.

When triggered, Catch collapses the Quick Settings panel using system accessibility actions rather than starting an Activity, invokes Google's contextual Circle to Search / Sound Search overlay over the active window, clicks the audio recognition action, waits for a stabilized track match, saves the recognized song to a local Room database, and dismisses the overlay automatically.

---

## Features

- **Non-Intrusive Quick Settings Trigger**: Dismisses the notification shade via `AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE` (API 31+) or `Intent.ACTION_CLOSE_SYSTEM_DIALOGS` (API 28-30) so the foreground media player retains window focus and never receives `onPause()`.
- **Multi-Window Accessibility Inspection**: Uses `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` to inspect all interactive `AccessibilityWindowInfo` layers on screen, allowing song results rendered inside `com.google.android.googlequicksearchbox` overlay windows to be parsed even while `rootInActiveWindow` belongs to the underlying social app.
- **Cascading Search Invocation**:
  1. Direct `IVoiceInteractionManagerService.showSessionFromSession` (`Omni`) Binder invocation across API 34-36 (4-parameter signature) and API 28-33 (3-parameter signature).
  2. Stationary `650ms` long-press gesture dispatched to the system navigation handle (`TYPE_NAVIGATION_BAR`).
  3. Intent fallback (`com.google.android.googlequicksearchbox.MUSIC_SEARCH` / `android.intent.action.MUSIC_SEARCH`) for Android 9-12 devices without Circle to Search support.
- **3-Tier Node Extraction & Stabilization**:
  - **Tier 1**: Matches explicit `sound_search_result_title` / `sound_search_result_artist` resource IDs.
  - **Tier 2**: Parses localized `"Song by ..."` / `"Track by ..."` and RTL Persian/Arabic bylines when resource IDs are obfuscated.
  - **Tier 3**: Spatial vertical-stack heuristic that pairs primary title text nodes with adjacent subtitle nodes while filtering out transient UI labels (`"Listening..."`, `"Play, sing, or hum a song"`, `"Translate"`, `"Try again"`).
  - Enforces a `3,200ms` minimum audio sampling window and a `250ms` result stabilization debounce before committing a track match and dispatching `GLOBAL_ACTION_BACK`.
- **One-Tap Download Search**: Tapping any captured song row or the `"Download (Search)"` notification action opens the default browser with a UTF-8 URL-encoded Google Search query appending the Persian download keyword (`<Song> <Artist> &#x062F;&#x0627;&#x0646;&#x0644;&#x0648;&#x062F;`, encoded as `+%D8%AF%D8%A7%D9%86%D9%84%D9%88%D8%AF`).
- **Local Room Library & Multi-Format Backup**: Stores full capture history locally via Room SQLite (`catch_song_library.db`) with instant search filtering, favorites, and export/restore support in JSON, CSV, and plain text formats.
- **Adaptive Bone Light & Obsidian Dark Themes**: Built with Jetpack Compose and Material 3, featuring configurable theme modes (`Bone Light`, `Obsidian Dark`, and `System`) and responsive window-insets handling across phones and tablets.

---

## Architecture

```text
app/src/main/java/com/example/
|-- CatchSongApplication.kt                  # Application graph wiring Room, DataStore, and state listeners
|-- MainActivity.kt                          # Edge-to-edge Compose host activity
|-- data/
|   |-- local/
|   |   |-- AppDatabase.kt                   # Room database configuration (catch_song_library.db)
|   |   |-- CapturedSongDao.kt               # Reactive Flow queries and CRUD operations
|   |   \-- CapturedSongEntity.kt            # Captured song table schema and display helpers
|   |-- preferences/
|   |   \-- UserPreferencesRepository.kt     # Jetpack DataStore preferences (theme, timeout, duplicate policy)
|   \-- repository/
|       \-- SongRepository.kt                # Repository layer and JSON / CSV / TXT archive serializer
|-- domain/
|   \-- interception/
|       |-- GoogleMusicNodeParser.kt         # 3-tier Accessibility node parser and JSON tree serializer
|       |-- InterceptionModels.kt            # Immutable node snapshots and multi-window environment models
|       \-- InterceptionStateMachine.kt      # Session state machine, sampling window, and watchdog timer
|-- notification/
|   |-- NotificationActionReceiver.kt        # BroadcastReceiver for notification download actions
|   \-- SongNotificationDispatcher.kt        # High-priority confirmation notifications and haptic pulses
|-- service/
|   |-- CircleToSearchInternalAudioEngine.kt # Coroutine pipeline coordinating shade collapse, summon, and match
|   |-- GoogleMusicAccessibilityService.kt   # AccessibilityService bridge and multi-window node traversal
|   |-- HiddenApiHelper.kt                   # VoiceInteractionSession Binder invocation helper
|   |-- SongCatchTileService.kt              # Quick Settings TileService implementation
|   \-- TileTriggerTrampolineActivity.kt     # Legacy fallback activity for shade collapse
|-- ui/
|   |-- CatchSongViewModel.kt                # MVVM state holder combining Room history and session telemetry
|   |-- SettingsBottomSheet.kt               # Appearance, engine timeout, backup/restore, and tree inspector UI
|   |-- SongHistoryScreen.kt                 # Main library screen, setup card, and bottom capture trigger
|   \-- theme/
|       |-- Color.kt                         # Bone Light and Obsidian Dark color tokens
|       |-- Theme.kt                         # Compose theme provider
|       \-- Type.kt                          # Plus Jakarta Sans and JetBrains Mono typography definitions
\-- util/
    |-- SystemPermissionHelper.kt            # System permission checks and deep-link intents
    |-- PersianDownloadBrowserLauncher.kt    # UTF-8 URL encoder and browser intent launcher
    \-- PlatformCompat.kt                    # Runtime compatibility helpers across API 28 through 36
```

---

## Compatibility

| Property | Supported Range |
| :--- | :--- |
| **Minimum SDK** | Android 9 Pie (`API 28`) |
| **Target / Compile SDK** | Android 16 (`API 36`) |
| **CPU Architectures (ABIs)** | `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`, plus `universal` |
| **16 KB Page Alignment** | Enabled (`jniLibs.useLegacyPackaging = false`) |
| **Google App Package** | `com.google.android.googlequicksearchbox` |

---

## Releases & Multi-ABI APKs

Every release build automatically generates five signed APKs under `app/build/outputs/apk/release/` (and publishes them to [GitHub Releases](https://github.com/ahmadZMVD/Catch/releases)):

- `Catch-v1.0.0-arm64-v8a.apk` (64-bit ARM devices)
- `Catch-v1.0.0-armeabi-v7a.apk` (32-bit ARM devices)
- `Catch-v1.0.0-x86_64.apk` (64-bit x86 tablets / emulators)
- `Catch-v1.0.0-x86.apk` (32-bit x86 targets)
- `Catch-v1.0.0-universal.apk` (Universal APK containing all 4 ABIs)

---

## Building and Testing

### Prerequisites

- JDK 17 or newer
- Android SDK Platform 36

### Build Commands

Build all multi-ABI debug or release APKs (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`, and `universal`):

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

### Running the Test Suite

The project includes unit and Robolectric tests covering the multi-second Circle to Search recognition pipeline, foreground Activity lifecycle preservation, watchdog timeout recovery across failure modes, 500-iteration Accessibility hierarchy fuzzing, Room database concurrency, and API 28-36 compatibility branches:

```bash
./gradlew :app:testDebugUnitTest
```

---

## Required Permissions and Setup

1. **Quick Settings Tile (`BIND_QUICK_SETTINGS_TILE`)**: Add the **Catch** tile to the system Quick Settings panel so song recognition can be triggered while another app is playing audio.
2. **Accessibility Service (`BIND_ACCESSIBILITY_SERVICE`)**: Enable **Catch Interceptor** in **Settings -> Accessibility -> Installed apps**. The service remains idle until a capture session is started from the Quick Settings tile or the in-app button.
   - *Note for Android 13+ (API 33+) sideloaded APKs*: If the Accessibility toggle is restricted by the system, open **Settings -> Apps -> Catch**, tap the top-right overflow menu, and select **Allow restricted settings** before enabling the service.
3. **Notifications (`POST_NOTIFICATIONS`)**: Required on Android 13+ to display the confirmation banner when a track is saved.
4. **Unrestricted Battery Usage (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)**: Prevents background process restrictions from delaying Quick Settings tile actions or Accessibility window callbacks.

---

## License

Released under the [MIT License](LICENSE).
