package com.thor.smartwall

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Decodes a video file into a continuous stream of Bitmaps using MediaCodec + ImageReader, looping
 * forever, handing each decoded frame to [onFrame] to be drawn to a Canvas.
 *
 * Why this exists: on the AYN Thor, MediaPlayer cannot render video onto a WallpaperService
 * surface (fails with EINVAL as soon as it draws - confirmed in field logs). But drawing Bitmaps
 * to the wallpaper Canvas works. So we decode frames ourselves and draw them.
 *
 * Format note (the thing that made the first attempt produce no frames): hardware AVC decoders
 * output YUV, not RGBA. Requesting an RGBA_8888 ImageReader silently yielded zero frames. This
 * version requests YUV_420_888 (which hardware decoders DO feed) and converts each frame to a
 * Bitmap via NV21 -> YuvImage -> JPEG -> Bitmap. That conversion isn't the fastest possible, but
 * it's dependency-free and works on essentially any device; correctness first, optimize later.
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
    @Volatile private var framesEmitted = 0

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

        val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (_: Throwable) { null }
            if (img != null) {
                try {
                    val bmp = yuvImageToBitmap(img, width, height)
                    if (bmp != null && running) {
                        framesEmitted++
                        if (framesEmitted == 1) DebugLog.d("VideoFrameDecoder: first frame emitted for ${file.name}")
                        onFrame(bmp)
                    }
                } catch (t: Throwable) {
                    DebugLog.e("VideoFrameDecoder: frame convert failed", t)
                } finally {
                    img.close()
                }
            }
        }, handler)

        val dec = MediaCodec.createDecoderByType(mime)
        codec = dec
        dec.configure(format, reader.surface, null, 0)
        dec.start()
        DebugLog.d("VideoFrameDecoder: started ${file.name} ${width}x$height mime=$mime fps=$targetFps")

        val bufferInfo = MediaCodec.BufferInfo()
        val frameIntervalMs = (1000L / targetFps.coerceIn(1, 60))

        while (running) {
            val inIndex = dec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val inBuf = dec.getInputBuffer(inIndex)!!
                val sampleSize = ex.readSampleData(inBuf, 0)
                if (sampleSize < 0) {
                    ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    val retry = ex.readSampleData(inBuf, 0)
                    if (retry < 0) {
                        dec.queueInputBuffer(inIndex, 0, 0, 0, 0)
                    } else {
                        dec.queueInputBuffer(inIndex, 0, retry, ex.sampleTime, 0)
                        ex.advance()
                    }
                } else {
                    dec.queueInputBuffer(inIndex, 0, sampleSize, ex.sampleTime, 0)
                    ex.advance()
                }
            }

            val outIndex = dec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outIndex >= 0) {
                dec.releaseOutputBuffer(outIndex, true)
                try { Thread.sleep(frameIntervalMs) } catch (_: InterruptedException) {}
            }
        }
    }

    private fun yuvImageToBitmap(image: Image, width: Int, height: Int): Bitmap? {
        val nv21 = yuv420ToNv21(image, width, height)
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, width, height), 85, out)
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun yuv420ToNv21(image: Image, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val nv21 = ByteArray(ySize + ySize / 2)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        var pos = 0
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
            pos = ySize
        } else {
            val row = ByteArray(yRowStride)
            for (r in 0 until height) {
                yBuffer.position(r * yRowStride)
                yBuffer.get(row, 0, minOf(yRowStride, row.size))
                System.arraycopy(row, 0, nv21, pos, width)
                pos += width
            }
        }

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val vBuffer = vPlane.buffer
        val uBuffer = uPlane.buffer
        val vRowStride = vPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vPixStride = vPlane.pixelStride
        val uPixStride = uPlane.pixelStride

        var offset = ySize
        for (r in 0 until chromaHeight) {
            for (c in 0 until chromaWidth) {
                nv21[offset++] = vBuffer.get(r * vRowStride + c * vPixStride)
                nv21[offset++] = uBuffer.get(r * uRowStride + c * uPixStride)
            }
        }
        return nv21
    }
}
