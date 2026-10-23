# BLEScoreboard Remote Control (Android & Wear OS App)

The Android port of [BLEScoreboard](https://github.com/gerardoqm/BLEScoreboard), the iPhone and Apple Watch remote for the custom ESP32-S3 HUB75 LED matrix scoreboard ([scoreboard_BT_ESP32S3](https://github.com/gerardoqm/scoreboard_BT_ESP32S3)). Built with **Kotlin**, **Jetpack Compose**, the Android **Bluetooth LE** stack and the **Wear OS Data Layer**. It speaks the same Bluetooth protocol as the iOS app, so the same boards work with either phone.

## ✨ Key Features

- **Find Scoreboards Screen:** Scans with an animated radar and lists nearby scoreboards. Link to your own board to avoid interference at multi-court venues.
- **English & Spanish:** Every text, the panel alerts and the spoken announcements follow the language picked with the globe button (Automatic follows the phone). Names left at their default ("HOME" / "LOCAL") switch language too. The LED panel font is ASCII-only, so accents are stripped on the panel (`Águilas` shows as `AGUILAS`).
- **Board Renaming:** Give each board its own name (1-20 plain characters). The board saves it and restarts, and the app relinks on its own.
- **Panel Colors:** Set the color of the timer (running and stopped), the period, each team's name and score, and the start alerts, with a live LED preview. A switch on each row hides that element on the panel. Pick the emoji shown with goal alerts. The board saves colors and visibility, and the app resends them on every connection.
- **Emoji in team names:** Up to two emoji per team are drawn on the panel as 10 × 10 pictures (firmware with protocol 7+).
- **Alerts and Sound:** For each event (goal, time left, match start, period start, period end, half time, match end) choose audio, the phone banner and the panel separately. Goals and the time left are spoken with Android text-to-speech in the app's language; whistles and horns are generated in code. Everything plays wherever the phone plays media, so a paired Bluetooth speaker works.
- **Simulated Board:** The bottom of the match screen shows a simulated panel drawn with the firmware's own layout and 3 × 5 font: scores, clock, period, scrolling names, your colors and the alerts. **Try the simulated board** runs it without a board; with a board linked it shows whether the two clocks are in sync.
- **Landscape scoreboard:** Turn the phone sideways on the match screen and the simulated panel fills the screen with the controls below; the screen stays on, so the phone can serve as the scoreboard.
- **Board Firmware over Bluetooth:** Shows the board's firmware version and updates it from the phone (challenge-response with HMAC-SHA256, CRC-32 check, progress bar on the panel).
- **The phone owns the clock:** The countdown is measured against the moment it will reach zero, so it stays right in the background. If the board drops, the clock keeps running and the app relinks the same board when it is back.
- **Match notification:** While a match is on, an ongoing notification shows the score, period and a live countdown (Android's counterpart of the iOS Live Activity). A foreground service keeps the link, the clock and the announcements alive with the screen off.
- **Wear OS app:** Add or subtract points, change the period and start or pause the clock from your wrist. The watch follows the phone and taps with a banner for kickoffs, goals, half time, time left and the final whistle.

## 📲 Build and install

### Requirements
- **Android Studio** (Ladybug or newer) with the Android SDK 35
- A phone with **Android 8.0+** and Bluetooth LE
- Optional: a **Wear OS 3+** watch paired with the phone

### Steps
1. Clone the repository:
   ```bash
   git clone https://github.com/gerardoqm/BLEScoreboard-Android.git
   ```
2. Open the folder in Android Studio and let Gradle sync.
3. Optional, for firmware updates from the phone: run `tools/bundle-firmware.sh [path to scoreboard_BT_ESP32S3]` (needs `arduino-cli` and the firmware's `secrets.h`). It writes `app/src/main/assets/firmware/`, which is git-ignored because it contains your Wi-Fi and update passwords. Never commit it, and treat a build that includes it as private.
4. Run the **app** configuration on your phone. Allow Bluetooth (and notifications) when asked.
5. For the watch, run the **wear** configuration on the watch from the **same** Android Studio. The Wear Data Layer only connects apps that have the same application ID (`com.gerardoqm.blescoreboard`) **and the same signing key**, so install both from the same machine (or sign both with the same release key).

Unit tests for the protocol, panel text, match rules and firmware transfer run on the JVM:
```bash
./gradlew :core:test
```

> 🔐 **Permissions:** `BLUETOOTH_SCAN` (declared `neverForLocation`) and `BLUETOOTH_CONNECT` on Android 12+, location on Android 11 and older (Android requires it for Bluetooth scans there), `POST_NOTIFICATIONS` on Android 13+ for the match notification and alerts, and a `connectedDevice` foreground service.

## 🏷️ Versions

The app version and build number live in `gradle.properties` (`appVersionName`, `appVersionCode`) and are shared by the phone and watch apps. Bump **patch** for fixes, **minor** for compatible features and **major** for breaking changes, and raise the build number for every build you install. The board firmware has its own version in the firmware repository; the app offers an update when the firmware it carries is newer than the board's. See [CHANGELOG.md](CHANGELOG.md).

## 🗂️ Project layout

| Module | What it holds |
| :--- | :--- |
| `core` | Plain Kotlin, no Android: the Bluetooth packets and telemetry parser (`Protocol.kt`), the 80 × 40 panel renderer and LED font (`Panel.kt`), panel-safe text, ordinals and emoji handling (`Texts.kt`), the firmware transfer state machine with CRC-32 and HMAC (`Firmware.kt`), half time and time-left rules and the watch message keys (`MatchRules.kt`). Unit-tested. |
| `app` | The phone app. `ScoreboardManager` is the port of the iOS `AdvancedBLEManager`; `ble/BoardConnection` wraps one GATT connection; `ui/` holds the Compose screens. |
| `wear` | The Wear OS app: `WatchModel` (Data Layer) and the Compose for Wear OS screen. |

## 🧠 How it maps to the iOS app

| iOS | Android |
| :--- | :--- |
| CoreBluetooth (`CBCentralManager`) | `BluetoothLeScanner` + `BluetoothGatt`, with an operation queue (Android runs one GATT operation at a time) |
| The board's 185-byte MTU is negotiated by iOS | The app calls `requestMtu(185)`; without it every write is cut at 20 bytes |
| WatchConnectivity messages + application context | Wear Data Layer: `MessageClient` for live messages, a `DataClient` item the watch reads when it wakes (same keys: `h`, `a`, `p`, `r`, `t`, `l`, `m`, `s`, `hn`, `an`, `lang`, `ts`, `rc`, `sync`) |
| Live Activity / Dynamic Island | Ongoing notification with a chronometer countdown, from a foreground service |
| Scheduled local notifications for half time and full time | The foreground service and a partial wake lock keep the clock running; alerts become notifications when the app is in the background |
| `AVSpeechSynthesizer`, `AVAudioPlayer`, route picker | `TextToSpeech`, `AudioTrack` with audio focus ducking, Bluetooth settings shortcut |
| String catalogs | `values/strings.xml` and `values-es/strings.xml`; AppCompat per-app language |

## 📡 Bluetooth protocol

The byte-level protocol (STATE, CONFIG and OTA characteristics, telemetry, commands `0x01`-`0x08`, OTA opcodes) is documented in the [firmware repository README](https://github.com/gerardoqm/scoreboard_BT_ESP32S3#-bluetooth-le-protocol). It is implemented in `core/src/main/kotlin/.../Protocol.kt` and `Firmware.kt`. Keep this app, the iOS app and the firmware in sync when it changes, and bump `EXPECTED_PROTOCOL_VERSION` together with the firmware's `PROTOCOL_VERSION`.

```
Wear OS watch ──(Data Layer)──► Android phone ──(BLE: STATE / CONFIG / OTA)──► ESP32-S3 ──(HUB75)──► 80x40 LED panel
```
