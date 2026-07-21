package com.thor.smartwall

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.roundToInt

/**
 * Turns one source image into a correctly-cropped bitmap per physical screen,
 * as if the screens were laid edge to edge with a hinge gap between them.
 *
 * Example (Thor, vertical stack): source gets center-crop-scaled to fill a
 * virtual canvas of width = max(screen widths), height = sum(screen heights)
 * + gap, then sliced top-to-bottom. That slice is what makes art line up
 * continuously across the hinge instead of each screen just getting its own
 * independent center-crop (which is what naive "set same wallpaper on both"
 * looks like, and why it never lines up).
 */
object SplitEngine {

    /**
     * @param gapFraction gap between adjacent screens, as a fraction of the
     *   combined content length along the stacking axis. 0f = screens butt
     *   up against each other with no gap. Tune this per-device until art
     *   lines up with the real hinge; ~0.02–0.06 is typical for a clamshell.
     * @param overscan extra margin (0f..~0.3f) baked into each crop so the
     *   Ken Burns pan/zoom in the live wallpaper has room to move without
     *   ever revealing an edge. Pass 0f for exports meant to be static.
     */
    fun computeCrops(
        source: Bitmap,
        screens: List<ScreenSpec>,
        orientation: StackOrientation,
        gapFraction: Float,
        overscan: Float = 0f
    ): Map<Int, Bitmap> {
        if (screens.isEmpty()) return emptyMap()
        val ordered = screens.sortedBy { it.order }

        val axisIsVertical = orientation == StackOrientation.VERTICAL

        val crossSize = ordered.maxOf { if (axisIsVertical) it.widthPx else it.heightPx }
        val contentAlongAxis = ordered.sumOf { if (axisIsVertical) it.heightPx else it.widthPx }
        val gapPx = (contentAlongAxis * gapFraction).roundToInt()
        val totalAlongAxis = contentAlongAxis + gapPx * (ordered.size - 1).coerceAtLeast(0)

        // Expand the whole virtual canvas by the overscan factor so every
        // screen's slice has breathing room to pan/zoom into.
        val canvasCross = (crossSize * (1f + overscan)).roundToInt().coerceAtLeast(1)
        val canvasAlong = (totalAlongAxis * (1f + overscan)).roundToInt().coerceAtLeast(1)

        val canvasW = if (axisIsVertical) canvasCross else canvasAlong
        val canvasH = if (axisIsVertical) canvasAlong else canvasCross

        val filled = centerCropScale(source, canvasW, canvasH)

        val result = mutableMapOf<Int, Bitmap>()
        var cursor = ((canvasAlong - totalAlongAxis) / 2f) // center the real content within overscan margin
        for (screen in ordered) {
            val lengthAlong = if (axisIsVertical) screen.heightPx else screen.widthPx
            val outW: Int
            val outH: Int
            val cropRect: Rect
            if (axisIsVertical) {
                outW = (screen.widthPx * (1f + overscan)).roundToInt().coerceAtLeast(1)
                outH = (lengthAlong * (1f + overscan)).roundToInt().coerceAtLeast(1)
                val yStart = (cursor - (outH - lengthAlong) / 2f).roundToInt().coerceIn(0, filled.height - outH)
                val xStart = ((filled.width - outW) / 2f).roundToInt().coerceIn(0, (filled.width - outW).coerceAtLeast(0))
                cropRect = Rect(xStart, yStart, xStart + outW, yStart + outH)
            } else {
                outH = (screen.heightPx * (1f + overscan)).roundToInt().coerceAtLeast(1)
                outW = (lengthAlong * (1f + overscan)).roundToInt().coerceAtLeast(1)
                val xStart = (cursor - (outW - lengthAlong) / 2f).roundToInt().coerceIn(0, filled.width - outW)
                val yStart = ((filled.height - outH) / 2f).roundToInt().coerceIn(0, (filled.height - outH).coerceAtLeast(0))
                cropRect = Rect(xStart, yStart, xStart + outW, yStart + outH)
            }

            val slice = Bitmap.createBitmap(
                filled,
                cropRect.left.coerceIn(0, filled.width - 1),
                cropRect.top.coerceIn(0, filled.height - 1),
                cropRect.width().coerceAtMost(filled.width - cropRect.left).coerceAtLeast(1),
                cropRect.height().coerceAtMost(filled.height - cropRect.top).coerceAtLeast(1)
            )
            result[screen.displayId] = slice
            cursor += lengthAlong + gapPx
        }
        return result
    }

    /** Each screen gets its own independently center-cropped image (no continuity across the hinge). */
    fun computeIndependentCrops(
        sources: Map<Int, Bitmap>,
        screens: List<ScreenSpec>,
        overscan: Float = 0f
    ): Map<Int, Bitmap> {
        val result = mutableMapOf<Int, Bitmap>()
        for (screen in screens) {
            val src = sources[screen.displayId] ?: sources.values.firstOrNull() ?: continue
            val w = (screen.widthPx * (1f + overscan)).roundToInt().coerceAtLeast(1)
            val h = (screen.heightPx * (1f + overscan)).roundToInt().coerceAtLeast(1)
            result[screen.displayId] = centerCropScale(src, w, h)
        }
        return result
    }

    /** Classic "center crop" fill: scale to cover, then crop the overflow evenly from both sides. */
    fun centerCropScale(source: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val srcW = source.width.toFloat()
        val srcH = source.height.toFloat()
        val scale = maxOf(targetW / srcW, targetH / srcH)
        val scaledW = (srcW * scale).roundToInt()
        val scaledH = (srcH * scale).roundToInt()

        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val dx = (targetW - scaledW) / 2f
        val dy = (targetH - scaledH) / 2f
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
