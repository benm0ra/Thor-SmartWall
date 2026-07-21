package com.thor.smartwall

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
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
import com.thor.smartwall.Prefs.gifUri
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
        private var gifDrawable: AnimatedImageDrawable? = null

        // Lets an AnimatedImageDrawable schedule its own frame timing onto our Handler and
        // ask us to redraw when a new frame is ready - the standard way to drive a Drawable's
        // animation when it isn't hosted inside a View.
        private val gifCallback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) { drawFrame() }
            override fun scheduleDrawable(who: Drawable, what: Runnable, whenMs: Long) {
                handler.postAtTime(what, whenMs)
            }
            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                handler.removeCallbacks(what)
            }
        }

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
                gifDrawable?.start()
            } else {
                handler.removeCallbacks(drawRunnable)
                mediaPlayer?.pause()
                gifDrawable?.stop()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            handler.removeCallbacks(drawRunnable)
            mediaPlayer?.release()
            mediaPlayer = null
            gifDrawable?.callback = null
            gifDrawable = null
        }

        override fun onDestroy() {
            super.onDestroy()
            handler.removeCallbacks(drawRunnable)
            mediaPlayer?.release()
            mediaPlayer = null
            gifDrawable?.callback = null
            gifDrawable = null
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
                WallMode.GIF -> setupGif()
                WallMode.STATIC, WallMode.KEN_BURNS -> setupImage()
            }
        }

        private fun setupImage() {
            mediaPlayer?.release()
            mediaPlayer = null
            gifDrawable?.callback = null
            gifDrawable = null

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
            gifDrawable?.callback = null
            gifDrawable = null
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

        /**
         * Decodes an animated GIF with the platform's built-in ImageDecoder (available since
         * API 28, no extra library needed) and drives its frame timing off our own Handler via
         * gifCallback. Like video, a GIF fills each screen independently rather than doing the
         * continuous crop-across-the-hinge trick, since it's a fixed pre-baked animation rather
         * than something we can re-render from one big virtual canvas per screen.
         */
        private fun setupGif() {
            mediaPlayer?.release()
            mediaPlayer = null
            readyBitmap = null
            gifDrawable?.callback = null
            gifDrawable = null

            val ctx = applicationContext
            val uriStr = ctx.gifUri ?: return
            try {
                val source = ImageDecoder.createSource(ctx.contentResolver, uriStr.toUri())
                val drawable = ImageDecoder.decodeDrawable(source)
                if (drawable is AnimatedImageDrawable) {
                    drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    drawable.callback = gifCallback
                    gifDrawable = drawable
                    if (visible) drawable.start()
                }
            } catch (t: Throwable) {
                android.util.Log.e("ThorSmartWall", "Failed to load GIF from $uriStr", t)
                gifDrawable = null
            }
            drawFrame()
        }

        private fun loadBitmap(uriStr: String?): Bitmap? {
            if (uriStr == null) return null
            return try {
                val uri: Uri = uriStr.toUri()
                applicationContext.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
            } catch (t: Throwable) {
                android.util.Log.e("ThorSmartWall", "Failed to load image from $uriStr", t)
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
                    when (ctx.mode) {
                        WallMode.GIF -> {
                            val drawable = gifDrawable
                            if (drawable != null) {
                                c.drawColor(Color.BLACK)
                                drawGifCentered(c, drawable)
                            } else {
                                drawNoContentMessage(c)
                            }
                        }
                        else -> {
                            val bmp = readyBitmap
                            if (bmp != null) {
                                c.drawColor(Color.BLACK)
                                val matrix = if (ctx.mode == WallMode.KEN_BURNS) kenBurnsMatrix(bmp, c.width, c.height)
                                else fitMatrix(bmp, c.width, c.height)
                                c.drawBitmap(bmp, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                            } else {
                                drawNoContentMessage(c)
                            }
                        }
                    }
                }
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas)
            }
        }

        /** Center-crop-fills this screen's surface with the GIF's current frame, no stretching. */
        private fun drawGifCentered(c: Canvas, drawable: AnimatedImageDrawable) {
            val dw = drawable.intrinsicWidth.takeIf { it > 0 } ?: return
            val dh = drawable.intrinsicHeight.takeIf { it > 0 } ?: return
            val scale = maxOf(c.width.toFloat() / dw, c.height.toFloat() / dh)
            val scaledW = dw * scale
            val scaledH = dh * scale
            val dx = (c.width - scaledW) / 2f
            val dy = (c.height - scaledH) / 2f

            c.save()
            c.translate(dx, dy)
            c.scale(scale, scale)
            drawable.setBounds(0, 0, dw, dh)
            drawable.draw(c)
            c.restore()
        }

        /**
         * Deliberately NOT plain black: if you see this dark red screen instead of your art,
         * something failed to load (permission lost, file moved, bad format, etc) rather than
         * the app just not doing anything.
         */
        private fun drawNoContentMessage(c: Canvas) {
            c.drawColor(Color.parseColor("#3A1620"))
            val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = 36f
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            c.drawText("Thor Smart Split: no image loaded", c.width / 2f, c.height / 2f, textPaint)
            c.drawText("Reopen the app and pick media again", c.width / 2f, c.height / 2f + 50f, textPaint)
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
