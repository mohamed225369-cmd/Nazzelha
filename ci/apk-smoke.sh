#!/usr/bin/env bash
set -euo pipefail
APK="$(realpath "$1")"
PACKAGE=com.clipshelf.app
OUT="$(realpath -m "$2")"
mkdir -p "$OUT"

capture() {
  adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>&1 || true
  adb logcat -d -b crash > "$OUT/crash.txt" 2>&1 || true
  cat "$OUT/crash.txt" || true
  adb shell dumpsys package "$PACKAGE" > "$OUT/package.txt" 2>&1 || true
  adb shell dumpsys activity activities > "$OUT/activities.txt" 2>&1 || true
  adb exec-out screencap -p > "$OUT/final-screen.png" 2>/dev/null || true
}
trap capture EXIT

adb wait-for-device
adb shell getprop > "$OUT/device-properties.txt"
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abilist | tr -d '\r' | tee "$OUT/abi.txt"
adb shell getconf PAGE_SIZE | tr -d '\r' | tee "$OUT/page-size.txt"
EXPECTED_PAGE_SIZE="${3:-0}"
if [[ "$EXPECTED_PAGE_SIZE" != 0 ]] && ! grep -qx "$EXPECTED_PAGE_SIZE" "$OUT/page-size.txt"; then
  echo "Expected page size $EXPECTED_PAGE_SIZE; emulator reports $(cat "$OUT/page-size.txt")"
  exit 1
fi
if ! grep -qw arm64-v8a "$OUT/abi.txt"; then
  echo "Emulator has no ARM64 ABI support; cannot validate this ARM64 APK."
  exit 1
fi
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard || true
adb logcat -c
adb install "$APK" 2>&1 | tee "$OUT/install.txt"
grep -x 'Success' "$OUT/install.txt"
adb shell pm path "$PACKAGE" | tee "$OUT/installed-path.txt"
grep -q '^package:' "$OUT/installed-path.txt"
adb shell am start -W -n "$PACKAGE/.MainActivity" | tee "$OUT/launch.txt"
grep -Eq '^Status:[[:space:]]+ok' "$OUT/launch.txt"

ui() {
  local label="$1" action="$2"
  for attempt in 1 2 3 4 5; do
    if adb shell uiautomator dump /sdcard/nazzelha-ui.xml >/dev/null 2>&1 &&
       adb pull /sdcard/nazzelha-ui.xml "$OUT/ui.xml" >/dev/null 2>&1; then
      if python3 - "$OUT/ui.xml" "$label" "$action" <<'PY'
import re, subprocess, sys, xml.etree.ElementTree as ET
path, label, action = sys.argv[1:]
nodes = [n for n in ET.parse(path).iter("node") if n.get("text") == label]
if not nodes:
    sys.exit(1)
if action == "tap":
    node = next((n for n in nodes if n.get("clickable") == "true"), nodes[0])
    x1,y1,x2,y2 = map(int,re.findall(r"\d+",node.get("bounds","")))
    subprocess.run(["adb","shell","input","tap",str((x1+x2)//2),str((y1+y2)//2)],check=True)
PY
      then return 0; fi
    fi
    sleep 2
  done
  echo "UI element not found: $label"
  return 1
}

ui 'Load link' assert
adb shell pidof "$PACKAGE" | tee "$OUT/startup-pid.txt"
grep -Eq '[0-9]' "$OUT/startup-pid.txt"
adb exec-out screencap -p > "$OUT/home.png"
ui 'Downloads' tap
ui 'Nothing queued yet.' assert
adb exec-out screencap -p > "$OUT/downloads.png"
ui 'Back to library' tap
ui 'Load link' assert
ui 'Settings' tap
ui 'Appearance · System' tap
ui 'Light' tap
ui 'Load link' assert
adb exec-out screencap -p > "$OUT/light.png"
ui 'Settings' tap
ui 'Appearance · Light' tap
ui 'Dark' tap
ui 'Load link' assert
adb exec-out screencap -p > "$OUT/dark.png"
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$PACKAGE/.MainActivity" | tee "$OUT/relaunch.txt"
grep -Eq '^Status:[[:space:]]+ok' "$OUT/relaunch.txt"
ui 'Load link' assert
adb exec-out screencap -p > "$OUT/relaunch.png"
adb shell pidof "$PACKAGE" | tee "$OUT/pid.txt"
grep -Eq '[0-9]' "$OUT/pid.txt"
adb logcat -d -b crash > "$OUT/crash.txt"
if grep -E "Process: $PACKAGE([, ]|$)" "$OUT/crash.txt"; then
  echo "Application crashed during smoke test."
  exit 1
fi
echo "PASS: installed, opened, navigated, switched themes, no app crash."
