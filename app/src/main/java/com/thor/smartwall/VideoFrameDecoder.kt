package com.thor.smartwall

import android.graphics.Bitmap
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.nio.ByteBuffer

/**
 * Decodes a video file into a continuous stream of Bitmaps using MediaCodec + ImageReader, looping
 * forever, and hands each decoded frame to a callback so it can be drawn to a Canvas.
 *
 * Why this exists: on the AYN Thor, MediaPlayer cannot render video onto a WallpaperService
 * surface at all (it fails with EINVAL the moment playback starts drawing - confirmed in field
 * debug reports, in both "smooth" and "pre-split" MediaPlayer paths). But drawing Bitmaps to the
 * wallpaper Canvas works fine (that's how every other mode renders). So instead of asking
 * MediaPlayer to draw, we decode frames ourselves and draw them. Paired with the pre-split files
 * (already cropped per screen), the only per-frame cost here is decode, letting this run far
 * smoother than the old 16-frame sampled slideshow.
 *
 * This is deliberately simple and defensive: software-friendly YUV->RGB via ImageReader in
 * RGBA_8888 request, one decoder per instance, its own thread, and a hard stop() that releases
 * everything. Heavy logging via DebugLog so field failures are diagnosable.
 */
class VideoFrameDecoder(
    private val file: File,
    private val targetFps: Int,
    private val onFrame: (Bitmap) -> Unit
) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var running = false

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var imageReader: ImageReader? = null

    fun start() {
        if (running) return
        running = true
        val t = HandlerThread("VideoFrameDecoder").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        h.post { runCatching { decodeLoop() }.onFailure { DebugLog.e("VideoFrameDecoder: decode loop crashed", it) } }
    }

    fun stop() {
        running = false
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor?.release() }
        runCatching { imageReader?.close() }
        codec = null
        extractor = null
        imageReader = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun decodeLoop() {
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        extractor = ex

        var videoTrack = -1
        var format: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                videoTrack = i
                format = f
                break
            }
        }
        if (videoTrack < 0 || format == null) {
            DebugLog.e("VideoFrameDecoder: no video track in ${file.name}")
            return
        }
        ex.selectTrack(videoTrack)

        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        // ImageReader in RGBA_8888 gives us frames we can copy straight into a Bitmap.
        val reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 3)
        imageReader = reader

        val dec = MediaCodec.createDecoderByType(mime)
        codec = dec
        // Decode onto the ImageReader surface. Request flexible output so the codec renders into it.
        dec.configure(format, reader.surface, null, 0)
        dec.start()
        DebugLog.d("VideoFrameDecoder: started ${file.name} ${width}x$height mime=$mime fps=$targetFps")

        val bufferInfo = MediaCodec.BufferInfo()
        val frameIntervalMs = (1000L / targetFps.coerceIn(1, 60))
        var inputDone = false

        val reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        reader.setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (_: Throwable) { null }
            if (img != null) {
                try {
                    copyImageToBitmap(img, reusableBitmap, width, height)
                    if (running) onFrame(reusableBitmap)
                } catch (t: Throwable) {
                    DebugLog.e("VideoFrameDecoder: frame copy failed", t)
                } finally {
                    img.close()
                }
            }
        }, handler)

        while (running) {
            // Feed input.
            if (!inputDone) {
                val inIndex = dec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inBuf = dec.getInputBuffer(inIndex)!!
                    val sampleSize = ex.readSampleData(inBuf, 0)
                    if (sampleSize < 0) {
                        // End of stream: loop back to the start for a seamless repeat.
                        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        val retry = ex.readSampleData(inBuf, 0)
                        if (retry < 0) {
                            dec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIndex, 0, retry, ex.sampleTime, 0)
                            ex.advance()
                        }
                    } else {
                        dec.queueInputBuffer(inIndex, 0, sampleSize, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }

            // Drain output (rendered to the ImageReader surface).
            val outIndex = dec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outIndex >= 0) {
                val render = bufferInfo.size > 0
                dec.releaseOutputBuffer(outIndex, render)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    // Reached EOS on output: restart the whole loop for continuous playback.
                    runCatching { dec.flush() }
                    ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    inputDone = false
                }
                // Pace ourselves roughly to target fps so we don't spin the CPU flat out.
                try { Thread.sleep(frameIntervalMs) } catch (_: InterruptedException) {}
            }
        }

        runCatching { reusableBitmap.recycle() }
    }

    /** Copies a single ImageReader Image (RGBA_8888) into an existing ARGB_8888 Bitmap. */
    private fun copyImageToBitmap(image: Image, out: Bitmap, width: Int, height: Int) {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        if (rowPadding == 0) {
            buffer.rewind()
            out.copyPixelsFromBuffer(buffer)
        } else {
            // Handle stride padding row by row into a padded bitmap, then it still maps 1:1 since
            // out width matches image width; we build a temporary full-stride bitmap.
            val padded = Bitmap.createBitmap(
                width + rowPadding / pixelStride.coerceAtLeast(1), height, Bitmap.Config.ARGB_8888
            )
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)
            val canvas = android.graphics.Canvas(out)
            canvas.drawBitmap(padded, 0f, 0f, null)
            padded.recycle()
        }
    }
}
