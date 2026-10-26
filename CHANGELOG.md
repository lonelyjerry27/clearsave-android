# Changelog

## 1.3
- **Choose where downloads are saved.** The start screen shows "Saving to …" with **Change…**, which opens Android's own folder chooser. The choice is remembered between launches, and **Use default** goes back to `Music/ClearSave` and `Movies/ClearSave`.
- If the chosen folder is deleted or can't be written to, the file is saved in the default folder instead and the app says so.
- The folder permission is granted through Android's folder chooser, so the app never needs broad access to your storage.

## 1.2.1
- **Fixed Instagram video downloads.** The "above 1080p is saved as WebM" rule is meant for YouTube, but Instagram's tall phone videos are over 1280 pixels high, so it wrongly applied to them and the download failed with "Conversion failed". The rule now applies to YouTube only. If WebM is chosen for an Instagram or X video, it is saved as MKV instead of failing.
- Instagram and X videos are no longer affected by the YouTube-specific note.

## 1.2
- **Fixed black video above 1080p.** 1440p and 4K are now saved as WebM (VP9 + Opus). VP9 inside MP4 shows a black picture on many phones. AV1 and HDR are avoided when VP9 and SDR exist. A note explains the switch.
- **Fixed missing cover art.** Each audio file gets its own album tag (Android was naming the album after the folder, so music apps showed one shared cover). The cover image is chosen once and verified, and the app says when a cover couldn't be added.
- **Music and videos are now saved where players look:** `Music/ClearSave` and `Movies/ClearSave` (ZIP files stay in `Downloads/ClearSave`).
- **Much faster starts.** The download reuses what the link lookup already learned, and no longer tries about 40 thumbnail variants one by one.
- **Smoother screens.** A lighter look on Android: no blur layers or blend modes, which made some phones stutter.
- First signed release build.

## 1.1
- Automatic retry when YouTube returns 403 or the connection drops: fresh links, resuming the partial file, up to two retries.
- "Copy details for support" button after a failed download.

## 1.0
- First version: YouTube, X and Instagram; video and audio formats; playlists as ZIP; trim; cover art; Share to ClearSave; dark mode.
