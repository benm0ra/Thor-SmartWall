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
import com.thor.smartwall.Prefs.swapOrder
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

    /** Debounced: coalesces rapid-fire calls (e.g. slider drag) into one redraw ~60ms later. */
    private fun renderPreview() {
        previewHandler.removeCallbacks(previewRunnable)
        previewHandler.postDelayed(previewRunnable, 60L)
    }

    private val pickPrimary = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { persistAndLoad(it, isSecondary = false) }
    }
    private val pickSecondary = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { persistAndLoad(it, isSecondary = true) }
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some providers still won't grant a lasting permission even through the
                // document picker; the video will still play for this app session, it just
                // may need re-picking after a reboot.
            }
            videoUri = it.toString()
            Toast.makeText(this, R.string.video_loaded, Toast.LENGTH_SHORT).show()
        }
    }
    private val pickGif = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Same caveat as the video picker above.
            }
            gifUri = it.toString()
            Toast.makeText(this, R.string.gif_loaded, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        restoreUiFromPrefs()
        loadExistingImages()

        binding.btnPickImage.setOnClickListener { pickPrimary.launch(arrayOf("image/*")) }
        binding.btnPickSecondary.setOnClickListener { pickSecondary.launch(arrayOf("image/*")) }
        binding.btnPickVideo.setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
        binding.btnPickGif.setOnClickListener { pickGif.launch(arrayOf("image/gif")) }
        binding.btnSearchGifs.setOnClickListener { startActivity(Intent(this, GifSearchActivity::class.java)) }

        binding.switchIndependent.setOnCheckedChangeListener { _, checked ->
            independentMode = checked
            binding.btnPickSecondary.isEnabled = checked
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
        binding.radioVideo.setOnClickListener { mode = WallMode.VIDEO }
        binding.radioGif.setOnClickListener { mode = WallMode.GIF }

        binding.btnApplyLive.setOnClickListener { applyAsLiveWallpaper() }
        binding.btnExport.setOnClickListener { exportSplitImages() }
        binding.btnDiagnostics.setOnClickListener { showScreenDiagnostics() }

        renderPreview()
    }

    override fun onResume() {
        super.onResume()
        restoreUiFromPrefs()
    }

    private fun restoreUiFromPrefs() {
        binding.switchIndependent.isChecked = independentMode
        binding.switchSwap.isChecked = swapOrder
        binding.switchVertical.isChecked = orientationVertical
        binding.btnPickSecondary.isEnabled = independentMode
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

    /** Draws a live simulation of the Thor's two screens with the current split settings applied. */
    private fun renderPreviewNow() {
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

    /** Caps the longest side so preview crops stay cheap no matter how big the source photo is. */
    private fun downscaleForPreview(bmp: Bitmap, maxDim: Int = 900): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= maxDim) return bmp
        val scale = maxDim.toFloat() / longest
        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
        val h = (bmp.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }

    private fun applyAsLiveWallpaper() {
        Prefs.resetEpoch(this)
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        intent.putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, SmartSplitWallpaperService::class.java)
        )
        try {
            startActivity(intent)
        } catch (_: Exception) {
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

    private fun savePng(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
