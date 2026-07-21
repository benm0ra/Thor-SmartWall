# Thor Smart Split — a Wallpaper-Engine-style live wallpaper for the AYN Thor

A live wallpaper app that takes one image (or video) and splits it correctly
across the AYN Thor's two OLED panels (1080×1920 top / 1080×1240 bottom),
instead of the usual "same picture stretched twice, doesn't line up" result.

## Why you have to build this yourself

I put this together in a sandboxed environment with **no internet access and
no Android SDK installed**, so I could write and sanity-check all the source
correctly, but I couldn't actually invoke Gradle/AAPT/the Kotlin compiler to
spit out a signed `.apk`. The good news: the fix is one build step on your
end, with two easy options.

### Option A — Android Studio (recommended, ~2 minutes)
1. Install [Android Studio](https://developer.android.com/studio) if you don't have it.
2. `File → Open` → select the `ThorSmartWall` folder.
3. Let Gradle sync (first time downloads the Android SDK/build tools automatically).
4. `Build → Generate Signed Bundle / APK` (or just hit Run with the Thor plugged in via USB debugging) → you get `app-debug.apk` under `app/build/outputs/apk/debug/`.
5. Copy that APK to the Thor (USB, or a cloud drive) and install it (enable "Install unknown apps" for whatever app you use to open it).

### Option B — no local install at all
This repo already includes `.github/workflows/build-apk.yml`. Push it to a
GitHub repo (even just by dragging the unzipped folder into GitHub's web
upload page — no `git` command line needed) and the **Actions** tab will
build `app-debug.apk` for you automatically and let you download it.

## How to use it once installed
1. Open **Thor Smart Split**, pick an image.
2. Drag the **hinge gap** slider until the art lines up across the hinge in
   the live preview.
3. Pick **Slow pan/zoom**, **Static**, or **Looping video**.
4. Tap **Set as Live Wallpaper** → confirm in the system dialog.

If the bottom screen doesn't pick up the wallpaper (see caveat below), tap
**Export Split Images** instead, then assign `top_screen.png` /
`bottom_screen.png` manually through the system wallpaper picker for each
screen, if the Thor's launcher offers that (AYN's launcher supports basic
dual-screen wallpaper assignment through its Settings, based on public
reviews of the device).

## The one real technical unknown, stated plainly
Android has a documented flag, `android:supportsMultipleDisplays="true"`,
that tells the system "this live wallpaper knows how to render a different,
correctly-sized image per physical display," and the framework then creates
one `Engine` instance per display automatically. This app declares that flag
and implements it properly — each `Engine` asks `getDisplayContext()` for its
*own* display's real resolution and renders the right slice.

What I can't verify without the device in hand is whether **AYN's specific
custom launcher** actually requests a second wallpaper engine for the bottom
screen at all — that's OEM launcher behavior, not something documented
publicly, and Android's own docs note stock AOSP historically has no
guaranteed per-screen wallpaper selection UI. That's exactly why the
**Export** button exists as a no-dependency fallback: a plain PNG assigned
through whatever per-screen wallpaper option the Thor's Settings app exposes
will always work, live-wallpaper-engine support or not.

## What's "smart" about the split
- **Auto-detects real resolution** of every active display at draw time via
  `DisplayManager` — not hardcoded, so it keeps working if AYN ships a Thor
  variant with different panel sizes.
- **Continuous crop across the hinge**: the source image is scaled to fill one
  virtual canvas sized `combined width × (screen heights + gap)`, then sliced,
  so art lines up top-to-bottom instead of each screen getting an independent,
  misaligned center-crop.
- **Adjustable hinge-gap compensation** since the Thor is a clamshell with a
  real physical gap, unlike a foldable's continuous panel.
- **Ken Burns motion**, phase-locked across both screens from a shared clock
  stored in `SharedPreferences`, so the slow pan/zoom feels like one camera
  move spanning both panels rather than two engines drifting out of sync.
- **Independent mode** if you'd rather run two unrelated images.
- **Video mode**: loops an MP4 per screen with playback position aligned to
  that same shared clock. Note: video is filled/stretched to each screen
  independently (no seamless cross-hinge crop for video — that would need a
  full GPU transcode pipeline) — pick a source near 1080×1920 or 1080×1240
  for the least distortion, the same tradeoff real Wallpaper Engine makes for
  multi-monitor video wallpapers.
- **Animated GIF mode**: decodes with Android's built-in `ImageDecoder` (API
  28+, no extra library), same per-screen-independent tradeoff as video.

## Project layout
```
app/src/main/java/com/thor/smartwall/
  MainActivity.kt                  – picker UI, live dual-screen preview, export
  SmartSplitWallpaperService.kt    – the actual live wallpaper (one Engine per display)
  SplitEngine.kt                   – the crop/scale math, unit-testable, no Android deps beyond Bitmap
  ScreenSpec.kt                    – runtime display detection
  Prefs.kt                         – shared settings between the activity and the engine
```
