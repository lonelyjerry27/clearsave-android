# Third-party software

ClearSave for Android bundles or depends on the following. This is a good-faith summary for convenience, not legal advice; the linked projects are the authority on their own licenses.

| Component | Used for | License |
|---|---|---|
| [youtubedl-android](https://github.com/yausername/youtubedl-android) (library and ffmpeg modules, as published by junkfood02) | Runs yt-dlp, Python and ffmpeg on Android | GPL-3.0 (stated in the published package metadata) |
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) | Reads and downloads from YouTube, X and Instagram (bundled inside youtubedl-android and updated at runtime) | The Unlicense |
| [Python](https://www.python.org/) | Runs yt-dlp | PSF License |
| [QuickJS](https://bellard.org/quickjs/) | JavaScript engine yt-dlp needs for YouTube | MIT |
| [FFmpeg](https://ffmpeg.org/) | Merging, converting, cover art | Built and distributed by the youtubedl-android ffmpeg module under GPL-3.0 |
| [AndroidX](https://developer.android.com/jetpack/androidx) (core, appcompat, webkit) | Android app framework | Apache-2.0 |
| [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Background work | Apache-2.0 |

The app's own source code is under the GNU General Public License v3.0; see [LICENSE](LICENSE).

"YouTube", "X" and "Instagram" are trademarks of their respective owners. ClearSave is not affiliated with them.
