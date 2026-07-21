package com.thor.smartwall

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.thor.smartwall.Prefs.gapFraction
import com.thor.smartwall.Prefs.gifUri
import com.thor.smartwall.Prefs.imageUri
import com.thor.smartwall.Prefs.imageUriSecondary
import com.thor.smartwall.Prefs.independentMode
import com.thor.smartwall.Prefs.mode
import com.thor.smartwall.Prefs.orientationVertical
import com.thor.smartwall.Prefs.rotationOverrideDegrees
import com.thor.smartwall.Prefs.swapOrder
import com.thor.smartwall.Prefs.videoSmoothMode
import com.thor.smartwall.Prefs.videoSmoothness
import com.thor.smartwall.Prefs.splitVideoTopPath
import com.thor.smartwall.Prefs.splitVideoBottomPath
import com.thor.smartwall.Prefs.videoUri
import com.thor.smartwall.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var primaryBitmap: Bitmap? = null
    private var secondaryBitmap: Bitmap? = null

    // Small downscaled copies used ONLY for the live in-app preview. Recomputing a crop from a
    // 12MP camera photo on every pixel of slider drag is what made the UI feel unresponsive;
    // these keep every preview redraw cheap regardless of how large the original photo is.
    private var previewPrimary: Bitmap? = null
    private var previewSecondary: Bitmap? = null

    private val previewHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val previewRunnable = Runnable { renderPreviewNow() }

    // Animated video preview: a handful of pre-cropped (top,bottom) frame pairs cycled on a timer,
    // so the preview actually shows the motion instead of a frozen still. Kept small and downscaled
    // since it's just a thumbnail-sized loop. A generation counter cancels a stale sampling job if
    // the user changes settings/media before it finishes.
    private var previewVideoFrames: List<Pair<Bitmap?, Bitmap?>> = emptyList()
    private var previewVideoIndex = 0
    private var previewVideoGeneration = 0
    private val previewVideoHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val previewVideoTick = object : Runnable {
        override fun run() {
            val frames = previewVideoFrames
            if (frames.isEmpty()) return
            val pair = frames[previewVideoIndex % frames.size]
            binding.previewTop.setImageBitmap(pair.first)
            binding.previewBottom.setImageBitmap(pair.second)
            previewVideoIndex++
            previewVideoHandler.postDelayed(this, 350L) // ~3 fps: enough to read as motion in a thumbnail
        }
    }

    private fun stopPreviewVideoLoop() {
        previewVideoHandler.removeCallbacks(previewVideoTick)
    }

    // Live status-bar clock, 3DS-style. Ticks while the screen is on the settings UI.
    private val clockHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val clockFormat = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
    private val clockRunnable = object : Runnable {
        override fun run() {
            binding.statusClock.text = clockFormat.format(java.util.Date())
            clockHandler.postDelayed(this, 15_000L)
        }
    }

    // ViewFlipper child indices, in layout order.
    private val homeIndex = 0
    private val subTitles by lazy {
        mapOf(
            1 to getString(R.string.section_source),
            2 to getString(R.string.section_fit),
            3 to getString(R.string.section_motion),
            4 to getString(R.string.section_background),
            5 to getString(R.string.section_advanced)
        )
    }

    /** Flip to a sub-screen (or home) and update the status bar's title + back button. */
    private fun showScreen(index: Int) {
        binding.flipper.displayedChild = index
        if (index == homeIndex) {
            binding.statusTitle.text = getString(R.string.app_name)
            binding.btnBack.visibility = android.view.View.GONE
            binding.statusIcon.visibility = android.view.View.VISIBLE
            renderPreview()
        } else {
            binding.statusTitle.text = subTitles[index] ?: getString(R.string.app_name)
            binding.btnBack.visibility = android.view.View.VISIBLE
            binding.statusIcon.visibility = android.view.View.GONE
        }
    }

    /** Debounced: coalesces rapid-fire calls (e.g. slider drag) into one redraw ~60ms later. */
    private fun renderPreview() {
        previewHandler.removeCallbacks(previewRunnable)
        previewHandler.postDelayed(previewRunnable, 60L)
    }

    private val pickSecondary = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { persistAndLoad(it, isSecondary = true) }
    }

    /**
     * One picker for images, GIFs, and videos - it inspects the actual MIME type of whatever
     * was picked (not just the file extension) and routes to the right storage + mode + preview
     * automatically, instead of making you pick the right button first.
     */
    private val pickWallpaper = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { handlePickedWallpaper(it) }
    }

    private fun handlePickedWallpaper(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some providers won't grant a lasting permission even through the document picker;
            // it'll still work for this session, it just may need re-picking after a reboot.
        }
        val type = contentResolver.getType(uri) ?: ""
        when {
            type == "image/gif" -> {
                gifUri = uri.toString()
                mode = WallMode.GIF
                restoreUiFromPrefs()
                renderPreview()
                Toast.makeText(this, R.string.gif_loaded, Toast.LENGTH_SHORT).show()
            }
            type.startsWith("video/") -> {
                videoUri = uri.toString()
                mode = WallMode.VIDEO
                // Any previously pre-split files belong to the OLD video - discard them.
                clearSplitVideoFiles()
                restoreUiFromPrefs()
                renderPreview()
                Toast.makeText(this, R.string.video_loaded, Toast.LENGTH_SHORT).show()
                promptPreSplitVideo(uri)
            }
            type.startsWith("image/") -> {
                if (mode == WallMode.GIF || mode == WallMode.VIDEO) {
                    mode = WallMode.KEN_BURNS
                }
                persistAndLoad(uri, isSecondary = false)
                restoreUiFromPrefs()
            }
            else -> {
                Toast.makeText(this, R.string.unsupported_file_type, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.init(this)
        DebugLog.d("MainActivity.onCreate")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        restoreUiFromPrefs()
        loadExistingImages()

        binding.btnPickWallpaper.setOnClickListener { pickWallpaper.launch(arrayOf("image/*", "video/*")) }
        binding.btnPickSecondary.setOnClickListener { pickSecondary.launch(arrayOf("image/*")) }
        binding.btnSearchGifs.setOnClickListener { startActivity(Intent(this, GifSearchActivity::class.java)) }

        binding.switchIndependent.setOnCheckedChangeListener { _, checked ->
            independentMode = checked
            binding.btnPickSecondary.isEnabled = checked
            binding.seekGap.isEnabled = !checked
            binding.labelHingeGap.alpha = if (checked) 0.4f else 1f
            renderPreview()
        }
        binding.switchSwap.setOnCheckedChangeListener { _, checked ->
            swapOrder = checked
            renderPreview()
        }
        binding.switchVertical.setOnCheckedChangeListener { _, checked ->
            orientationVertical = checked
            renderPreview()
        }

        binding.seekGap.max = 150
        binding.seekGap.progress = (gapFraction * 1000).toInt()
        binding.seekGap.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                gapFraction = progress / 1000f
                if (fromUser) renderPreview()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.radioStatic.setOnClickListener { mode = WallMode.STATIC; renderPreview() }
        binding.radioKenBurns.setOnClickListener { mode = WallMode.KEN_BURNS; renderPreview() }
        binding.radioVideo.setOnClickListener { mode = WallMode.VIDEO; renderPreview() }
        binding.radioGif.setOnClickListener { mode = WallMode.GIF; renderPreview() }
        binding.switchVideoSmooth.setOnCheckedChangeListener { _, checked ->
            videoSmoothMode = checked
            updateSmoothnessVisibility()
            renderPreview()
        }
        binding.toggleSmoothness.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            videoSmoothness = when (checkedId) {
                R.id.smoothMedium -> VideoSmoothness.MEDIUM
                R.id.smoothHigh -> VideoSmoothness.HIGH
                else -> VideoSmoothness.LOW
            }
        }

        binding.btnApplyLive.setOnClickListener { applyAsLiveWallpaper() }
        binding.btnExport.setOnClickListener { exportSplitImages() }
        binding.btnDiagnostics.setOnClickListener { showScreenDiagnostics() }
        binding.btnBatteryOptimization.setOnClickListener { requestBatteryExemption() }
        binding.btnRotationFix.setOnClickListener {
            rotationOverrideDegrees = (rotationOverrideDegrees + 90) % 360
            updateRotationLabel()
        }
        binding.btnDebugReport.setOnClickListener { createDebugReport() }

        // Home-menu tile navigation
        binding.tileSource.setOnClickListener { showScreen(1) }
        binding.tileFit.setOnClickListener { showScreen(2) }
        binding.tileMotion.setOnClickListener { showScreen(3) }
        binding.tilePower.setOnClickListener { showScreen(4) }
        binding.tileAdvanced.setOnClickListener { showScreen(5) }
        binding.btnBack.setOnClickListener { showScreen(homeIndex) }

        // System back returns to the home menu before exiting the app
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.flipper.displayedChild != homeIndex) {
                    showScreen(homeIndex)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        showScreen(homeIndex)
        renderPreview()
    }

    override fun onResume() {
        super.onResume()
        restoreUiFromPrefs()
        clockHandler.removeCallbacks(clockRunnable)
        clockHandler.post(clockRunnable)
        renderPreview() // re-samples/animates the video preview if we're on video mode
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
        stopPreviewVideoLoop() // don't animate while backgrounded
    }

    /** The smoothness tiers only affect split video; hide them when Smooth (duplicated) is on. */
    private fun createDebugReport() {
        val result = DebugReport.saveToDownloads(this)
        if (result.success) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.create_debug_report)
                .setMessage(getString(R.string.debug_report_saved, result.location))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } else {
            Toast.makeText(this, getString(R.string.debug_report_failed, result.location), Toast.LENGTH_LONG).show()
        }
    }

    private fun updateSmoothnessVisibility() {
        binding.smoothnessGroup.visibility =
            if (videoSmoothMode) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun updateRotationLabel() {
        binding.labelRotationValue.text = getString(R.string.rotation_current_value, rotationOverrideDegrees)
    }

    private fun restoreUiFromPrefs() {
        updateRotationLabel()
        binding.switchIndependent.isChecked = independentMode
        binding.switchSwap.isChecked = swapOrder
        binding.switchVertical.isChecked = orientationVertical
        binding.btnPickSecondary.isEnabled = independentMode
        binding.seekGap.isEnabled = !independentMode
        binding.labelHingeGap.alpha = if (independentMode) 0.4f else 1f
        binding.switchVideoSmooth.isChecked = videoSmoothMode
        when (videoSmoothness) {
            VideoSmoothness.LOW -> binding.toggleSmoothness.check(R.id.smoothLow)
            VideoSmoothness.MEDIUM -> binding.toggleSmoothness.check(R.id.smoothMedium)
            VideoSmoothness.HIGH -> binding.toggleSmoothness.check(R.id.smoothHigh)
        }
        updateSmoothnessVisibility()
        when (mode) {
            WallMode.STATIC -> binding.radioStatic.isChecked = true
            WallMode.KEN_BURNS -> binding.radioKenBurns.isChecked = true
            WallMode.VIDEO -> binding.radioVideo.isChecked = true
            WallMode.GIF -> binding.radioGif.isChecked = true
        }
    }

    private fun loadExistingImages() {
        imageUri?.let {
            primaryBitmap = loadBitmapSafely(Uri.parse(it))
            previewPrimary = primaryBitmap?.let { b -> downscaleForPreview(b) }
        }
        imageUriSecondary?.let {
            secondaryBitmap = loadBitmapSafely(Uri.parse(it))
            previewSecondary = secondaryBitmap?.let { b -> downscaleForPreview(b) }
        }
    }

    private fun persistAndLoad(uri: Uri, isSecondary: Boolean) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some providers (e.g. certain gallery apps) don't grant persistable permission;
            // the picked image still works for this session via the returned content Uri.
        }
        val bmp = loadBitmapSafely(uri) ?: run {
            Toast.makeText(this, R.string.could_not_load_image, Toast.LENGTH_SHORT).show()
            return
        }
        if (isSecondary) {
            secondaryBitmap = bmp
            previewSecondary = downscaleForPreview(bmp)
            imageUriSecondary = uri.toString()
        } else {
            primaryBitmap = bmp
            previewPrimary = downscaleForPreview(bmp)
            imageUri = uri.toString()
        }
        renderPreview()
    }

    private fun loadBitmapSafely(uri: Uri): Bitmap? = try {
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) {
        null
    }

    /** Draws a live simulation of the Thor's two screens reflecting whatever is actually picked right now. */
    private fun renderPreviewNow() {
        updateEmptyHint()
        if (mode != WallMode.VIDEO) stopPreviewVideoLoop()
        when (mode) {
            WallMode.VIDEO -> renderVideoPreview()
            WallMode.GIF -> renderGifPreview()
            else -> renderImagePreview()
        }
    }

    /** Shows the "pick something" nudge only when the current mode has no media chosen yet. */
    private fun updateEmptyHint() {
        val hasMedia = when (mode) {
            WallMode.VIDEO -> videoUri != null
            WallMode.GIF -> gifUri != null
            else -> imageUri != null
        }
        binding.previewEmptyHint.visibility = if (hasMedia) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun renderImagePreview() {
        val screens = DisplayDetector.PREVIEW_FALLBACK
        val orientation = if (orientationVertical) StackOrientation.VERTICAL else StackOrientation.HORIZONTAL

        val crops: Map<Int, Bitmap> = when {
            previewPrimary == null -> emptyMap()
            independentMode -> {
                val sources = mapOf(
                    screens[0].displayId to previewPrimary!!,
                    screens[1].displayId to (previewSecondary ?: previewPrimary!!)
                )
                SplitEngine.computeIndependentCrops(sources, screens)
            }
            else -> SplitEngine.computeCrops(previewPrimary!!, screens, orientation, gapFraction)
        }

        binding.previewTop.setImageBitmap(crops[screens[0].displayId])
        binding.previewBottom.setImageBitmap(crops[screens[1].displayId])
    }

    /**
     * Animated video preview: samples several frames across the clip in the background (one
     * MediaMetadataRetriever pass), crops each through the SAME split math the live wallpaper
     * uses, then loops the pre-cropped pairs on a timer so the preview shows the actual motion
     * and the actual split - matching what you'll get once applied.
     */
    private fun renderVideoPreview() {
        val uriStr = videoUri
        if (uriStr == null) {
            stopPreviewVideoLoop()
            binding.previewTop.setImageBitmap(null)
            binding.previewBottom.setImageBitmap(null)
            return
        }
        val smooth = videoSmoothMode
        val orientation = if (orientationVertical) StackOrientation.VERTICAL else StackOrientation.HORIZONTAL
        val gap = gapFraction
        val generation = ++previewVideoGeneration
        val previewFrameCount = 10 // small: it's a thumbnail-sized loop, not the real wallpaper

        Thread {
            val pairs = try {
                android.media.MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(this, Uri.parse(uriStr))
                    val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()?.coerceAtLeast(1L) ?: 1000L
                    val screens = DisplayDetector.PREVIEW_FALLBACK
                    val out = mutableListOf<Pair<Bitmap?, Bitmap?>>()
                    for (i in 0 until previewFrameCount) {
                        if (generation != previewVideoGeneration) break
                        val timeUs = durationMs * 1000L * i.toLong() / previewFrameCount.toLong()
                        val raw = try {
                            retriever.getScaledFrameAtTime(
                                timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270
                            )
                        } catch (_: Throwable) {
                            null
                        } ?: retriever.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        if (raw != null) {
                            val crops = if (smooth) {
                                SplitEngine.computeIndependentCrops(
                                    mapOf(screens[0].displayId to raw, screens[1].displayId to raw), screens
                                )
                            } else {
                                SplitEngine.computeCrops(raw, screens, orientation, gap)
                            }
                            out.add(crops[screens[0].displayId] to crops[screens[1].displayId])
                        }
                    }
                    out
                }
            } catch (t: Throwable) {
                emptyList()
            }
            runOnUiThread {
                if (generation != previewVideoGeneration) return@runOnUiThread
                stopPreviewVideoLoop()
                if (pairs.isEmpty()) {
                    binding.previewTop.setImageBitmap(null)
                    binding.previewBottom.setImageBitmap(null)
                    return@runOnUiThread
                }
                previewVideoFrames = pairs
                previewVideoIndex = 0
                if (pairs.size == 1) {
                    // Only one frame could be extracted - just show it, nothing to animate.
                    binding.previewTop.setImageBitmap(pairs[0].first)
                    binding.previewBottom.setImageBitmap(pairs[0].second)
                } else {
                    previewVideoHandler.post(previewVideoTick)
                }
            }
        }.start()
    }

    /**
     * GIF preview: decodes the first frame in the background and just relies on the ImageViews'
     * own centerCrop scaleType, since GIF always fills each screen independently (same as the
     * live wallpaper's actual behavior) rather than doing a continuous split.
     */
    private fun renderGifPreview() {
        val uriStr = gifUri
        if (uriStr == null) {
            binding.previewTop.setImageBitmap(null)
            binding.previewBottom.setImageBitmap(null)
            return
        }
        Thread {
            val frame = try {
                contentResolver.openInputStream(Uri.parse(uriStr))?.use { BitmapFactory.decodeStream(it) }
            } catch (t: Throwable) {
                null
            }
            runOnUiThread {
                binding.previewTop.setImageBitmap(frame)
                binding.previewBottom.setImageBitmap(frame)
            }
        }.start()
    }

    /** Caps the longest side so preview crops stay cheap no matter how big the source photo is. */
    private fun downscaleForPreview(bmp: Bitmap, maxDim: Int = 900): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= maxDim) return bmp
        val scale = maxDim.toFloat() / longest
        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
        val h = (bmp.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }

    private fun clearSplitVideoFiles() {
        splitVideoTopPath = null
        splitVideoBottomPath = null
        runCatching { VideoSplitTranscoder.outputFileFor(this, "top").delete() }
        runCatching { VideoSplitTranscoder.outputFileFor(this, "bottom").delete() }
    }

    /**
     * Offers the one-time pre-split so the video can play back smooth AND split. This is the
     * heavier, higher-quality path; it's optional because the choppy-but-instant frame-sampled
     * path still works if the user doesn't want to wait.
     */
    private fun promptPreSplitVideo(uri: Uri) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.presplit_title)
            .setMessage(R.string.presplit_message)
            .setPositiveButton(R.string.presplit_confirm) { _, _ -> startPreSplit(uri) }
            .setNegativeButton(R.string.presplit_skip, null)
            .show()
    }

    private fun startPreSplit(uri: Uri) {
        val screens = DisplayDetector.PREVIEW_FALLBACK
        val progress = android.app.ProgressDialog(this).apply {
            setTitle(getString(R.string.presplit_working_title))
            setMessage(getString(R.string.presplit_working_message))
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }
        DebugLog.d("Pre-split requested for $uri")
        VideoSplitTranscoder.split(
            context = this,
            sourceUri = uri,
            topWidth = screens[0].widthPx,
            topHeight = screens[0].heightPx,
            bottomWidth = screens[1].widthPx,
            bottomHeight = screens[1].heightPx,
            callback = object : VideoSplitTranscoder.Callback {
                override fun onProgress(fraction: Double) {
                    progress.progress = (fraction * 100).toInt()
                }

                override fun onComplete(topFile: java.io.File, bottomFile: java.io.File) {
                    splitVideoTopPath = topFile.absolutePath
                    splitVideoBottomPath = bottomFile.absolutePath
                    runCatching { progress.dismiss() }
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.presplit_done_title)
                        .setMessage(R.string.presplit_done_message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }

                override fun onError(message: String) {
                    clearSplitVideoFiles()
                    runCatching { progress.dismiss() }
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.presplit_failed_title)
                        .setMessage(getString(R.string.presplit_failed_message, message))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        )
    }

    private fun applyAsLiveWallpaper() {
        DebugLog.d("Apply tapped: mode=${mode}, smart=${independentMode}, vertical=${orientationVertical}, swap=${swapOrder}, smooth=${videoSmoothMode}")
        Prefs.resetEpoch(this)
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        intent.putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, SmartSplitWallpaperService::class.java)
        )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            DebugLog.e("Failed to open live wallpaper picker", e)
            Toast.makeText(this, R.string.open_wallpaper_picker_manually, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Bakes the exact per-screen crop to PNG files the user can hand to the system's own
     * "set wallpaper" flow for each screen individually. This is the safety net for the case
     * where the Thor's launcher doesn't request a second live-wallpaper Engine for the bottom
     * panel - a real, documented gap in stock Android (there's no OS-level guarantee a launcher
     * offers per-screen wallpaper selection at all), so a plain exported image always works.
     */
    private fun exportSplitImages() {
        val bmp = primaryBitmap
        if (bmp == null) {
            Toast.makeText(this, R.string.pick_image_first, Toast.LENGTH_SHORT).show()
            return
        }
        val screens = DisplayDetector.PREVIEW_FALLBACK
        val orientation = if (orientationVertical) StackOrientation.VERTICAL else StackOrientation.HORIZONTAL
        val crops = if (independentMode) {
            val sources = mapOf(
                screens[0].displayId to bmp,
                screens[1].displayId to (secondaryBitmap ?: bmp)
            )
            SplitEngine.computeIndependentCrops(sources, screens)
        } else {
            SplitEngine.computeCrops(bmp, screens, orientation, gapFraction)
        }

        try {
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "ThorSmartWall")
            dir.mkdirs()
            val topFile = File(dir, "top_screen.png")
            val bottomFile = File(dir, "bottom_screen.png")
            crops[screens[0].displayId]?.let { savePng(it, topFile) }
            crops[screens[1].displayId]?.let { savePng(it, bottomFile) }
            Toast.makeText(
                this,
                getString(R.string.exported_to, dir.absolutePath),
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Shows exactly what Android's DisplayManager reports system-wide, right now. This is the
     * one thing that settles the real open question about the Thor: whether the OS actually
     * exposes two separate displays (which is what our per-Engine splitting depends on) or only
     * one. Run this with the Thor open and both screens active.
     */
    private fun showScreenDiagnostics() {
        val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
        val displays = dm.displays
        val sb = StringBuilder()
        sb.append("Android reports ${displays.size} display(s):\n\n")
        for (d in displays) {
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            d.getRealSize(point)
            sb.append("• id=${d.displayId}  \"${d.name}\"  ${point.x}×${point.y}px  state=${d.state}\n")
        }
        if (displays.size < 2) {
            sb.append(
                "\nOnly one display showed up. That means the bottom screen isn't a " +
                    "second Android display at all (likely the launcher/firmware composes both " +
                    "panels from a single combined framebuffer), so a per-screen live wallpaper " +
                    "Engine can never be handed the bottom half separately - that has to happen " +
                    "at the OS/launcher level, not in this app. The Export button's PNGs are the " +
                    "correct workaround for this case."
            )
        } else {
            sb.append(
                "\nTwo or more displays showed up - the app's per-display splitting should be " +
                    "reaching the bottom screen. If it still looks wrong with this many displays " +
                    "detected, the bug is in how we're matching Engine to display, not a platform " +
                    "limitation - let me know these numbers and I'll dig into that instead."
            )
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.screen_diagnostics_title)
            .setMessage(sb.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * Once applied, the wallpaper Engine is a genuine Android system service and keeps running
     * whether or not this Activity is open - that part needs no code from us. What this button
     * actually addresses is a different, real problem: some OEM battery managers aggressively
     * kill backgrounded app processes (including, sometimes, the process hosting a wallpaper
     * service) to save power. Asking to be exempted from battery optimization is the one thing
     * an app is allowed to do about that; it can't grant itself immunity beyond this.
     */
    private fun requestBatteryExemption() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, R.string.battery_already_exempt, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.battery_exempt_request_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun savePng(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
