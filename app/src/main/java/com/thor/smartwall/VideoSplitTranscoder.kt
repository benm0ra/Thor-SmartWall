package com.thor.smartwall

import android.content.Context
import android.net.Uri
import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy
import java.io.File

/**
 * Pre-splits one source video into two smooth, playable MP4 files - one already cropped to the
 * top screen's shape, one to the bottom's. This is the "your idea" path: do the expensive cropping
 * ONCE here, so the wallpaper can then just play each file with a plain MediaPlayer (smooth,
 * full-framerate) that happens to already be the right shape. No per-frame work at draw time.
 *
 * Honest scope note: the Transcoder library crops each output around the center of the source
 * (independent per-screen center-crop), which matches this app's "Smart Fit" behaviour - each
 * screen its own crop - rather than the continuous-across-the-hinge split. That's an inherent
 * property of cropping two files separately; a continuous split can't be expressed as two
 * independent center-crops. Smart Fit is the app default anyway, so this fits the common case.
 *
 * Everything is heavily logged via DebugLog so a field debug report shows exactly what happened.
 */
object VideoSplitTranscoder {

    /** Where the per-screen cropped files live. Kept in the app's private files dir. */
    fun outputFileFor(context: Context, screenTag: String): File =
        File(context.filesDir, "split_video_$screenTag.mp4")

    interface Callback {
        fun onProgress(fraction: Double)
        fun onComplete(topFile: File, bottomFile: File)
        fun onError(message: String)
    }

    /**
     * Transcodes [sourceUri] into two cropped files sized for the given screens. Runs the two
     * transcodes in sequence (top, then bottom) and reports combined progress. Safe to call from
     * the main thread - Transcoder does its work on its own threads and calls back on the main one.
     */
    fun split(
        context: Context,
        sourceUri: Uri,
        topWidth: Int,
        topHeight: Int,
        bottomWidth: Int,
        bottomHeight: Int,
        callback: Callback
    ) {
        val topOut = outputFileFor(context, "top")
        val bottomOut = outputFileFor(context, "bottom")
        // Clear any stale outputs so a half-written file from a previous failed run can't be reused.
        runCatching { if (topOut.exists()) topOut.delete() }
        runCatching { if (bottomOut.exists()) bottomOut.delete() }

        DebugLog.d("VideoSplit: starting top crop -> ${topWidth}x$topHeight")

        // AVC encoders require even dimensions; round down to be safe.
        val tW = (topWidth / 2) * 2
        val tH = (topHeight / 2) * 2
        val bW = (bottomWidth / 2) * 2
        val bH = (bottomHeight / 2) * 2

        // Conservative, widely-playable output. exact(w,h) crops to the screen aspect; the extra
        // options (explicit frame rate + regular key frames) produce a file the device's own
        // decoder is far more likely to play back on a wallpaper surface. Audio is removed - a
        // wallpaper is silent, and dropping the audio track avoids a class of muxing failures.
        val topStrategy = DefaultVideoStrategy.exact(tW, tH)
            .frameRate(30)
            .keyFrameInterval(1f)
            .build()
        val bottomStrategy = DefaultVideoStrategy.exact(bW, bH)
            .frameRate(30)
            .keyFrameInterval(1f)
            .build()
        val noAudio = com.otaliastudios.transcoder.strategy.RemoveTrackStrategy()

        Transcoder.into(topOut.absolutePath)
            .addDataSource(context, sourceUri)
            .setVideoTrackStrategy(topStrategy)
            .setAudioTrackStrategy(noAudio)
            .setListener(object : TranscoderListener {
                override fun onTranscodeProgress(progress: Double) {
                    callback.onProgress(progress * 0.5) // top is first half of overall progress
                }

                override fun onTranscodeCompleted(successCode: Int) {
                    DebugLog.d("VideoSplit: top done (code=$successCode), starting bottom -> ${bW}x$bH")
                    transcodeBottom(context, sourceUri, bottomStrategy, topOut, bottomOut, callback)
                }

                override fun onTranscodeCanceled() {
                    DebugLog.w("VideoSplit: top transcode canceled")
                    callback.onError("Video processing was canceled")
                }

                override fun onTranscodeFailed(exception: Throwable) {
                    DebugLog.e("VideoSplit: top transcode failed", exception)
                    callback.onError("Couldn't process the top screen: ${exception.message}")
                }
            })
            .transcode()
    }

    private fun transcodeBottom(
        context: Context,
        sourceUri: Uri,
        bottomStrategy: com.otaliastudios.transcoder.strategy.TrackStrategy,
        topOut: File,
        bottomOut: File,
        callback: Callback
    ) {
        Transcoder.into(bottomOut.absolutePath)
            .addDataSource(context, sourceUri)
            .setVideoTrackStrategy(bottomStrategy)
            .setAudioTrackStrategy(com.otaliastudios.transcoder.strategy.RemoveTrackStrategy())
            .setListener(object : TranscoderListener {
                override fun onTranscodeProgress(progress: Double) {
                    callback.onProgress(0.5 + progress * 0.5) // bottom is second half
                }

                override fun onTranscodeCompleted(successCode: Int) {
                    DebugLog.d("VideoSplit: bottom done (code=$successCode) - both files ready")
                    if (topOut.exists() && bottomOut.exists() && topOut.length() > 0 && bottomOut.length() > 0) {
                        callback.onComplete(topOut, bottomOut)
                    } else {
                        callback.onError("Processed files were empty - this video may use a codec the device can't re-encode")
                    }
                }

                override fun onTranscodeCanceled() {
                    DebugLog.w("VideoSplit: bottom transcode canceled")
                    callback.onError("Video processing was canceled")
                }

                override fun onTranscodeFailed(exception: Throwable) {
                    DebugLog.e("VideoSplit: bottom transcode failed", exception)
                    callback.onError("Couldn't process the bottom screen: ${exception.message}")
                }
            })
            .transcode()
    }
}
