# Folder Audio

Folder Audio is a local Android music player for Android 8.0 and up (API 26+). It decodes FLAC, ALAC in MPEG-4 or CAF, MP3, and WAV. The library uses Android's folder picker and scans only the chosen folders and their subfolders. Folder access and EQ settings persist across launches.

## Use

1. Open **Library → Add folder** and select a folder such as **Music**. Add more folders as needed. Use **Folders** to revoke a selected folder and **Refresh** after adding or moving files.
2. Tap a track to play it. The transport supports seeking, previous and next tracks, and background controls through the media notification.
3. Open **Equalizer** for 16 peaking bands. Each band has an adjustable frequency (10–22,000 Hz), Q (0.1–20), and gain (−12 to +12 dB in 0.25 dB steps). Pre-gain uses the same dB range and is applied before the filters. Reduce pre-gain when boosting several bands to leave headroom. Bands at or above 49% of the output sample rate are bypassed.
4. Open **Output** to select **Prefer exclusive**, **Exclusive only**, or **Shared**. While playing, the transport displays the granted mode and output stream sample rate.

## Audio output

On Android 8.1 and later, Folder Audio requests exclusive AAudio MMAP. A granted exclusive MMAP stream bypasses the Android mixer. Availability depends on the device, audio route, and whether another client holds it. **Prefer exclusive** can use shared output when necessary. **Exclusive only** stops with an error when the stream cannot be opened. Android 8.0 uses shared output.

The app requests the source sample rate for exclusive output and also tries the route's default rate. Playback converts to stereo and resamples when necessary. Enabled EQ changes the decoded samples.

Android may restrict selection of certain locations in the folder picker. Use a subfolder such as **Music** on internal storage or the SD card.

## Build a debug APK

Use Linux or macOS with JDK 17, Gradle 8.11.1, and the Android SDK command-line tools. The first build also needs Bash, curl, xz, Make, and access to Google Maven and ffmpeg.org. Set `ANDROID_HOME` to the SDK path or set `sdk.dir` in `local.properties`.

```sh
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1'
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Gradle builds FFmpeg 8.1.3 for arm64-v8a, armeabi-v7a, x86 and x86_64. To populate native dependencies ahead of Android Studio's project sync, run `gradle :app:prepareFfmpeg` first. The shared FFmpeg libraries use the LGPL configuration and are built from [upstream source](https://ffmpeg.org/releases/ffmpeg-8.1.3.tar.xz) using [build-ffmpeg.sh](scripts/build-ffmpeg.sh).

## Verify playback

On Linux, with a C++17 compiler, FFmpeg CLI and development headers, run `scripts/test-audio-core.sh`. The host checks exercise decoding, sample-rate conversion, seeking, descriptor offsets, the 16-band filter cascade, and pre-gain.

On a phone, add a folder with all four formats, play and seek through each, change EQ during playback, and test screen-off controls. Try the exclusive modes on compatible hardware and check the displayed route mode. A shared-only route should play under **Prefer exclusive** and show an error under **Exclusive only**.
