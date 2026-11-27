#!/usr/bin/env python3
from pathlib import Path
import sys
project = Path(sys.argv[1])
here = Path(__file__).resolve().parent
java = project / "app/src/main/java/com/clipshelf/app"
for name in ("Brand.kt", "UnifiedProgress.kt"):
    (java / name).write_text((here / "overlays" / name).read_text(), encoding="utf-8")
test = project / "app/src/test/java/com/clipshelf/app"
(test / "UnifiedProgressTest.kt").write_text((here / "tests/UnifiedProgressTest.kt").read_text(), encoding="utf-8")
(project / "app/src/main/res/drawable/ic_logo.xml").write_text((here / "overlays/ic_logo.xml").read_text(), encoding="utf-8")
def replace(path, old, new):
    s = path.read_text()
    if s.count(old) != 1: raise SystemExit(f"Expected one match in {path}: {old[:100]}")
    path.write_text(s.replace(old, new, 1))
def change(path, old, new):
    s=path.read_text()
    if old not in s: raise SystemExit(f"Missing {old[:100]} in {path}")
    path.write_text(s.replace(old,new))
m=java / "MainActivity.kt"
# Shared, rounded cards/buttons with clear contrast in both existing themes.
replace(m, "radius: Int = 10", "radius: Int = 16")
change(m, "shape(panel2, 10)", "shape(panel2, 16)")
change(m, "shape(if (primary) accent else panel2, 10)", "shape(if (primary) accent else panel2, 16)")
replace(m, "setPadding(dp(12), dp(4), dp(12), dp(4)); setOnClickListener { action() }",
    """setPadding(dp(12), dp(4), dp(12), dp(4))
        Brand.iconFor(text)?.let { Brand.buttonIcon(this,it,if(primary) onAccent else ink) }
        setOnClickListener { action() }""")
replace(m, 'header.addView(label("نزّلها", 23, ink, true)', 'header.addView(label("نزّلها", 25, ink, true)')
replace(m, 'header.addView(button("Settings") { showSettings() })',
    'header.addView(button("Settings") { showSettings() }.apply { textSize = 12f })')
replace(m, 'tabs = row(); root.addView(tabs, lp(-1, 56, 4))', 'tabs = row().apply { background = shape(panel, 18, line); setPadding(dp(5),dp(4),dp(5),dp(4)) }; root.addView(tabs, lp(-1, 62, 4))')
replace(m, "        isSelected = active", "        Brand.iconFor(text)?.let { Brand.buttonIcon(this,it,if(active) accent else soft) }; isSelected = active")
replace(m, 'content.addView(label("Paste a video or playlist link to choose what to save.", 14, soft), lp(-1, -2, 8))', '')
replace(m, '''            } else {
                content.addView(rule(), lp(-1, 1, 20))
                content.addView(label("How it works", 16, ink, true), lp(-1, -2, 20))
                listOf("01" to "Load a video or playlist", "02" to "Select videos and set quality", "03" to "Save to your Downloads folder").forEach { (n, text) ->
                    val r = row(); r.addView(label(n, 13, soft, true), lp(36, -2)); r.addView(label(text, 14, ink), LinearLayout.LayoutParams(0, -2, 1f)); content.addView(r, lp(-1, 44))
                }
            }
            footer.addView(label("Files are saved on your device. Only download content you have permission to save.", 12, soft))''','''            }
            val quick = row().apply { background = shape(panel, 20, line); setPadding(dp(12),dp(12),dp(12),dp(12)) }
            fun quickMode(mode: Mode, caption: String, icon: String) = button(caption, Workspace.defaultChoice.mode == mode) {
                Workspace.defaultChoice = Workspace.defaultChoice.copy(mode = mode,videoId = null,audioId = null,combined = false)
                showTab(0)
            }.apply { Brand.buttonIcon(this,icon,if(Workspace.defaultChoice.mode == mode) onAccent else ink) }
            weightButton(quick,quickMode(Mode.VIDEO,"Video + audio","video")); quick.addView(space(8))
            weightButton(quick,quickMode(Mode.AUDIO,"Audio","audio"))
            content.addView(quick,lp(-1,-2,18))''')
replace(m, 'val linkRow = row().apply { background = shape(panel, 10, line);', 'val linkRow = row().apply { background = shape(panel, 18, line);')
replace(m, 'val settings = row().apply { background = shape(panel, 10, line);','val settings = row().apply { background = shape(panel, 18, line);')
replace(m, 'ready == available.size -> "Qualities ready · sizes may be estimated"','ready == available.size -> "Qualities ready"')
replace(m, 'panel.addView(label(if (clip == null) "Apply one setting to selected videos. You can override each video separately." else clip.title, 14, ink, true))','if (clip != null) panel.addView(label(clip.title, 14, ink, true))')
replace(m, 'val explanation = label("", 12, soft); panel.addView(explanation, lp(-1, -2, 12))','')
s=m.read_text(); lines=s.splitlines(keepends=True); lines=[l for l in lines if "explanation.text =" not in l]; m.write_text("".join(lines))
replace(m, 'orientation = VERTICAL; setPadding(0, dp(12), 0, 0)', 'orientation = VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(10)); background = shape(panel,20,line)')
replace(m, 'addView(head); addView(dynamic); addView(rule(), lp(-1, 1, 12))','addView(head); addView(dynamic)')
replace(m, 'visibility = if (states.isEmpty()) View.GONE else View.VISIBLE','visibility = if (states.size <= 1) View.GONE else View.VISIBLE')
replace(m, 'divider = null; dividerHeight = 0; setPadding(0, dp(4), 0, dp(8))','divider = ColorDrawable(Color.TRANSPARENT); dividerHeight = dp(10); setPadding(0, dp(4), 0, dp(8))')
replace(m, 'val color = if (state.phase == Phase.FAILED) red else if (state.phase == Phase.DONE) green else soft','val color = if (state.phase == Phase.FAILED) red else if (state.phase == Phase.DONE) green else if (state.phase.active) accent else soft')
replace(m, 'label(if (state.phase == Phase.FAILED) EngineRecoveryPolicy.summary(state.message) else state.message, 12, color)', 'label(if (state.phase == Phase.FAILED) "Download failed" else DisplayText.clean(state.message), 13, color)')
change(m, '"Update yt-dlp"', '"Download engine"')
change(m, '"Checking yt-dlp…"', '"Checking…"')
change(m, '"Updating yt-dlp…"', '"Updating…"')
change(m, '"Engine update"', '"Download engine"')
replace(m, '"Installed yt-dlp: " + current + "\\nAutomatic recovery retries compatible engine failures once."', '"Version: " + current')
replace(m, '.setMessage(result).setPositiveButton("OK", null).show()', '.setMessage(DisplayText.clean(result)).setPositiveButton("OK", null).show()')
replace(m, '"Media downloads stay on your device. Save only content you have permission to download.\\n\\nPowered by yt-dlp and FFmpeg."', '"Nazzelha " + appUpdates.versionLabel() + "\\nSave only content you have permission to download."')
# Keep UI strings in English as in the existing app; retain Arabic logotype.
change(m, 'label("VIDEO QUALITY LIMIT"', 'label("Video quality"')
change(m, 'label("EXACT VIDEO STREAM"', 'label("Video format"')
change(m, 'label("SOURCE AUDIO QUALITY"', 'label("Audio quality"')
change(m, 'label("AUDIO OUTPUT"', 'label("Audio format"')
change(m, 'label("MP3 TARGET BITRATE"', 'label("MP3 bitrate"')
change(m, 'field("DOWNLOAD MODE", modes)', 'field("Format", modes)')
replace(m, 'val sheet = dialog.show()', 'val sheet = dialog.showBranded()')
# All standard AlertDialog builders share one visual treatment. Avoid touching Toast.show or sheet windows.
s=m.read_text()
s=s.replace('.setPositiveButton("Done", null).show()', '.setPositiveButton("Done", null).showBranded()').replace('.setNegativeButton("Cancel", null).show()', '.setNegativeButton("Cancel", null).showBranded()').replace('.setPositiveButton("OK", null).show()', '.setPositiveButton("OK", null).showBranded()').replace('.setNegativeButton("Close", null)\n', '.setNegativeButton("Close", null)\n')
s=s.replace('                }.show()\n        }','                }.showBranded()\n        }').replace('        }.show()\n    }','        }.showBranded()\n    }').replace('            }.show()\n    }','            }.showBranded()\n    }').replace('.create().apply { show() }','.create().apply { show(); Brand.styleDialog(this) }')
m.write_text(s)
# Make link/metadata loading and popups use the same palette.
u=java / "AppUpdater.kt"
s=u.read_text().replace('.show()', '.showBranded()')
# Do not replace unrelated Toasts; none are shown in updater.
s=s.replace('.create().apply { show() }','.create().apply { show(); Brand.styleDialog(this) }')
s=s.replace('setMessage("Android will ask you to confirm installation.")','setMessage("Ready to install.")')
s=s.replace('release.notes)', 'DisplayText.clean(release.notes))')
s=s.replace('it.message?.take(240) ?: "Could not check for updates."', 'DisplayText.updateError()')
s=s.replace('it.message?.take(240) ?: "Update download failed."', 'DisplayText.updateError()')
u.write_text(s)
e=java / "Engine.kt"
replace(e, '        val dir = File(context.cacheDir, "clipshelf/${job.key}")', '        val unified = UnifiedProgress(job)\n        val dir = File(context.cacheDir, "clipshelf/${job.key}")')
replace(e, 'addOption("--no-playlist"); addOption("--newline"); addOption("--progress")', 'addOption("--no-playlist"); addOption("--newline"); addOption("--progress"); addOption("--progress-template",UnifiedProgress.TEMPLATE)')
replace(e, 'TransferProgress.fromCallback(p, eta.toLong(), line)?.let(progress)', 'unified.callback(line)?.let(progress)')
replace(e, 'progress(TransferProgress.saving(0, file.length()))', 'progress(unified.saving(TransferProgress.saving(0, file.length())))')
replace(e, 'return publish(context, file, job, canceled, progress)', 'return publish(context, file, job, canceled) { progress(unified.saving(it)) }')
d=java / "DownloadService.kt"
replace(d, 'var lastMessage: String? = null', 'var lastMessage: String? = null\n                var overall = 0')
replace(d, 'val indeterminate = update.percent == null','val indeterminate = false\n                        overall = maxOf(overall, update.percent ?: overall).coerceIn(0,99)')
replace(d, 'progress = update.percent, message = update.message', 'progress = overall, message = DisplayText.clean(update.message)')
replace(d, 'update.percent ?: 0, indeterminate', 'overall, indeterminate')
replace(d, 'message = "Connecting…"','progress = 0, message = "Connecting…"')
# The original icon is a single-color status-bar glyph, not the colored launcher asset.
p=java / "Palette.kt"
change(p, '0xFFFFFFFF.toInt(),\n        panel = 0xFFF7F8FA.toInt(),\n        panel2 = 0xFFEEF0F3.toInt()', '0xFFF7F5F2.toInt(),\n        panel = 0xFFFFFFFF.toInt(),\n        panel2 = 0xFFEDEAE7.toInt()')
styles=project / "app/src/main/res/values/styles.xml"
change(styles, '<item name="android:windowBackground">#FFFFFF</item>','<item name="android:windowBackground">#F7F5F2</item>')
change(styles, '<item name="android:colorBackground">#FFFFFF</item>','<item name="android:colorBackground">#F7F5F2</item>')
change(styles, '<item name="android:statusBarColor">#FFFFFF</item>','<item name="android:statusBarColor">#F7F5F2</item>')
change(styles, '<item name="android:navigationBarColor">#FFFFFF</item>','<item name="android:navigationBarColor">#F7F5F2</item>')
print("Installed Ink & Coral identity, clean UI and unified download progress")
