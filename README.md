# Almost Monitor for Canon EOS

A free, open-source Android app that turns your phone into a field monitor and remote for Canon EOS cameras, over a single USB cable. It's meant for people who can't afford a dedicated camera monitor yet.

The camera stays in its normal mode (PTP remote control, the same protocol EOS Utility uses), so you can keep recording to the card while you monitor.

> **Verified camera:** Canon EOS R6 Mark II. Other Canon EOS cameras with USB remote shooting should show live view, but camera-specific features (AF frame, AF area icons, video format, battery level, REC indicator) may behave differently. Reports from other models are very welcome.

## Features

**Monitoring**
- Zebras with adjustable threshold
- Focus peaking: color, sensitivity, and a black & white mode
- 3D LUTs (`.cube`, up to 129³)
- False color
- Scopes drawn over the image: histogram (luma or RGB), waveform, RGB parade, vectorscope with skin-tone line
- Frame guides (2.39, 1.85, 16:9, 4:5, 9:16, 1:1), rule of thirds, safe areas
- Anamorphic desqueeze, mirror, rotate 180°
- Reference frame overlay, app zoom up to 8×, screen lock

**Camera control**
- Shutter, aperture, ISO, exposure compensation, white balance / Kelvin
- Video resolution and frame rate
- AF area, autofocus, touch AF, start/stop recording

**Camera info**
- Mode, focal length, battery, free card space with remaining-minutes estimate
- REC timer and the camera's AF frame on screen

## Download
Get the latest APK from the [Releases](../../releases) page and open it on your phone. Android will ask you to allow installing apps from your browser or file manager.

## Requirements
- An Android 9+ phone with USB OTG (USB host) support and OpenGL ES 3.0
- A USB **data** cable. Charge-only cables won't work.
- A Canon EOS camera with USB remote shooting

## Connecting
1. On the camera, if the Set-up menu has **Choose USB connection app**, select **Photo Import/Remote Control**. Don't select UVC/UAC streaming.
2. Turn off the camera's Wi-Fi/Bluetooth connection.
3. On the phone, enable OTG if your phone has that setting.
4. Connect the cable and turn the camera on. When Android asks for USB access, tap OK.
5. If Canon Camera Connect opens instead, clear its "Open by default" setting.

The app shows these steps on first launch. You can reopen them from Menu › Camera & connection guide.

## Building
1. Open the project folder in Android Studio (Ladybug or newer) and let Gradle sync. Or build from the command line with `./gradlew assembleDebug` (`gradlew.bat` on Windows).
2. Run it on your phone. The phone's USB port will be busy with the camera, so use **Wireless debugging** (Android 11+).

### Releasing (maintainers)
Pushing a tag like `v1.0.0` runs `.github/workflows/release.yml`. The workflow builds a signed APK and attaches it to a new GitHub Release.

It needs four repository secrets:
- `SIGNING_KEYSTORE_BASE64`: the `.jks` file, base64-encoded
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

For local release builds, create `keystore.properties` in the project root with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. It is ignored by git. Never commit the keystore.

Bump `versionCode` and `versionName` in `app/build.gradle.kts` before each release.

## How it works
The app talks to the camera directly through PTP with Canon's EOS extensions, which are documented by the libgphoto2 project.
1. It opens a session and enables live view output to the PC.
2. It repeatedly requests the viewfinder data (a JPEG of about 1024×576, plus the camera's histogram and AF frames).
3. It renders everything with an OpenGL ES 3.0 shader pipeline.

Live view over USB has a lower resolution than HDMI, so scopes and false color are a guide, not a reference.

Source layout (`app/src/main/java/io/github/almostmonitor/`):

| File | What it does |
|---|---|
| `PtpUsb.kt` | PTP transport over USB bulk |
| `CanonLiveViewController.kt` | Session, live view loop, camera events and commands |
| `CanonProps.kt` | Canon property codes and formatting |
| `MonitorRenderer.kt` | OpenGL renderer (LUT, peaking, zebras, false color, guides) |
| `CubeLut.kt` | `.cube` parser |
| `ScopeData.kt`, `ScopeView.kt`, `HistogramView.kt` | Scopes |
| `CameraModels.kt` | Model list and per-model connection notes |
| `MainActivity.kt` | UI |

## Privacy
The app has no internet permission. It doesn't collect or send any data. Settings and the reference frame stay on your phone.

## Contributing
Testing on other Canon models is the most useful help. Open an issue with your camera model and what works and what doesn't. A Logcat capture filtered by the `CanonPTP` tag helps a lot.

## License
[GNU GPL v3.0](LICENSE). You can use, study, share and modify the app. If you distribute a modified version, you must release its source code under the same license.

## Disclaimer
This is an independent project. It is not affiliated with, endorsed by, or supported by Canon Inc. "Canon" and "EOS" are trademarks of Canon Inc., used here only to describe compatibility. Use at your own risk.
