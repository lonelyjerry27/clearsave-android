# ClearSave for Android

The ClearSave downloader as a standalone Android app. Everything runs on the phone: nothing is uploaded and no server is involved.

- **Screens:** the same web UI as `clearsave-web`, shown in a WebView.
- **Engine:** [youtubedl-android](https://github.com/junkfood02/youtubedl-android) (yt-dlp + Python + QuickJS) and its ffmpeg module, the same engine family YTDLnis and Seal use. QuickJS is what lets yt-dlp read YouTube.
- **Saving:** finished files go to `Downloads/ClearSave`.
- **Share to ClearSave:** share a link from YouTube, X or Instagram and ClearSave opens with it.

## Build the APK
Needs JDK 17 and the Android SDK (platform 35, build-tools 35).

```bash
export JAVA_HOME=/path/to/jdk17
./scripts/sync-web.sh        # copies the screens from ../clearsave-web/static
./gradlew :app:assembleDebug
```
The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Install on a phone
1. Copy the APK to the phone (USB, AirDrop alternative, cloud drive, or `adb install -r app-debug.apk`).
2. Open it and allow "install unknown apps" for the app you opened it from.
3. Open ClearSave. The first start unpacks the engine and updates yt-dlp, which takes a little while.

## Notes
- 64-bit ARM only (`arm64-v8a`), which covers practically every phone from the last several years.
- The APK is about 40 MB because Python, QuickJS and ffmpeg ship inside it; the app unpacks them (and updates yt-dlp) on first start.
- Tested on an Android 14 emulator: YouTube (all qualities including 4K), MP3 with cover art, MP4 merge, trim, playlist ZIP, X and Instagram links, Share-to-app, dark mode.
- This is a debug-signed build for personal sideloading, not for the Play Store.
- Only save content you have the right to. Downloading can violate a site's terms.
- The engine libraries are GPL-3.0 licensed. Keep that in mind before distributing the APK to others.
