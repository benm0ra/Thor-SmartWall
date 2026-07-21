package com.thor.smartwall

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.content.Intent
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
import com.thor.smartwall.Prefs.rotationOverrideDegrees
import com.thor.smartwall.Prefs.swapOrder
import com.thor.smartwall.Prefs.splitVideoTopPath
import com.thor.smartwall.Prefs.splitVideoBottomPath
import com.thor.smartwall.Prefs.videoSmoothMode
import com.thor.smartwall.Prefs.videoSmoothness
import com.thor.smartwall.Prefs.videoUri
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class SmartSplitWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = SmartEngine()

    inner class SmartEngine : Engine() {

        private val handler = Handler(Looper.getMainLooper())
        private var visible = false
        private var screenOn = true
        private var mediaPlayer: MediaPlayer? = null

        // Everything below is resolved lazily once we know which display we're on.
        private var myScreen: ScreenSpec? = null
        private var myLogicalScreen: ScreenSpec? = null
        private var myRotationDegrees: Int = 0
        private var allScreensLogical: List<ScreenSpec> = emptyList()
        private var readyBitmap: Bitmap? = null
        private var gifDrawable: AnimatedImageDrawable? = null
        private var videoFrames: List<Bitmap> = emptyList()

        // Guards against the rapid engine-recreate races seen in field debug reports: each call to
        // setupVideoSplit bumps this, and a background sampling thread only publishes its result if
        // its captured generation still matches. Stale threads (from a torn-down engine) no-op.
        @Volatile private var contentGeneration = 0
        private var samplingThread: Thread? = null
        @Volatile private var videoPreparing = false
        private var currentHolder: SurfaceHolder? = null
        private var contentPreparedForSurface = false
        private var frameDecoder: VideoFrameDecoder? = null
        @Volatile private var latestVideoFrame: Bitmap? = null

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

        // Split-video: frames are sampled once up front then cycled, so the loop stays cheap at
        // runtime regardless of how many frames the chosen smoothness tier decodes. See setupVideoSplit().
        private val videoLoopMs = 6_000L

        /** Which sampled video frame should be showing right now, phase-locked via the shared epoch. */
        private fun currentVideoFrame(): Bitmap? {
            val frames = videoFrames
            if (frames.isEmpty()) return null
            val epoch = Prefs.getOrCreateEpoch(applicationContext)
            val elapsed = (SystemClock.elapsedRealtime() - epoch) % videoLoopMs
            val idx = ((elapsed.toFloat() / videoLoopMs) * frames.size).toInt().coerceIn(0, frames.size - 1)
            return frames[idx]
        }

        private val drawRunnable = object : Runnable {
            override fun run() {
                drawFrame()
                if (shouldPlay()) {
                    handler.postDelayed(this, 66L) // ~15fps: plenty smooth for a *slow* pan/zoom, half the cost of 30fps
                }
            }
        }

        // Belt-and-suspenders: onVisibilityChanged *should* fire false whenever the screen goes
        // off, but OEM Android skins have a long history of being inconsistent about this, and a
        // wallpaper that keeps animating (and re-decoding video/GIF frames) with the screen
        // physically off is exactly the kind of thing that causes silent overnight battery drain.
        // This listens for the real screen-power broadcast directly rather than trusting that.
        private val screenReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: Intent) {
                screenOn = intent.action != Intent.ACTION_SCREEN_OFF
                updatePlaybackState()
            }
        }

        private fun shouldPlay(): Boolean = visible && screenOn

        /** Single source of truth for starting/stopping every animated thing this Engine owns. */
        private fun updatePlaybackState() {
            val ctx = applicationContext
            if (shouldPlay()) {
                val m = ctx.mode
                val needsDrawLoop = m == WallMode.KEN_BURNS || (m == WallMode.VIDEO && !ctx.videoSmoothMode)
                if (needsDrawLoop) {
                    handler.removeCallbacks(drawRunnable)
                    handler.post(drawRunnable)
                }
                try { mediaPlayer?.start() } catch (_: IllegalStateException) { /* not prepared yet - onPrepared will start it */ }
                gifDrawable?.start()
            } else {
                handler.removeCallbacks(drawRunnable)
                try { mediaPlayer?.pause() } catch (_: IllegalStateException) { /* not prepared yet, nothing to pause */ }
                gifDrawable?.stop()
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            DebugLog.d("Engine.onCreate")
            val filter = android.content.IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
                addAction(Intent.ACTION_SCREEN_ON)
            }
            applicationContext.registerReceiver(screenReceiver, filter)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            currentHolder = holder
            resolveScreenGeometry()
            DebugLog.d("Engine.onSurfaceCreated: mode=${applicationContext.mode}, screen=${myLogicalScreen?.let { "${it.widthPx}x${it.heightPx} id=${it.displayId}" } ?: "null"}, totalScreens=${allScreensLogical.size}")
            contentPreparedForSurface = false
            prepareContentForCurrentMode(holder)
            contentPreparedForSurface = true
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            currentHolder = holder
            // onSurfaceChanged fires right after onSurfaceCreated with the same surface. Re-running
            // full content setup here would spin up a SECOND MediaPlayer that collides with the
            // first mid-preparation (the observed what=-38 error). Only re-prepare if we haven't
            // already prepared for this surface (e.g. a genuine later size change).
            if (!contentPreparedForSurface) {
                resolveScreenGeometry()
                prepareContentForCurrentMode(holder)
                contentPreparedForSurface = true
            }
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            updatePlaybackState()
            // Static and smooth-video modes have no repeating draw loop to fall back on, so if
            // they became ready while the surface was hidden (a common race right after applying),
            // nothing would ever paint them. Force one draw now that we're visible. Animated modes
            // are already covered by their loop, but a redundant draw here is harmless.
            if (isVisible) {
                val ctx = applicationContext
                if (ctx.mode != WallMode.VIDEO || !ctx.videoSmoothMode) {
                    drawFrame()
                }
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            contentGeneration++ // orphan any in-flight sampling thread
            contentPreparedForSurface = false
            currentHolder = null
            handler.removeCallbacks(drawRunnable)
            mediaPlayer?.release()
            mediaPlayer = null
            frameDecoder?.stop()
            frameDecoder = null
            latestVideoFrame = null
            gifDrawable?.callback = null
            gifDrawable = null
        }

        override fun onDestroy() {
            super.onDestroy()
            contentGeneration++ // orphan any in-flight sampling thread
            handler.removeCallbacks(drawRunnable)
            mediaPlayer?.release()
            mediaPlayer = null
            frameDecoder?.stop()
            frameDecoder = null
            latestVideoFrame = null
            gifDrawable?.callback = null
            gifDrawable = null
            try {
                applicationContext.unregisterReceiver(screenReceiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered - fine.
            }
        }

        /**
         * Figures out which physical display this Engine instance belongs to, using the real,
         * per-display Context that getDisplayContext() gives us - the crux of splitting across
         * two screens at all.
         *
         * A previous revision of this method tried to auto-detect and compensate for a supposed
         * rotation mismatch, on the theory that the Thor's panels are "really" portrait and
         * DisplayManager was reporting them landscape-rotated. A reference photo of the device
         * running proved that theory wrong: the Thor is used landscape, Switch-style, top screen
         * above bottom screen, both wider than tall - exactly what DisplayManager reports (e.g.
         * 1920x1080 and 1240x1080). So the raw numbers are simply correct and no swap is needed.
         * [rotationOverrideDegrees] is kept as a pure manual escape hatch in case some other Thor
         * variant or a future firmware update ever does need it - it defaults to 0 (no rotation)
         * and only applies if you deliberately dial it in from the Orientation Fix section.
         */
        private fun resolveScreenGeometry() {
            val displayContext = getDisplayContext() ?: applicationContext
            val display: Display? = displayContext.display
            val ctx = applicationContext
            val swap = ctx.swapOrder
            val raw = DisplayDetector.findScreens(ctx, swap)

            allScreensLogical = raw

            val myId = display?.displayId ?: Display.DEFAULT_DISPLAY
            val rawMine = raw.firstOrNull { it.displayId == myId } ?: raw.firstOrNull()
            myScreen = rawMine
            myLogicalScreen = rawMine

            myRotationDegrees = ctx.rotationOverrideDegrees.mod(360)
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
            frameDecoder?.stop()
            frameDecoder = null
            latestVideoFrame = null
            gifDrawable?.callback = null
            gifDrawable = null
            videoFrames = emptyList()

            val ctx = applicationContext
            val screen = myLogicalScreen
            if (screen == null) {
                DebugLog.w("setupImage: no screen resolved yet; drawing diagnostic")
                drawFrame()
                return
            }
            val overscanAmount = if (ctx.mode == WallMode.KEN_BURNS) overscan else 0f

            readyBitmap = try {
                if (ctx.independentMode) {
                    val primary = loadBitmap(ctx.imageUri)
                    if (primary == null) {
                        DebugLog.w("setupImage: primary image failed to load (uri=${ctx.imageUri})")
                        null
                    } else {
                        val secondary = loadBitmap(ctx.imageUriSecondary) ?: primary
                        val sources = mapOf(
                            (allScreensLogical.getOrNull(0)?.displayId ?: 0) to primary,
                            (allScreensLogical.getOrNull(1)?.displayId ?: 1) to secondary
                        )
                        SplitEngine.computeIndependentCrops(sources, allScreensLogical, overscanAmount)[screen.displayId]
                    }
                } else {
                    val source = loadBitmap(ctx.imageUri)
                    if (source == null) {
                        DebugLog.w("setupImage: image failed to load (uri=${ctx.imageUri})")
                        null
                    } else {
                        val orientation = if (ctx.orientationVertical) StackOrientation.VERTICAL else StackOrientation.HORIZONTAL
                        SplitEngine.computeCrops(source, allScreensLogical, orientation, ctx.gapFraction, overscanAmount)[screen.displayId]
                    }
                }
            } catch (t: Throwable) {
                DebugLog.e("setupImage: crop failed", t)
                null
            }
            drawFrame()
            // STATIC has nothing to animate - draw once above and stop, don't burn battery
            // redrawing an unchanging bitmap 15x/sec. Only KEN_BURNS needs the ongoing loop.
            if (ctx.mode == WallMode.KEN_BURNS && shouldPlay()) {
                handler.removeCallbacks(drawRunnable)
                handler.post(drawRunnable)
            } else {
                handler.removeCallbacks(drawRunnable)
            }
        }

        /**
         * Video playback picks the best available path:
         * 1. Pre-split files exist (from VideoSplitTranscoder) -> play this screen's own cropped
         *    file with MediaPlayer. Smooth AND split. The preferred path.
         * 2. Smooth mode on -> play the original with MediaPlayer (smooth, duplicated).
         * 3. Otherwise -> frame-sampled slideshow (split, choppy). The fallback.
         */
        private fun setupVideo(holder: SurfaceHolder) {
            val ctx = applicationContext
            val topPath = ctx.splitVideoTopPath
            val bottomPath = ctx.splitVideoBottomPath
            val haveSplitFiles = topPath != null && bottomPath != null &&
                java.io.File(topPath).exists() && java.io.File(bottomPath).exists()

            when {
                haveSplitFiles && !ctx.videoSmoothMode -> setupVideoPreSplit(holder, topPath!!, bottomPath!!)
                ctx.videoSmoothMode -> setupVideoSmooth(holder)
                else -> setupVideoSplit()
            }
        }

        /**
         * Plays the pre-cropped file for THIS screen by decoding it frame-by-frame with MediaCodec
         * and drawing each frame to the wallpaper Canvas. This is the path that actually works on
         * the Thor: MediaPlayer cannot render to a wallpaper surface here (fails with EINVAL), but
         * Canvas bitmap drawing works. Because the files are already cropped per screen, decode is
         * the only per-frame cost, so this runs far smoother than the old sampled slideshow.
         */
        private fun setupVideoPreSplit(holder: SurfaceHolder, topPath: String, bottomPath: String) {
            readyBitmap = null
            videoFrames = emptyList()
            gifDrawable?.callback = null
            gifDrawable = null
            mediaPlayer?.release()
            mediaPlayer = null
            frameDecoder?.stop()
            frameDecoder = null
            latestVideoFrame = null

            val isTop = (myLogicalScreen?.order ?: 0) == 0
            val path = if (isTop) topPath else bottomPath
            val f = java.io.File(path)
            val myGeneration = ++contentGeneration
            DebugLog.d("setupVideoPreSplit(decode): gen=$myGeneration screen id=${myLogicalScreen?.displayId} isTop=$isTop size=${if (f.exists()) f.length()/1024 else -1}KB -> $path")

            if (!f.exists() || f.length() == 0L) {
                DebugLog.w("setupVideoPreSplit(decode): file missing/empty")
                drawFrame()
                return
            }

            val fps = 30
            val decoder = VideoFrameDecoder(f, fps) { bmp ->
                // Called on the decoder's thread. Stash the frame and ask the main thread to draw.
                if (myGeneration != contentGeneration) return@VideoFrameDecoder
                latestVideoFrame = bmp
                handler.post { if (myGeneration == contentGeneration) drawFrame() }
            }
            frameDecoder = decoder
            decoder.start()
        }

        /** Smooth path: hand the file straight to MediaPlayer, which draws directly to the surface. */
        private fun setupVideoSmooth(holder: SurfaceHolder) {
            videoFrames = emptyList()
            readyBitmap = null
            gifDrawable?.callback = null
            gifDrawable = null
            val ctx = applicationContext
            val uriStr = ctx.videoUri ?: return
            val myGeneration = ++contentGeneration
            if (!holder.surface.isValid) {
                DebugLog.w("setupVideoSmooth: surface not valid yet, skipping (gen=$myGeneration)")
                return
            }
            try {
                mediaPlayer?.release()
                mediaPlayer = null
                val mp = MediaPlayer()
                mp.setDataSource(ctx, uriStr.toUri())
                mp.setSurface(holder.surface)
                mp.isLooping = true
                mp.setVolume(0f, 0f)
                mp.setOnPreparedListener { player ->
                    if (myGeneration != contentGeneration || currentHolder !== holder) {
                        runCatching { player.release() }
                        return@setOnPreparedListener
                    }
                    // Align every engine's playback position to the same shared epoch so
                    // top and bottom screens loop in lockstep instead of drifting apart.
                    val epoch = Prefs.getOrCreateEpoch(ctx)
                    val elapsed = SystemClock.elapsedRealtime() - epoch
                    val duration = player.duration.takeIf { d -> d > 0 } ?: 1
                    runCatching {
                        player.seekTo((elapsed % duration).toInt())
                        if (shouldPlay()) player.start()
                    }
                }
                mp.setOnErrorListener { _, what, extra ->
                    DebugLog.e("setupVideoSmooth: MediaPlayer error what=$what extra=$extra gen=$myGeneration")
                    true
                }
                mp.prepareAsync()
                mediaPlayer = mp
            } catch (t: Throwable) {
                DebugLog.e("setupVideoSmooth: failed", t)
                mediaPlayer = null
            }
        }

        /**
         * Split path: samples [videoFrameCount] frames spread across the video with
         * MediaMetadataRetriever, runs each one through the *exact same* SplitEngine crop used
         * for images (continuous split across the hinge), and cycles through them like a
         * slideshow at [videoLoopMs]. The honest tradeoff: motion is choppier than the source
         * video, but it's correctly split.
         */
        private fun setupVideoSplit() {
            mediaPlayer?.release()
            mediaPlayer = null
            frameDecoder?.stop()
            frameDecoder = null
            latestVideoFrame = null
            gifDrawable?.callback = null
            gifDrawable = null
            readyBitmap = null
            videoFrames = emptyList()

            val ctx = applicationContext
            val screen = myLogicalScreen
            if (screen == null) {
                DebugLog.w("setupVideoSplit: no screen resolved; drawing diagnostic")
                drawFrame()
                return
            }
            val uriStr = ctx.videoUri
            if (uriStr == null) {
                DebugLog.w("setupVideoSplit: no video URI set")
                drawFrame()
                return
            }
            val screensSnapshot = allScreensLogical
            val orientation = if (ctx.orientationVertical) StackOrientation.VERTICAL else StackOrientation.HORIZONTAL
            val gap = ctx.gapFraction
            val smoothness = ctx.videoSmoothness

            // Invalidate any in-flight sampling job from a previous (possibly torn-down) engine.
            val myGeneration = ++contentGeneration
            videoPreparing = true
            drawFrame() // show a "preparing" state right away so the screen is never blank while sampling
            DebugLog.d("setupVideoSplit: starting sample gen=$myGeneration frames=${smoothness.frameCount} screen=${screen.widthPx}x${screen.heightPx} id=${screen.displayId}")

            val worker = Thread {
                var attempted = 0
                var succeeded = 0
                val sampled = try {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(ctx, uriStr.toUri())
                    val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()?.coerceAtLeast(1L) ?: 1000L
                    val frameCount = smoothness.frameCount
                    val frames = mutableListOf<Bitmap>()
                    for (i in 0 until frameCount) {
                        if (myGeneration != contentGeneration) break // superseded - stop wasting work
                        attempted++
                        val timeUs = durationMs * 1000L * i.toLong() / frameCount.toLong()
                        val raw = try {
                            retriever.getScaledFrameAtTime(
                                timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                smoothness.sampleWidth, smoothness.sampleHeight
                            )
                        } catch (_: Throwable) {
                            null
                        } ?: retriever.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        val cropped = raw?.let {
                            SplitEngine.computeCrops(it, screensSnapshot, orientation, gap)[screen.displayId]
                        }
                        if (cropped != null) { frames.add(cropped); succeeded++ }
                    }
                    retriever.release()
                    frames
                } catch (t: Throwable) {
                    DebugLog.e("setupVideoSplit: sampling failed (gen=$myGeneration)", t)
                    emptyList()
                }
                handler.post {
                    if (myGeneration != contentGeneration) {
                        DebugLog.d("setupVideoSplit: gen=$myGeneration superseded, discarding ${sampled.size} frames")
                        return@post
                    }
                    videoPreparing = false
                    videoFrames = sampled
                    DebugLog.d("setupVideoSplit: gen=$myGeneration done - $succeeded/$attempted frames usable")
                    if (sampled.isEmpty()) {
                        DebugLog.w("setupVideoSplit: NO usable frames - drawing diagnostic. Codec may not support frame extraction for this file.")
                    }
                    drawFrame()
                    updatePlaybackState()
                }
            }
            samplingThread = worker
            worker.start()
        }

        /**
         * Decodes an animated GIF with the platform's built-in ImageDecoder (available since
         * API 28, no extra library needed) and drives its frame timing off our own Handler via
         * gifCallback. Unlike video and static images, a GIF still fills each screen
         * independently rather than doing the continuous crop-across-the-hinge trick, since it's
         * a small fixed pre-baked animation loop rather than something worth re-sampling frame by
         * frame the way video now is.
         */
        private fun setupGif() {
            mediaPlayer?.release()
            mediaPlayer = null
            frameDecoder?.stop()
            frameDecoder = null
            latestVideoFrame = null
            videoFrames = emptyList()
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
                    if (shouldPlay()) drawable.start()
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
            if (ctx.mode == WallMode.VIDEO && ctx.videoSmoothMode) return // MediaPlayer draws directly to the surface

            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                canvas?.let { c ->
                    drawWithRotationCompensation(c) { w, h ->
                        when (ctx.mode) {
                            WallMode.GIF -> {
                                val drawable = gifDrawable
                                if (drawable != null) {
                                    c.drawColor(Color.BLACK)
                                    drawGifCentered(c, drawable, w, h)
                                } else {
                                    drawNoContentMessage(c, w, h)
                                }
                            }
                            WallMode.VIDEO -> {
                                val decoded = latestVideoFrame
                                val frame = decoded ?: currentVideoFrame()
                                when {
                                    frame != null && !frame.isRecycled -> {
                                        c.drawColor(Color.BLACK)
                                        c.drawBitmap(frame, fitMatrix(frame, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
                                    }
                                    videoPreparing -> drawCenteredMessage(c, w, h, "Preparing video…", "#101418")
                                    else -> drawNoContentMessage(c, w, h)
                                }
                            }
                            else -> {
                                val bmp = readyBitmap
                                if (bmp != null) {
                                    c.drawColor(Color.BLACK)
                                    val matrix = if (ctx.mode == WallMode.KEN_BURNS) kenBurnsMatrix(bmp, w, h)
                                    else fitMatrix(bmp, w, h)
                                    c.drawBitmap(bmp, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                                } else {
                                    drawNoContentMessage(c, w, h)
                                }
                            }
                        }
                    }
                }
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas)
            }
        }

        /**
         * Rotates the canvas by [myRotationDegrees] so content we draw at logical-orientation
         * coordinates ends up upright on the physically-rotated surface Android actually gave us
         * (see resolveScreenGeometry). Hands the block the LOGICAL width/height to draw at -
         * Canvas#getWidth()/getHeight() always report the raw physical surface size regardless of
         * any rotate()/translate() applied, so callers must use these instead of c.width/c.height.
         */
        private fun drawWithRotationCompensation(c: Canvas, draw: (w: Int, h: Int) -> Unit) {
            when (myRotationDegrees) {
                90 -> {
                    c.save()
                    c.rotate(90f)
                    c.translate(0f, -c.width.toFloat())
                    draw(c.height, c.width)
                    c.restore()
                }
                180 -> {
                    c.save()
                    c.rotate(180f)
                    c.translate(-c.width.toFloat(), -c.height.toFloat())
                    draw(c.width, c.height)
                    c.restore()
                }
                270 -> {
                    c.save()
                    c.rotate(270f)
                    c.translate(-c.height.toFloat(), 0f)
                    draw(c.height, c.width)
                    c.restore()
                }
                else -> draw(c.width, c.height)
            }
        }

        /** Center-crop-fills the given logical area with the GIF's current frame, no stretching. */
        private fun drawGifCentered(c: Canvas, drawable: AnimatedImageDrawable, w: Int, h: Int) {
            val dw = drawable.intrinsicWidth.takeIf { it > 0 } ?: return
            val dh = drawable.intrinsicHeight.takeIf { it > 0 } ?: return
            val scale = maxOf(w.toFloat() / dw, h.toFloat() / dh)
            val scaledW = dw * scale
            val scaledH = dh * scale
            val dx = (w - scaledW) / 2f
            val dy = (h - scaledH) / 2f

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
        private fun drawNoContentMessage(c: Canvas, w: Int, h: Int) {
            c.drawColor(Color.parseColor("#3A1620"))
            val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = 36f
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            c.drawText("ThorPaper: content couldn't load", w / 2f, h / 2f, textPaint)
            c.drawText("Open ThorPaper and pick media again", w / 2f, h / 2f + 50f, textPaint)
        }

        /** Neutral centered status text (e.g. the brief "Preparing video…" state during sampling). */
        private fun drawCenteredMessage(c: Canvas, w: Int, h: Int, text: String, bgHex: String) {
            c.drawColor(Color.parseColor(bgHex))
            val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = 34f
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            c.drawText(text, w / 2f, h / 2f, textPaint)
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
