# Sound Stash

Download YouTube videos and playlists as chaptered audio for offline listening on Android. The default is one compact 24 kbps Opus file per video, with original chapter names and timestamps where available.

## Open in CoGo

Extract the source ZIP and open the **SoundStash** folder containing `settings.gradle` as an existing project in Code on the Go. Use a recent CoGo release with Gradle 9.6.1, Android Gradle Plugin 9.3.1, and the Android 36 SDK. Sync and run the debug configuration. The equivalent desktop command is `./gradlew assembleDebug`.

The app runs on 64-bit ARM Android phones with Android 10 or newer. Build with the same signing key to update an existing install and keep its queue and settings.

## App updates

Starting in 0.5.3, Sound Stash checks [GitHub Releases](https://github.com/Warren-Stack-Custom-Technology/SoundStash/releases) when opened, at most once every 24 hours. You can also use **Settings → Check for app updates**. When an update is available, Sound Stash can download and verify it. Android will ask you to confirm installation and may first ask you to allow installs from Sound Stash.

To publish an update, increase `versionCode` and `versionName` in `app/build.gradle`. Build the APK with the signing key used for the previous version. Create a stable GitHub release tagged with its three-part version, such as `v0.5.3`, and attach the APK as `SoundStash.apk` before publishing the release. A source ZIP alone cannot update the app. If you built your current copy in CoGo, use the same CoGo signing key for the APK you publish.

## Import and play audio

- Paste YouTube links or text containing them, share from YouTube or Samsung Notes, or select text files, PDFs, and modern Office documents. PDFs can supply both selectable URLs and clickable links. Scanned pages need OCR before import.
- Copy a playlist’s own Share link to queue its available videos in order. A regular video link continues to queue one video. Videos already in the queue are deduplicated by ID. Available titles appear in the queue while it prepares.
- Tap **Download**. Downloads and encoding continue when you leave the app or lock the screen. Allow notifications to see progress and tap **Stop** to pause. On Android 14 and later, you can also pause by swiping away the ongoing notification while the phone is unlocked.
- Choose a **Download folder** in Settings or use the default **Downloads / Sound Stash**. Audio saved before the rename stays in its original folders.
- Choose one chaptered file or numbered chapter tracks under **Chapter export**. The app supports Opus and M4B. Tap **Play** on a finished item. In VLC, open **More options → Go to chapter** for files with embedded chapters.

## Performance and appearance

**Settings → Workload** controls the app's pace relative to its original full-speed mode:

| Workload | Downloads and encoding |
| --- | --- |
| **25%** | Run one task at a time at the lowest speeds. |
| **50% (default)** | Split the budget evenly between downloading and encoding. Run up to two jobs per stage, each capped at 256 KiB/s for downloading or six seconds of audio per second for encoding. |
| **75%** | Run up to two jobs per stage, each capped at 384 KiB/s for downloading or nine seconds of audio per second for encoding. |
| **100%** | Run at the original full speed with up to two downloads and one encode. |

The app and notification show the effective workload profile and why it was selected. The percentage sets the app's pace. Actual CPU usage and temperature vary by phone and network. Battery Saver and devices reporting limited resources use the 25% profile. Changes take effect with the next batch. If Android reports severe heat, the app pauses the queue and keeps its working files so you can resume after cooling.

Downloads wait for encoder capacity to limit temporary disk use. The app removes working files after verifying and saving the audio and retries leftover cleanup on launch. Removing a queue item also discards its working files. Saved audio stays in its export folder.

**Appearance** includes Dusk, Black, Ocean, Forest, Ember, Rosewood, Glacier, Paper, Daylight, and System. Turn on **Twinkling background** for faint stars and occasional shooting stars behind the queue. The animation pauses when the app leaves view.

[Optional donation · Buy Me a Coffee](https://www.buymeacoffee.com/WarrenStack)

## Bundled components

- [youtubedl-android 0.18.1](https://github.com/yausername/youtubedl-android) provides the Android Python, QuickJS, FFmpeg, and FFprobe runtime. Its [FFmpeg build instructions](https://github.com/yausername/youtubedl-android/blob/master/BUILD_FFMPEG.md) are available upstream.
- [yt-dlp 2026.10.26](https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.26) is included as the initial extractor. The upstream SHA-256 for the executable is e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3.
- The 16 KiB-aligned WebP libraries are from the [Termux libwebp build](https://github.com/termux/termux-packages/tree/master/packages/libwebp), version 1.6.0.0.0.1+really1.6.0. See the [libwebp source and license](https://github.com/webmproject/libwebp/tree/v1.6.0).
- [PdfBox-Android 2.0.27.0](https://github.com/TomRoush/PdfBox-Android) reads selectable PDF text and link annotations.
