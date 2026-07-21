package com.thor.smartwall

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.Display
import android.view.SurfaceHolder
import androidx.core.net.toUri
import com.thor.smartwall.Prefs.gapFraction
import com.thor.smartwall.Prefs.imageUri
import com.thor.smartwall.Prefs.imageUriSecondary
import com.thor.smartwall.Prefs.independentMode
import com.thor.smartwall.Prefs.mode
import com.thor.smartwall.Prefs.orientationVertical
import com.thor.smartwall.Prefs.swapOrder
import com.thor.smartwall.Prefs.videoUri
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class SmartSplitWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = SmartEngine()

    inner class SmartEngine : Engine() {

        private val handler = Handler(Looper.getMainLooper())
        private var visible = false
        private var mediaPlayer: MediaPlayer? = null

        // Everything below is resolved lazily once we know which display we're on.
        private var myScreen: ScreenSpec? = null
        private var allScreens: List<ScreenSpec> = emptyList()
        private var readyBitmap: Bitmap? = null

        private val kenBurnsPeriodMs = 26_000L
        private val overscan = 0.16f

        private val drawRunnable = object : Runnable {
            override fun run() {
                drawFrame()
                if (visible) {
                    handler.postDelayed(this, 33L) // ~30fps, plenty smooth for slow pan/zoom
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            resolveScreenGeometry()
            prepareContentForCurrentMode(holder)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            resolveScreenGeometry()
            prepareContentForCurrentMode(holder)
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible) {
                handler.removeCallbacks(drawRunnable)
                handler.post(drawRunnable)
                mediaPlayer?.start()
            } else {
                handler.removeCallbacks(drawRunnable)
                mediaPlayer?.pause()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            handler.removeCallbacks(drawRunnable)
            mediaPlayer?.release()
            mediaPlayer = null
        }

        override fun onDestroy() {
            super.onDestroy()
            handler.removeCallbacks(drawRunnable)
            mediaPlayer?.release()
            mediaPlayer = null
        }

        /** Figures out which physical display this Engine instance belongs to, using the
         *  real, per-display Context that getDisplayContext() gives us. This is the crux of
         *  the whole "correctly split across two screens" feature. */
        private fun resolveScreenGeometry() {
            val displayContext = getDisplayContext() ?: applicationContext
            val display: Display? = displayContext.display
            val swap = applicationContext.swapOrder
            allScreens = DisplayDetector.findScreens(applicationContext, swap)
            val myId = display?.displayId ?: Display.DEFAULT_DISPLAY
            myScreen = allScreens.firstOrNull { it.displayId == myId }
                ?: allScreens.firstOrNull()
        }

        private fun prepareContentForCurrentMode(holder: SurfaceHolder) {
            val ctx = applicationContext
            when (ctx.mode) {
                WallMode.VIDEO -> setupVideo(holder)
                WallMode.STATIC, WallMode.KEN_BURNS -> setupImage()
            }
        }

        private fun setupImage() {
            mediaPlayer?.release()
            mediaPlayer = null

            val ctx = applicationContext
            val screen = myScreen ?: return
            val overscanAmount = if (ctx.mode == WallMode.KEN_BURNS) overscan else 0f

            readyBitmap = try {
                if (ctx.independentMode) {
                    val primary = loadBitmap(ctx.imageUri) ?: return
                    val secondary = loadBitmap(ctx.imageUriSecondary) ?: primary
                    val sources = mapOf(
                        (allScreens.getOrNull(0)?.displayId ?: 0) to primary,
                        (allScreens.getOrNull(1)?.displayId ?: 1) to secondary
                    )
                    SplitEngine.computeIndependentCrops(sources, allScreens, overscanAmount)[screen.displayId]
                } else {
                    val source = loadBitmap(ctx.imageUri) ?: return
                    val orientation = if (ctx.orientationVertical) StackOrientation.VERTICAL else StackOrientation.HORIZONTAL
                    SplitEngine.computeCrops(source, allScreens, orientation, ctx.gapFraction, overscanAmount)[screen.displayId]
                }
            } catch (t: Throwable) {
                null
            }
            drawFrame()
        }

        private fun setupVideo(holder: SurfaceHolder) {
            readyBitmap = null
            val ctx = applicationContext
            val uriStr = ctx.videoUri ?: return
            try {
                mediaPlayer?.release()
                val mp = MediaPlayer()
                mp.setDataSource(ctx, uriStr.toUri())
                mp.setSurface(holder.surface)
                mp.isLooping = true
                mp.setVolume(0f, 0f)
                mp.setOnPreparedListener {
                    // Align every engine's playback position to the same shared epoch so
                    // top and bottom screens loop in lockstep instead of drifting apart.
                    val epoch = Prefs.getOrCreateEpoch(ctx)
                    val elapsed = SystemClock.elapsedRealtime() - epoch
                    val duration = it.duration.takeIf { d -> d > 0 } ?: 1
                    val seekTo = (elapsed % duration).toInt()
                    it.seekTo(seekTo)
                    if (visible) it.start()
                }
                mp.prepareAsync()
                mediaPlayer = mp
            } catch (t: Throwable) {
                mediaPlayer = null
            }
        }

        private fun loadBitmap(uriStr: String?): Bitmap? {
            if (uriStr == null) return null
            return try {
                val uri: Uri = uriStr.toUri()
                applicationContext.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
            } catch (t: Throwable) {
                null
            }
        }

        private fun drawFrame() {
            val ctx = applicationContext
            if (ctx.mode == WallMode.VIDEO) return // MediaPlayer draws directly to the surface

            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                canvas?.let { c ->
                    c.drawColor(Color.BLACK)
                    val bmp = readyBitmap
                    if (bmp != null) {
                        val matrix = if (ctx.mode == WallMode.KEN_BURNS) kenBurnsMatrix(bmp, c.width, c.height)
                        else fitMatrix(bmp, c.width, c.height)
                        c.drawBitmap(bmp, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                    }
                }
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas)
            }
        }

        /** Straight fill: bitmap already matches the surface size (this is the STATIC path). */
        private fun fitMatrix(bmp: Bitmap, w: Int, h: Int): Matrix {
            val scale = min(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
            return Matrix().apply {
                setScale(scale, scale)
                postTranslate((w - bmp.width * scale) / 2f, (h - bmp.height * scale) / 2f)
            }
        }

        /**
         * Slow, gentle pan/zoom sampled from a shared clock (see Prefs.getOrCreateEpoch),
         * so every screen's independent draw loop moves through the same motion "phase"
         * at the same wall-clock moment - the illusion of one continuous camera move.
         */
        private fun kenBurnsMatrix(bmp: Bitmap, w: Int, h: Int): Matrix {
            val epoch = Prefs.getOrCreateEpoch(applicationContext)
            val t = ((SystemClock.elapsedRealtime() - epoch) % kenBurnsPeriodMs).toFloat() / kenBurnsPeriodMs
            val angle = (t * 2 * Math.PI).toFloat()

            // Base scale so the (overscanned) bitmap fully covers the surface, then a little extra
            // breathing room that we slowly zoom in and out of.
            val baseScale = min(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
            val zoom = 1.0f + 0.06f * (0.5f + 0.5f * sin(angle))
            val scale = baseScale * zoom

            val maxDx = (bmp.width * scale - w) / 2f
            val maxDy = (bmp.height * scale - h) / 2f
            val dx = maxDx * 0.6f * cos(angle)
            val dy = maxDy * 0.6f * sin(angle * 0.7f)

            return Matrix().apply {
                setScale(scale, scale)
                postTranslate((w - bmp.width * scale) / 2f + dx, (h - bmp.height * scale) / 2f + dy)
            }
        }
    }
}
