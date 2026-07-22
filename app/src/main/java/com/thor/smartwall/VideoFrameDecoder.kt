package com.thor.smartwall

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import java.io.File

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
    private val transform: ((Bitmap) -> Bitmap?)? = null,
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
        // Snapshot then null the fields first, so the decode loop (checking `running`) and any
        // concurrent stop() can't double-release the same codec.
        val c = codec
        val e = extractor
        codec = null
        extractor = null
        runCatching { c?.stop() }
        runCatching { c?.release() }
        runCatching { e?.release() }
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
            // The whole loop body is guarded: MediaCodec throws IllegalStateException from almost
            // any call if the codec is torn down mid-operation (guaranteed with two decoders and
            // rapid engine recreation). Any such throw just ends this decoder cleanly - never a
            // crash. Stability over cleverness.
            try {
                // Feed input.
                val inIndex = dec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inBuf = dec.getInputBuffer(inIndex)
                    if (inBuf == null) {
                        // Shouldn't happen, but never dereference null.
                        dec.queueInputBuffer(inIndex, 0, 0, 0, 0)
                    } else {
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
                }

                val outIndex = dec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex >= 0 -> {
                        if (bufferInfo.size > 0) {
                            try {
                                val image = dec.getOutputImage(outIndex)
                                var bmp = if (image != null) imageToBitmap(image, width, height) else null
                                image?.close()
                                if (bmp != null && transform != null) bmp = transform.invoke(bmp)
                                if (bmp != null && running) {
                                    framesEmitted++
                                    if (framesEmitted == 1) DebugLog.d("VideoFrameDecoder: first frame emitted (${bmp.width}x${bmp.height})")
                                    onFrame(bmp)
                                }
                            } catch (t: Throwable) {
                                // A single bad frame must never kill the loop.
                                if (framesEmitted == 0) DebugLog.e("VideoFrameDecoder: convert failed", t)
                            }
                        }
                        runCatching { dec.releaseOutputBuffer(outIndex, false) }
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
            } catch (e: IllegalStateException) {
                // Codec was released/torn down under us. Normal during engine recreation - exit quietly.
                if (running) DebugLog.d("VideoFrameDecoder: codec state ended, stopping loop cleanly")
                break
            } catch (t: Throwable) {
                // Anything else unexpected: log once and stop this decoder rather than crash.
                DebugLog.e("VideoFrameDecoder: loop error, stopping", t)
                break
            }
        }
    }

    /**
     * Converts a decoder-provided YUV_420_888 Image directly to an ARGB Bitmap.
     *
     * We do the YUV->RGB math ourselves (BT.601, limited/"video" range) rather than routing through
     * YuvImage#compressToJpeg. That JPEG path assumed FULL-range YUV, which stretched this video's
     * limited-range (Y 16..235) values across 0..255 and produced the crushed-blacks / blown-out /
     * oversaturated look. Doing the conversion here with the correct range also removes a per-frame
     * JPEG encode+decode, so it's faster too.
     */
    private fun imageToBitmap(image: android.media.Image, width: Int, height: Int): Bitmap? {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vPixStride = vPlane.pixelStride

        val pixels = IntArray(width * height)

        for (row in 0 until height) {
            val yRowStart = row * yRowStride
            val chromaRow = row / 2
            val uRowStart = chromaRow * uRowStride
            val vRowStart = chromaRow * vRowStride
            var outIdx = row * width
            for (col in 0 until width) {
                val y = (yBuf.get(yRowStart + col).toInt() and 0xFF)
                val chromaCol = col / 2
                val u = (uBuf.get(uRowStart + chromaCol * uPixStride).toInt() and 0xFF)
                val v = (vBuf.get(vRowStart + chromaCol * vPixStride).toInt() and 0xFF)

                // BT.601 limited range: Y in 16..235, U/V centered at 128 in 16..240.
                val c = y - 16
                val d = u - 128
                val e = v - 128
                var r = (298 * c + 409 * e + 128) shr 8
                var g = (298 * c - 100 * d - 208 * e + 128) shr 8
                var b = (298 * c + 516 * d + 128) shr 8
                r = if (r < 0) 0 else if (r > 255) 255 else r
                g = if (g < 0) 0 else if (g > 255) 255 else g
                b = if (b < 0) 0 else if (b > 255) 255 else b

                pixels[outIdx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
