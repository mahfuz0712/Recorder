# Recorder

Offline sound recorder for Android (Kotlin, Jetpack Compose). Package: `com.mux.recorder`

## Build
Push to GitHub -> Actions -> "Build APK" -> download artifact `Recorder-debug-apk`.

## Release (in-app updates)
1. Bump `appVersionName` in `app/build.gradle.kts` (and `version` in `metadata.json`).
2. Build, then create a GitHub Release tagged with the same number (e.g. `1.1.0`) and upload the APK.
3. Installed apps check `releases/latest` on open and offer the update.

## Before publishing
Edit `DEV_NAME`, `DEV_EMAIL`, `GITHUB_USER` and `REPO` in `app/src/main/java/com/mux/recorder/Update.kt`,
and `author` in `metadata.json`. Replace the images in `public/screenshots` with real screenshots from your phone.
