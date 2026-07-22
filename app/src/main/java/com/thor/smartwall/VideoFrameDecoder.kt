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
                            val outFormat = dec.getOutputFormat(outIndex)
                            val bmp = outputBufferToBitmap(dec, outIndex, outFormat, width, height)
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

    /** Reads a decoded YUV output buffer and converts it to a Bitmap via NV21 -> JPEG. */
    private fun outputBufferToBitmap(
        dec: MediaCodec,
        index: Int,
        outFormat: MediaFormat,
        width: Int,
        height: Int
    ): Bitmap? {
        val buffer = dec.getOutputBuffer(index) ?: return null
        buffer.rewind()
        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        val nv21 = toNv21(data, outFormat, width, height) ?: return null
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, width, height), 85, out)
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /**
     * Converts a raw decoder output buffer to NV21, handling the two color formats that cover the
     * vast majority of hardware decoders:
     *  - COLOR_FormatYUV420Planar (I420): Y plane, then U plane, then V plane.
     *  - COLOR_FormatYUV420SemiPlanar (NV12): Y plane, then interleaved UV.
     * NV21 wants Y followed by interleaved VU.
     */
    private fun toNv21(data: ByteArray, outFormat: MediaFormat, width: Int, height: Int): ByteArray? {
        val frameSize = width * height
        val expected = frameSize + frameSize / 2
        if (data.size < expected) return null

        val colorFormat = try { outFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT) } catch (_: Throwable) { 0 }
        val nv21 = ByteArray(expected)
        // Copy Y as-is.
        System.arraycopy(data, 0, nv21, 0, frameSize)

        val chromaSize = frameSize / 4
        when (colorFormat) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> {
                // NV12: Y then U,V interleaved. Swap to V,U for NV21.
                val uvStart = frameSize
                var o = frameSize
                var i = 0
                while (i < chromaSize) {
                    val u = data[uvStart + i * 2]
                    val v = data[uvStart + i * 2 + 1]
                    nv21[o++] = v
                    nv21[o++] = u
                    i++
                }
            }
            else -> {
                // Assume planar I420: Y, then U plane, then V plane. (Also the fallback for the
                // flexible format we requested, COLOR_FormatYUV420Flexible.)
                val uStart = frameSize
                val vStart = frameSize + chromaSize
                var o = frameSize
                var i = 0
                while (i < chromaSize) {
                    nv21[o++] = data[vStart + i] // V
                    nv21[o++] = data[uStart + i] // U
                    i++
                }
            }
        }
        return nv21
    }
}
