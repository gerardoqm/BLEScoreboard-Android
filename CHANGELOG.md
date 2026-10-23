# App changelog

Versions follow `major.minor.patch` (see the Versions section of the README). The number in brackets is the build.

## 1.0.0 (1)
First Android version, ported from the iOS app 1.4.0 (25) and speaking the board's protocol up to version 9.
- Find and link scoreboards, relink by itself after a drop, rename the board.
- Match screen: scores, clock owned by the phone, periods and total periods, unit name, brightness, team names with emoji.
- Panel colors and visibility, goal icon, start / period / half time / time left / period end / full time / goal alerts on the panel.
- Alerts and Sound page: audio (text-to-speech in English or Spanish, whistles and horns made in code), phone banner and panel for each event; time left announced at any minute from 1 to 15.
- Simulated board on the match screen and without a board; landscape scoreboard with the screen kept on.
- Board firmware update over Bluetooth (bundled with `tools/bundle-firmware.sh`).
- Ongoing match notification with a live countdown; the clock and announcements keep running in the background.
- Wear OS app: scores, period, start / pause and reset from the wrist, with taps and banners for the alerts.
- English and Spanish, switchable in the app.
