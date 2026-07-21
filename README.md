# ThorPaper

A live-wallpaper app for the **AYN Thor** dual-screen Android handheld. It takes
one photo, GIF, or video and displays it correctly across **both** screens —
cropped and aligned per screen instead of the usual "same image stretched twice,
doesn't line up" result you get from the stock wallpaper picker.

> **Status:** functional, in active user testing. Not yet on any app store — you
> build and sideload it yourself (see [Building](#building)).

---

## For users

### What it does

- **One wallpaper, both screens.** Pick any image, GIF, or video; ThorPaper sizes
  and crops it for the Thor's top (1920×1080) and bottom (1240×1080) panels.
- **Auto-detects media type.** Choose a file and it figures out whether it's a
  photo, GIF, or video and sets the right mode for you.
- **Live preview.** A small on-screen Thor shows how your wallpaper will look on
  both screens before you apply it — videos animate in the preview too.
- **Smooth split video.** Video can be prepared once so it plays back smoothly
  *and* correctly split across both screens (see [Video modes](#video-modes)).
- **Motion options.** Static, slow Ken-Burns pan/zoom, looping video, or animated GIF.
- **In-app GIF search** via GIPHY (bring your own free API key).
- **Keeps running.** Helps exempt itself from battery optimization so aggressive
  power managers don't stop the wallpaper in the background.

### First run

On first launch a short walkthrough explains the basics. You can skip it; it
won't show again. After that the home screen is a menu of tiles:

| Tile | What it's for |
|------|---------------|
| **Source** | Pick a photo, GIF, or video, or search GIFs online |
| **Screen Fit** | How the image is split/cropped between the two screens |
| **Motion** | Static, Ken Burns, video, or GIF — plus video smoothness |
| **Keep Running** | Battery-optimization exemption so it survives in the background |
| **Advanced** | Export split images, screen diagnostics, debug report, rotation fix |

Tap **Set as Live Wallpaper** to apply.

### Video modes

Video on a dual-screen device is a genuine tradeoff. ThorPaper gives you three paths:

1. **Prepared (recommended).** When you pick a video, choose **Prepare it**.
   ThorPaper crops the video into one file per screen *once* (a short one-time
   processing step), then plays each with a normal media player — **smooth and
   split**. Lightest on battery during playback.
2. **Smooth (Motion → Smooth toggle).** Plays the original video at full
   framerate but shows the same full frame on both screens (not a true split).
3. **Split fallback (choppy).** If you don't prepare the video, it's shown as a
   frame-sampled slideshow — correctly split but not smooth. The **smoothness**
   setting (Low/Medium/High) trades memory for more frames.

### Troubleshooting

- **Wallpaper is blank / didn't apply:** open ThorPaper → **Advanced → Create
  Debug Report**. It saves a text file to your **Downloads** folder with device
  info, screen details, your settings, and a recent event log. That file is the
  fastest way to diagnose an issue.
- **Split looks wrong / sideways:** **Advanced → Show Screen Info** reports what
  Android sees for each display. If it shows only one display, per-screen
  splitting has to happen at the OS level — use **Export Split Images** instead
  and set each PNG per screen through the system picker. The **Rotate
  Compensation** control is a manual escape hatch if art is rotated.
- **Settings reset after an update:** shouldn't happen anymore — the app ships a
  stable debug signing key so rebuilds upgrade in place. Switching *to* that key
  the first time needs one uninstall.

---

## For developers

### What it is

A single-module Android app (Kotlin) whose core is a
`WallpaperService.Engine` that renders a different, correctly-cropped view of one
source onto each physical display the Thor exposes.

### Architecture

| File | Responsibility |
|------|----------------|
| `MainActivity.kt` | The settings/home UI: tile navigation, pickers, live preview, apply, onboarding, debug report. |
| `SmartSplitWallpaperService.kt` | The `WallpaperService.Engine`. One engine instance per display; resolves which screen it's on via `getDisplayContext()` and renders that screen's crop. Handles static / Ken Burns / GIF / all video paths, screen-on/off + visibility gating. |
| `SplitEngine.kt` | Pure crop math. Turns one source bitmap into a per-screen crop (continuous-across-the-hinge or independent per screen). |
| `ScreenSpec.kt` | `DisplayDetector.findScreens()` — enumerates active displays via `DisplayManager` and orders them top→bottom. |
| `VideoSplitTranscoder.kt` | One-time pre-crop of a source video into per-screen MP4s (the "Prepared" video path). |
| `Prefs.kt` | Typed `SharedPreferences` wrapper (Context extension properties). |
| `DebugLog.kt` | Lightweight in-memory + file event log for field diagnostics. |
| `DebugReport.kt` | Assembles the user-generatable debug report and writes it to Downloads. |
| `GifSearchActivity.kt` | In-app GIPHY search + download. |

### How the split works

`DisplayManager` reports the Thor as two displays (`id=0` 1920×1080 top, `id=4`
1240×1080 bottom). The wallpaper framework creates one `Engine` per display;
each engine uses its per-display `Context` to learn which screen it is, then asks
`SplitEngine` for that screen's crop of the shared source. Playback across the
two engines is phase-locked to a shared epoch so motion stays in sync.

> **Note on the Thor's orientation:** the panels are used landscape (Switch
> style), and `DisplayManager`'s reported dimensions are already correct — an
> earlier assumption that they were portrait-rotated was wrong and has been
> removed. The manual rotation override remains only as an escape hatch.

### Dependencies

All resolved from Google's Maven and Maven Central:

| Dependency | Version | Why |
|------------|---------|-----|
| `androidx.core:core-ktx` | 1.13.1 | Kotlin extensions |
| `androidx.appcompat:appcompat` | 1.7.0 | `AppCompatActivity`, dialogs |
| `com.google.android.material:material` | 1.12.0 | Material 3 theme, switches, button toggle group |
| `androidx.activity:activity-ktx` | 1.9.1 | `OnBackPressedDispatcher` |
| `androidx.constraintlayout:constraintlayout` | 2.1.4 | Layout |
| `com.otaliastudios:transcoder` | 0.11.2 | Hardware-accelerated video cropping for the "Prepared" video path (MediaCodec-based, no FFmpeg). Apache-2.0. |

No native code, no FFmpeg, no server. Everything runs on-device.

### Build configuration

- `minSdk = 30` — needs `WallpaperService.Engine#getDisplayContext()` (API 29)
  and `Context#getDisplay()` (API 30). The Thor runs Android 13 (API 33).
- `targetSdk = 34`, `compileSdk = 34`, JDK 17, view binding on.
- A **debug keystore is checked into `app/keystore/`** on purpose so every build
  (CI or local) is signed identically and upgrades in place. It's debug-only and
  carries no security value.

### Permissions

`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` (and `READ_EXTERNAL_STORAGE` capped at
API 32) for picking media; `INTERNET` for GIPHY search;
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for background survival. No location,
camera, contacts, or write-storage permissions. `allowBackup` is disabled.

### Building

**Option A — Android Studio (recommended)**
1. Install [Android Studio](https://developer.android.com/studio).
2. `File → Open` → select the `ThorSmartWall` folder; let Gradle sync (it
   downloads the SDK/build-tools automatically).
3. Run on a connected Thor (USB debugging) or `Build → Build APK(s)` and grab
   `app/build/outputs/apk/debug/app-debug.apk`.

**Option B — GitHub Actions (no local toolchain)**
The repo includes `.github/workflows/build-apk.yml`. Push the project to a
GitHub repo (the web "Add file → Upload files" drag-drop works) and the
**Actions** tab builds `app-debug.apk` as a downloadable artifact.

> The hidden `.github` folder can be skipped by macOS Finder drag-drop. If the
> workflow doesn't appear, create it manually via GitHub's web editor.

### Known constraints

- **Prepared video uses independent per-screen crops** (Smart Fit style), not a
  continuous-across-the-hinge split — a consequence of cropping two files
  separately.
- **Some video codecs can't be re-encoded** on a given device; preparation fails
  gracefully with a message and the choppy/smooth paths remain available.
- **Per-screen live wallpaper depends on the launcher** handing the service a
  second display. If a device exposes only one display, use Export Split Images.

### License

Personal project. The bundled Transcoder library is Apache-2.0.
