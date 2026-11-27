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
TEST_ABI="${4:-arm64-v8a}"
if ! grep -qw "$TEST_ABI" "$OUT/abi.txt"; then
  echo "Emulator does not support requested ABI: $TEST_ABI"
  exit 1
fi
PRIMARY_ABI="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
if [[ "$TEST_ABI" == x86_64 && "$PRIMARY_ABI" != x86_64 ]]; then
  echo "Engine test requires native x86_64 execution."
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
  for attempt in $(seq 1 "${3:-12}"); do
    if adb shell uiautomator dump /sdcard/nazzelha-ui.xml >/dev/null 2>&1 &&
       adb pull /sdcard/nazzelha-ui.xml "$OUT/ui.xml" >/dev/null 2>&1; then
      if python3 - "$OUT/ui.xml" "$label" "$action" <<'PY'
import re, subprocess, sys, xml.etree.ElementTree as ET
path, label, action = sys.argv[1:]
nodes = [n for n in ET.parse(path).iter("node") if (re.search(label, n.get("text", "")) if action == "regex" else n.get("text", "").casefold() == label.casefold())]
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

ui 'تحميل' assert
adb shell pidof "$PACKAGE" | tee "$OUT/startup-pid.txt"
grep -Eq '[0-9]' "$OUT/startup-pid.txt"
ui 'فيديو + صوت' assert
ui 'فيديو' assert
ui 'صوت' assert
adb exec-out screencap -p > "$OUT/home.png"
ui 'الإعدادات' tap
ui 'تحديثات التطبيق' tap
ui 'البحث عن تحديث' tap
ui 'التطبيق محدّث' assert
adb exec-out screencap -p > "$OUT/update-check.png"
ui 'تم' tap
ui 'محرك التحميل' assert
if [[ "$TEST_ABI" == x86_64 ]]; then
  ui 'محرك التحميل' tap
  ui '^الإصدار: [0-9]{4}\.[0-9]{2}\.[0-9]{2}' regex
  adb exec-out screencap -p > "$OUT/engine-version.png"
  ui 'تحديث الآن' tap
  ui '^(الإصدار السابق:|الإصدار الحالي:)' regex 48
  cp "$OUT/ui.xml" "$OUT/engine-result.xml"
  ui 'تم' tap
  ui 'محرك التحميل' tap
  ui '^الإصدار: [0-9]{4}\.[0-9]{2}\.[0-9]{2}' regex
  ui 'إغلاق' tap
  echo "PASS: native engine version and update."
fi
adb exec-out screencap -p > "$OUT/settings.png"
ui 'الملفات' tap
ui 'لا توجد ملفات محفوظة' assert
adb exec-out screencap -p > "$OUT/files.png"
ui 'التحميلات' tap
ui 'لا توجد تحميلات' assert
adb exec-out screencap -p > "$OUT/downloads.png"
ui 'الإعدادات' tap
ui 'المظهر' tap
ui 'فاتح' tap
ui 'تحديثات التطبيق' assert
ui 'الرئيسية' tap
ui 'تحميل' assert
adb exec-out screencap -p > "$OUT/light.png"
ui 'الإعدادات' tap
ui 'المظهر' tap
ui 'داكن' tap
ui 'تحديثات التطبيق' assert
ui 'الرئيسية' tap
ui 'تحميل' assert
adb exec-out screencap -p > "$OUT/dark.png"
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$PACKAGE/.MainActivity" | tee "$OUT/relaunch.txt"
grep -Eq '^Status:[[:space:]]+ok' "$OUT/relaunch.txt"
ui 'تحميل' assert
adb exec-out screencap -p > "$OUT/relaunch.png"
adb shell pidof "$PACKAGE" | tee "$OUT/pid.txt"
grep -Eq '[0-9]' "$OUT/pid.txt"
adb logcat -d -b crash > "$OUT/crash.txt"
if grep -E "Process: $PACKAGE([, ]|$)" "$OUT/crash.txt"; then
  echo "Application crashed during smoke test."
  exit 1
fi
echo "PASS: installed, opened, navigated, switched themes, no app crash."
