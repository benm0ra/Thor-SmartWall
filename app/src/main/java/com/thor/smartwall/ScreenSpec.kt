package com.thor.smartwall

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/** How the two (or more) panels are physically arranged relative to each other. */
enum class StackOrientation { VERTICAL, HORIZONTAL }

/**
 * One physical screen as Android sees it right now.
 * [order] is our best guess at its position in the stack: 0 = top/left, 1 = bottom/right, ...
 */
data class ScreenSpec(
    val displayId: Int,
    val order: Int,
    val widthPx: Int,
    val heightPx: Int
)

/**
 * Finds every currently-active display and figures out a stable top-to-bottom
 * (or left-to-right) order for them.
 *
 * There is no public Android API that reports the physical position of a
 * secondary panel relative to the default one (this is a known platform gap —
 * see source.android.com's multi-display docs), so we use the next best thing:
 * the default display (id == Display.DEFAULT_DISPLAY) is always the Thor's
 * top/main screen, and every other active display is sorted by id and placed
 * after it. If that guess ever comes out backwards on a given unit, the
 * "Swap screen order" toggle in the app flips it without needing new code.
 */
object DisplayDetector {

    fun findScreens(context: Context, swapOrder: Boolean): List<ScreenSpec> {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = dm.displays
            .filter { it.state == Display.STATE_ON || it.state == Display.STATE_UNKNOWN }
            .distinctBy { it.displayId }

        val ordered = displays.sortedBy { display ->
            if (display.displayId == Display.DEFAULT_DISPLAY) Int.MIN_VALUE else display.displayId
        }

        val specs = ordered.mapIndexed { index, display ->
            val bounds = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(bounds)
            ScreenSpec(
                displayId = display.displayId,
                order = index,
                widthPx = bounds.x,
                heightPx = bounds.y
            )
        }

        return if (swapOrder && specs.size >= 2) {
            val flipped = specs.toMutableList()
            val a = flipped[0]
            val b = flipped[1]
            flipped[0] = a.copy(order = b.order)
            flipped[1] = b.copy(order = a.order)
            flipped.sortedBy { it.order }
        } else specs
    }

    /**
     * The Thor's panels used *as actually displayed* - landscape, confirmed both by the
     * in-app "Show Screen Info" diagnostic (which reported 1920x1080 and 1240x1080) and by a
     * reference photo of the running device: it's held/used landscape like a Switch, not
     * portrait like a book-style DS. Used only for the in-app preview when the app is running
     * as a normal single-screen activity and can't see the second panel itself. The live
     * wallpaper engine never uses this — it always asks DisplayManager for the real thing at
     * draw time, so it keeps working correctly even on Thor variants with slightly different
     * panels.
     */
    val PREVIEW_FALLBACK = listOf(
        ScreenSpec(displayId = -1, order = 0, widthPx = 1920, heightPx = 1080),
        ScreenSpec(displayId = -2, order = 1, widthPx = 1240, heightPx = 1080)
    )
}
