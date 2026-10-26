# ClearSave for Android

A free, ad-free video and audio downloader for YouTube, X and Instagram. Everything runs on your phone: no account, no ads, no server, nothing is uploaded.

## Download
Get the latest APK from the **[Releases](../../releases/latest)** page and install it. Installing from outside the Play Store ("sideloading") is how this app is distributed. See [Install](#install) below.

## Features
- **Original quality:** every resolution the source offers, up to 4K. Above 1080p, YouTube only provides VP9/AV1 video, so ClearSave saves those as WebM (VP9 + Opus), which phones play more reliably than VP9 inside MP4.
- **Video:** MP4, MKV or WebM. **Audio:** MP3, M4A, AAC, Opus, OGG, FLAC, WAV or ALAC, with bitrate choices.
- **Cover art** for audio files, with each file given its own album so music apps show a cover per song.
- **Playlists** (and multi-video X/Instagram posts) as one ZIP, **trim** to save only a section, and **Share to ClearSave** from the YouTube, X or Instagram apps.
- Music is saved to `Music/ClearSave`, videos to `Movies/ClearSave`, ZIP files to `Downloads/ClearSave`, so your music app and gallery find them.
- If YouTube cuts a download off with a 403 error, the app fetches fresh links and resumes by itself. After a failure, **Copy details for support** copies what's needed for a bug report.

## Install
1. Download `ClearSave-<version>.apk` from the Releases page onto your phone.
2. Open it. Android asks you to allow "install unknown apps" for the app you opened it from. Allow it, then tap **Install**.
3. If Play Protect warns about an unrecognized app, that is normal for apps installed outside the Play Store. Only continue if you downloaded the file from this repository's Releases page.
4. Open ClearSave and allow notifications. The first start unpacks the engine and updates yt-dlp, which takes a moment and needs internet.

Requires Android 7.0 or newer on a 64-bit ARM phone (practically every phone from the last several years).

### Check the download
Each release lists the APK's SHA-256 checksum. The app is signed with a key whose certificate fingerprint is:

```
SHA-256: 14:AE:B9:7B:B0:AC:95:48:0E:83:85:1B:65:3B:F9:6D:10:B3:06:1D:10:3A:AB:4A:51:E5:AA:9B:D3:E1:74:EB
```

You can check it with `apksigner verify --print-certs ClearSave-<version>.apk` (part of the Android SDK build tools). Updates are only accepted by Android if they are signed with the same key.

## Privacy
ClearSave has no accounts, no analytics and no tracking, and it uploads nothing. It only talks to:
- the site you paste a link from (YouTube, X or Instagram) and YouTube's thumbnail servers, to read and download what you ask for;
- GitHub, to check for a newer version of the yt-dlp downloader engine.

## Disclaimer
- **Use it only for content you have the right to save.** Downloading from these sites can violate their terms of service and copyright law. You are responsible for how you use this app.
- ClearSave is an independent project. It is not affiliated with or endorsed by YouTube, Google, X or Instagram. Those names are used only to describe what the app works with.
- Some posts (private ones, or Instagram posts that need a login) can't be downloaded. YouTube sometimes asks to confirm you're not a bot; waiting a few minutes usually helps.
- The software is provided as is, without warranty (see the license).

## How it works
The screens are a small web page shown in a WebView, shared with the [ClearSave web app](https://github.com/lonelyjerry27/Clearsave). A Kotlin bridge (`Api.kt`) answers the page's requests using the [youtubedl-android](https://github.com/yausername/youtubedl-android) engine: yt-dlp, Python, the QuickJS JavaScript engine that YouTube needs, and ffmpeg, all bundled in the app.

## Build from source
Needs JDK 17 and the Android SDK (platform 35, build-tools 35).

```bash
export JAVA_HOME=/path/to/jdk17
./scripts/sync-web.sh            # copies the screens from ../clearsave-web/static (already included in this repo)
./gradlew :app:assembleDebug     # debug build: app/build/outputs/apk/debug/app-debug.apk
```

A signed release build reads its key from `~/.android/clearsave-release.properties` (keys `storeFile`, `storePassword`, `keyAlias`, `keyPassword`; override the path with `CLEARSAVE_SIGNING`). That file and the key are never part of the repository.

```bash
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

## License
ClearSave for Android is free software, released under the **GNU General Public License v3.0** (see [LICENSE](LICENSE)). Copyright (C) 2026 UDAY.

It is built on GPL-3.0 licensed components (youtubedl-android and its ffmpeg module), which is why it uses the same license. Anyone who receives the app can get its source code from this repository. Other bundled software and its licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
