#!/bin/bash
# Builds the board firmware and puts the image into the app, so the app can update boards over Bluetooth.
#
# Usage: tools/bundle-firmware.sh [path to the scoreboard_BT_ESP32S3 folder]
#
# app/src/main/assets/firmware/ gets two files:
#   scoreboard.bin  the firmware image
#   firmware.json   its version (from FW_VERSION_MAJOR/MINOR/PATCH in the sketch) and the update key (OTA_PASSWORD from
#                   secrets.h), which the app uses to prove itself to the board, so nobody has to type a password.
#
# The image and the json contain your Wi-Fi and update passwords, so the folder is git-ignored: never commit it.
# Run this before building the app whenever the firmware changes.
set -euo pipefail

FIRMWARE_DIR="${1:-$HOME/Documents/Arduino/scoreboard_BT_ESP32S3}"
APP_FIRMWARE_DIR="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/firmware"
SKETCH="$FIRMWARE_DIR/scoreboard_BT_ESP32S3.ino"
SECRETS="$FIRMWARE_DIR/secrets.h"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

[ -f "$SECRETS" ] || { echo "Missing $SECRETS (copy secrets.h.example)"; exit 1; }

# PSRAM stays disabled: the firmware does not use it, and a board with a faulty PSRAM restarts in a loop at power-up when it is enabled.
arduino-cli compile \
  --fqbn "esp32:esp32:esp32s3:FlashSize=16M,PartitionScheme=app3M_fat9M_16MB,PSRAM=disabled,CDCOnBoot=cdc,UploadMode=default" \
  --build-path "$BUILD_DIR" "$FIRMWARE_DIR"

mkdir -p "$APP_FIRMWARE_DIR"
cp "$BUILD_DIR/scoreboard_BT_ESP32S3.ino.bin" "$APP_FIRMWARE_DIR/scoreboard.bin"

python3 - "$SKETCH" "$SECRETS" "$APP_FIRMWARE_DIR/firmware.json" <<'PY'
import json, re, sys
sketch, secrets, out = sys.argv[1:4]
src = open(sketch).read()
def number(name):
    m = re.search(r'^#define\s+' + name + r'\s+(\d+)', src, re.M)
    if not m: sys.exit("Could not read " + name + " from the sketch")
    return m.group(1)
version = ".".join(number(n) for n in ("FW_VERSION_MAJOR", "FW_VERSION_MINOR", "FW_VERSION_PATCH"))
m = re.search(r'^#define\s+OTA_PASSWORD\s+"((?:[^"\\]|\\.)*)"', open(secrets).read(), re.M)
if not m: sys.exit("Could not read OTA_PASSWORD from secrets.h")
secret = json.loads('"' + m.group(1) + '"')
json.dump({"version": version, "secret": secret}, open(out, "w"))
print("Bundled firmware version " + version)
PY
echo "Image: $(wc -c < "$APP_FIRMWARE_DIR/scoreboard.bin") bytes"
