package com.thor.smartwall

import android.content.Context
import android.content.SharedPreferences

enum class WallMode { STATIC, KEN_BURNS, VIDEO, GIF }

/** Central config, read by both MainActivity (writer) and the wallpaper Engine (reader). */
object Prefs {
    private const val FILE = "thor_smartwall_prefs"

    private const val KEY_IMAGE_URI = "image_uri"
    private const val KEY_IMAGE_URI_2 = "image_uri_secondary" // used only in independent mode
    private const val KEY_VIDEO_URI = "video_uri"
    private const val KEY_GIF_URI = "gif_uri"
    private const val KEY_MODE = "mode"
    private const val KEY_GAP = "gap_fraction"
    private const val KEY_SWAP = "swap_order"
    private const val KEY_INDEPENDENT = "independent_mode"
    private const val KEY_EPOCH = "motion_epoch"
    private const val KEY_ORIENTATION = "orientation"

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
        get() = sp(this).getBoolean(KEY_INDEPENDENT, false)
        set(v) = sp(this).edit().putBoolean(KEY_INDEPENDENT, v).apply()

    var Context.orientationVertical: Boolean
        get() = sp(this).getBoolean(KEY_ORIENTATION, true)
        set(v) = sp(this).edit().putBoolean(KEY_ORIENTATION, v).apply()

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
