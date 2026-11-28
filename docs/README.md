# Nazzelha landing page
Arabic RTL, responsive static website (HTML/CSS/JS, no build step or framework). Screenshots are actual Android emulator screenshots from the successful v0.5.9 CI run 46100166640; the SVG logo uses the exact Android vector paths and gradients.

- Serve locally: `python -m http.server 8000 --directory docs` (or run the command from the extracted site folder without `--directory docs`).
- GitHub Pages: Repository **Settings → Pages → Build and deployment → Deploy from a branch → main /docs → Save**. The repository currently has Pages disabled; no domain purchase/configuration is assumed.
- The page checks the public GitHub latest stable release API, verifies the exact official APK asset, and updates version/size/links. A pinned v0.5.9 fallback works if GitHub is unreachable. The update banner indicates that an update is available for older copies, or that the newest release is newer than the last download requested in that browser. Browser local storage tracks requested release only, not installation. No backend or fake format resolver is present: real formats are extracted by the Android application.
- The APK is for Android ARM64; new social-platform download behavior still needs live end-to-end validation. The website is not affiliated with those platforms.
