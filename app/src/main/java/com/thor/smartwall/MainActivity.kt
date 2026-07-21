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

    private val pickPrimary = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { persistAndLoad(it, isSecondary = false) }
    }
    private val pickSecondary = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { persistAndLoad(it, isSecondary = true) }
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            videoUri = it.toString()
            Toast.makeText(this, R.string.video_loaded, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        restoreUiFromPrefs()
        loadExistingImages()

        binding.btnPickImage.setOnClickListener { pickPrimary.launch("image/*") }
        binding.btnPickSecondary.setOnClickListener { pickSecondary.launch("image/*") }
        binding.btnPickVideo.setOnClickListener { pickVideo.launch("video/*") }

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

        binding.btnApplyLive.setOnClickListener { applyAsLiveWallpaper() }
        binding.btnExport.setOnClickListener { exportSplitImages() }

        renderPreview()
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
        }
    }

    private fun loadExistingImages() {
        imageUri?.let { primaryBitmap = loadBitmapSafely(Uri.parse(it)) }
        imageUriSecondary?.let { secondaryBitmap = loadBitmapSafely(Uri.parse(it)) }
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
            imageUriSecondary = uri.toString()
        } else {
            primaryBitmap = bmp
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
    private fun renderPreview() {
        val screens = DisplayDetector.PREVIEW_FALLBACK
        val orientation = if (orientationVertical) StackOrientation.VERTICAL else StackOrientation.HORIZONTAL

        val crops: Map<Int, Bitmap> = when {
            primaryBitmap == null -> emptyMap()
            independentMode -> {
                val sources = mapOf(
                    screens[0].displayId to primaryBitmap!!,
                    screens[1].displayId to (secondaryBitmap ?: primaryBitmap!!)
                )
                SplitEngine.computeIndependentCrops(sources, screens)
            }
            else -> SplitEngine.computeCrops(primaryBitmap!!, screens, orientation, gapFraction)
        }

        binding.previewTop.setImageBitmap(crops[screens[0].displayId])
        binding.previewBottom.setImageBitmap(crops[screens[1].displayId])
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

    private fun savePng(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
