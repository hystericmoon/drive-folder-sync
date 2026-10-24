#!/usr/bin/env bash
# Installs the minified release APK on the running emulator, opens it, visits every tab and checks
# it never crashes. Catches what only R8 (code shrinking) breaks, which the debug-build tests can't see.
set -euo pipefail
APK="$1"
PKG=io.github.hystericmoon.drivesync

fail() {
  echo "::error::$1"
  adb logcat -d -b crash || true
  adb logcat -d | grep -B2 -A40 "FATAL EXCEPTION" || true
  exit 1
}

alive() {
  adb shell pidof "$PKG" > /dev/null || fail "Release build is not running ($1)."
  if adb logcat -d -b crash | grep -q "$PKG"; then fail "Release build crashed ($1)."; fi
}

# Taps the on-screen element whose text is exactly $1, using the accessibility tree.
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb pull /sdcard/ui.xml ui.xml > /dev/null
  local xy
  xy=$(python3 - "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
for node in ET.parse("ui.xml").iter("node"):
    if node.get("text") == sys.argv[1] or node.get("content-desc") == sys.argv[1]:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
  [ -n "$xy" ] || fail "Couldn't find \"$1\" on screen."
  adb shell input tap $xy
  sleep 3
}

adb uninstall "$PKG" > /dev/null 2>&1 || true # the debug build from the tests has another signature
adb install "$APK"
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity"
sleep 10
alive "after launch"
for tab in Settings History Folders; do
  tap_text "$tab"
  alive "after opening $tab"
done
echo "Release build launched, opened every tab and stayed up."
