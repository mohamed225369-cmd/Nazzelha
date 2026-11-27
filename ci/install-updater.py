#!/usr/bin/env python3
"""Install the updater source overlay after the existing v0.5.1 patches."""
from pathlib import Path
import sys

project = Path(sys.argv[1])
here = Path(__file__).resolve().parent
java = project / "app/src/main/java/com/clipshelf/app"

def replace(path, old, new):
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise SystemExit(f"Expected exactly one source match in {path}: {old[:80]}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")

updater = (here / "overlays/AppUpdater.kt").read_text(encoding="utf-8")
(java / "AppUpdater.kt").write_text(updater, encoding="utf-8")
(java / "ApkDelta.kt").write_text((here / "overlays/ApkDelta.kt").read_text(), encoding="utf-8")
test = project / "app/src/test/java/com/clipshelf/app"
test.mkdir(parents=True, exist_ok=True)
(test / "ApkDeltaTest.kt").write_text((here / "tests/ApkDeltaTest.kt").read_text(), encoding="utf-8")
main = java / "MainActivity.kt"
replace(main, '    private val prefs by lazy { getSharedPreferences("appearance", MODE_PRIVATE) }',
    '    private val appUpdates by lazy { AppUpdateUi(this) }\n    private val prefs by lazy { getSharedPreferences("appearance", MODE_PRIVATE) }')
replace(main, '        showTab(Workspace.tab)\n    }\n    override fun onNewIntent',
    '        showTab(Workspace.tab)\n        appUpdates.autoCheck()\n    }\n    override fun onNewIntent')
replace(main, 'override fun onDestroy() { QueueStore.unlisten(queueListener);',
    'override fun onDestroy() { appUpdates.close(); QueueStore.unlisten(queueListener);')
replace(main, '"Appearance · $current", "Update yt-dlp", "About Nazzelha"',
    '"Appearance · $current", "App updates", "Update yt-dlp", "About Nazzelha"')
replace(main, '                1 -> updateEngine()', '                1 -> appUpdates.settings()\n                2 -> updateEngine()')
manifest = project / "app/src/main/AndroidManifest.xml"
replace(manifest, '    <uses-permission android:name="android.permission.INTERNET" />',
    '    <uses-permission android:name="android.permission.INTERNET" />\n    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />')
replace(manifest, '        <service android:name=".DownloadService"',
    '        <provider android:name=".UpdateApkProvider" android:authorities="com.clipshelf.app.updates" android:exported="false" android:grantUriPermissions="true" />\n        <service android:name=".DownloadService"')
gradle = project / "app/build.gradle.kts"
replace(gradle, 'versionCode = 6', 'versionCode = 11')
replace(gradle, 'versionName = "0.5.1"', 'versionName = "0.5.6"')
print("Installed app updater overlay for 0.5.6")
