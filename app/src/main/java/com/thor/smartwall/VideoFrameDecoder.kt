package com.thor.smartwall

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * Decodes a video file into a continuous stream of Bitmaps, looping forever, handing each frame to
 * [onFrame] to be drawn to a Canvas.
 *
 * Why this exists: on the AYN Thor, MediaPlayer cannot render video onto a WallpaperService
 * surface (fails with EINVAL as soon as it draws). Canvas bitmap drawing works, so we decode
 * frames ourselves.
 *
 * Approach: ByteBuffer output (NO output Surface / ImageReader). Rendering to an ImageReader
 * surface produced zero frames on this device's hardware decoder (the decode loop spun without
 * ever delivering a frame). Decoding to plain output ByteBuffers is the older, slower, but far
 * more universally-supported path: we read the raw YUV bytes the codec emits, look at the codec's
 * reported color format, convert to NV21 -> JPEG -> Bitmap. Correctness first.
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
        codec = null
        extractor = null
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

        // Ask the decoder for a flexible YUV 4:2:0 output we know how to read.
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )

        val dec = MediaCodec.createDecoderByType(mime)
        codec = dec
        dec.configure(format, null, null, 0) // null surface -> ByteBuffer output
        dec.start()
        DebugLog.d("VideoFrameDecoder: started ${file.name} ${width}x$height mime=$mime fps=$targetFps (bytebuffer)")

        val bufferInfo = MediaCodec.BufferInfo()
        val frameIntervalMs = (1000L / targetFps.coerceIn(1, 60))
        var outColorFormat = 0

        while (running) {
            // Feed input.
            val inIndex = dec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val inBuf = dec.getInputBuffer(inIndex)!!
                val sampleSize = ex.readSampleData(inBuf, 0)
                if (sampleSize < 0) {
                    ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC) // loop
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
            when {
                outIndex >= 0 -> {
                    if (bufferInfo.size > 0) {
                        try {
                            // getOutputImage() returns an Image with correct per-plane strides and
                            // offsets regardless of the codec's underlying color format. Using it
                            // (instead of hand-parsing the raw ByteBuffer against a guessed format)
                            // is what removes the color-channel guesswork - the planes tell us
                            // exactly where U and V live.
                            val image = dec.getOutputImage(outIndex)
                            val bmp = if (image != null) imageToBitmap(image, width, height) else null
                            image?.close()
                            if (bmp != null && running) {
                                framesEmitted++
                                if (framesEmitted == 1) DebugLog.d("VideoFrameDecoder: first frame emitted (${bmp.width}x${bmp.height})")
                                onFrame(bmp)
                            }
                        } catch (t: Throwable) {
                            if (framesEmitted == 0) DebugLog.e("VideoFrameDecoder: convert failed", t)
                        }
                    }
                    dec.releaseOutputBuffer(outIndex, false)
                    try { Thread.sleep(frameIntervalMs) } catch (_: InterruptedException) {}
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outColorFormat = try {
                        dec.outputFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT)
                    } catch (_: Throwable) { 0 }
                    DebugLog.d("VideoFrameDecoder: output format changed, colorFormat=$outColorFormat")
                }
                // INFO_TRY_AGAIN_LATER (-1) and others: just keep looping.
            }
        }
    }

    /**
     * Converts a decoder-provided YUV_420_888 Image to a Bitmap using the Image's own plane
     * descriptors (row/pixel strides), so it's correct for planar (I420), semi-planar (NV12),
     * and vendor variants alike. Builds NV21 (Y + interleaved V,U) then JPEG-decodes it.
     */
    private fun imageToBitmap(image: android.media.Image, width: Int, height: Int): Bitmap? {
        val nv21 = imageToNv21(image, width, height)
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, width, height), 85, out)
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /** Packs an Image's Y/U/V planes into NV21, honoring each plane's row and pixel stride. */
    private fun imageToNv21(image: android.media.Image, width: Int, height: Int): ByteArray {
        val nv21 = ByteArray(width * height * 3 / 2)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        // --- Y ---
        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        var pos = 0
        if (yRowStride == width) {
            yBuf.get(nv21, 0, width * height)
            pos = width * height
        } else {
            val row = ByteArray(yRowStride)
            for (r in 0 until height) {
                yBuf.position(r * yRowStride)
                yBuf.get(row, 0, minOf(yRowStride, row.size))
                System.arraycopy(row, 0, nv21, pos, width)
                pos += width
            }
        }

        // --- V,U interleaved (NV21 order) ---
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vPixStride = vPlane.pixelStride

        var offset = width * height
        for (r in 0 until chromaHeight) {
            for (c in 0 until chromaWidth) {
                nv21[offset++] = vBuf.get(r * vRowStride + c * vPixStride)
                nv21[offset++] = uBuf.get(r * uRowStride + c * uPixStride)
            }
        }
        return nv21
    }
}
