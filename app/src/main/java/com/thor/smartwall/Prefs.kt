package com.thor.smartwall

import android.content.Context
import android.content.SharedPreferences

enum class WallMode { STATIC, KEN_BURNS, VIDEO, GIF }

/**
 * Split-video smoothness, expressed the way a person thinks about it (Low/Medium/High) rather
 * than as raw frame counts. Each tier bundles a frame count and a per-frame sample resolution,
 * because those two together are what actually determine both smoothness AND memory use - the
 * two things a user is really trading off. Higher = smoother motion but more memory and a longer
 * one-time processing pass when you first apply the wallpaper.
 */
enum class VideoSmoothness(val frameCount: Int, val sampleWidth: Int, val sampleHeight: Int) {
    LOW(16, 640, 360),
    MEDIUM(32, 640, 360),
    HIGH(48, 854, 480)
}

/** Central config, read by both MainActivity (writer) and the wallpaper Engine (reader). */
object Prefs {
    private const val FILE = "thor_smartwall_prefs"

    private const val KEY_IMAGE_URI = "image_uri"
    private const val KEY_IMAGE_URI_2 = "image_uri_secondary" // used only in independent mode
    private const val KEY_VIDEO_URI = "video_uri"
    private const val KEY_GIF_URI = "gif_uri"
    private const val KEY_GIPHY_KEY = "giphy_api_key"
    private const val KEY_MODE = "mode"
    private const val KEY_GAP = "gap_fraction"
    private const val KEY_SWAP = "swap_order"
    private const val KEY_INDEPENDENT = "independent_mode"
    private const val KEY_EPOCH = "motion_epoch"
    private const val KEY_ORIENTATION = "orientation"
    private const val KEY_ROTATION_OVERRIDE = "rotation_override_degrees"
    private const val KEY_VIDEO_SMOOTH = "video_smooth_mode"
    private const val KEY_VIDEO_SMOOTHNESS = "video_smoothness_level"
    private const val KEY_SPLIT_VIDEO_TOP = "split_video_top_path"
    private const val KEY_SPLIT_VIDEO_BOTTOM = "split_video_bottom_path"

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var Context.imageUri: String?
        get() = sp(this).getString(KEY_IMAGE_URI, null)
        set(v) = sp(this).edit().putString(KEY_IMAGE_URI, v).apply()

    var Context.imageUriSecondary: String?
        get() = sp(this).getString(KEY_IMAGE_URI_2, null)
        set(v) = sp(this).edit().putString(KEY_IMAGE_URI_2, v).apply()

    var Context.videoUri: String?
        get() = sp(this).getString(KEY_VIDEO_URI, null)
        set(v) = sp(this).edit().putString(KEY_VIDEO_URI, v).apply()

    var Context.gifUri: String?
        get() = sp(this).getString(KEY_GIF_URI, null)
        set(v) = sp(this).edit().putString(KEY_GIF_URI, v).apply()

    var Context.giphyApiKey: String?
        get() = sp(this).getString(KEY_GIPHY_KEY, null)
        set(v) = sp(this).edit().putString(KEY_GIPHY_KEY, v).apply()

    var Context.mode: WallMode
        get() = WallMode.valueOf(sp(this).getString(KEY_MODE, WallMode.KEN_BURNS.name)!!)
        set(v) = sp(this).edit().putString(KEY_MODE, v.name).apply()

    var Context.gapFraction: Float
        get() = sp(this).getFloat(KEY_GAP, 0.035f)
        set(v) = sp(this).edit().putFloat(KEY_GAP, v).apply()

    var Context.swapOrder: Boolean
        get() = sp(this).getBoolean(KEY_SWAP, false)
        set(v) = sp(this).edit().putBoolean(KEY_SWAP, v).apply()

    var Context.independentMode: Boolean
        get() = sp(this).getBoolean(KEY_INDEPENDENT, true)
        set(v) = sp(this).edit().putBoolean(KEY_INDEPENDENT, v).apply()

    /**
     * Video has two honest tradeoffs, not one "correct" answer:
     * true  = smooth, full-framerate playback via MediaPlayer, but MediaPlayer can't crop a
     *         sub-region, so both screens show the identical full frame stretched to fit.
     * false = correctly split across the hinge (same crop math as images), but motion is a
     *         choppier frame-sampled slideshow rather than smooth video.
     * Defaults to the split behavior since that's what most people actually want for a
     * dual-screen wallpaper; flip it if you'd rather have smooth motion instead.
     */
    var Context.videoSmoothMode: Boolean
        get() = sp(this).getBoolean(KEY_VIDEO_SMOOTH, false)
        set(v) = sp(this).edit().putBoolean(KEY_VIDEO_SMOOTH, v).apply()

    /** Smoothness tier for the split-video slideshow. Defaults to LOW (the cheapest). */
    var Context.videoSmoothness: VideoSmoothness
        get() = VideoSmoothness.valueOf(
            sp(this).getString(KEY_VIDEO_SMOOTHNESS, VideoSmoothness.LOW.name)!!
        )
        set(v) = sp(this).edit().putString(KEY_VIDEO_SMOOTHNESS, v.name).apply()

    /**
     * Paths to the pre-cropped per-screen video files produced by VideoSplitTranscoder. When both
     * are set (and videoSmoothMode is off), the wallpaper plays these directly with MediaPlayer -
     * smooth AND split. Null when no pre-split has been done for the current video.
     */
    var Context.splitVideoTopPath: String?
        get() = sp(this).getString(KEY_SPLIT_VIDEO_TOP, null)
        set(v) = sp(this).edit().putString(KEY_SPLIT_VIDEO_TOP, v).apply()

    var Context.splitVideoBottomPath: String?
        get() = sp(this).getString(KEY_SPLIT_VIDEO_BOTTOM, null)
        set(v) = sp(this).edit().putString(KEY_SPLIT_VIDEO_BOTTOM, v).apply()

    var Context.orientationVertical: Boolean
        get() = sp(this).getBoolean(KEY_ORIENTATION, true)
        set(v) = sp(this).edit().putBoolean(KEY_ORIENTATION, v).apply()

    /**
     * Manual fallback on top of the auto-detected rotation fix (see SmartSplitWallpaperService).
     * Auto-detection infers the right compensation from the orientation setting plus what
     * DisplayManager reports, but I can't test the actual rotation *direction* (90 vs 270) on
     * real Thor hardware, so this lets the person flip it with one tap if it's backwards rather
     * than needing another code round-trip. Cycles 0 -> 90 -> 180 -> 270 -> 0.
     */
    var Context.rotationOverrideDegrees: Int
        get() = sp(this).getInt(KEY_ROTATION_OVERRIDE, 0)
        set(v) = sp(this).edit().putInt(KEY_ROTATION_OVERRIDE, v).apply()

    /**
     * Shared "time zero" for the Ken Burns motion and for video-loop resync.
     * Both wallpaper Engines (top screen, bottom screen) read the same epoch
     * from disk, so their independent animation loops stay phase-locked and
     * the pan/zoom or loop point feels like a single continuous motion
     * spanning both screens instead of two clocks drifting apart.
     */
    fun getOrCreateEpoch(context: Context): Long {
        val prefs = sp(context)
        val existing = prefs.getLong(KEY_EPOCH, -1L)
        if (existing > 0L) return existing
        val now = android.os.SystemClock.elapsedRealtime()
        prefs.edit().putLong(KEY_EPOCH, now).apply()
        return now
    }

    fun resetEpoch(context: Context) {
        sp(context).edit().putLong(KEY_EPOCH, android.os.SystemClock.elapsedRealtime()).apply()
    }
}
