package com.thor.smartwall

import android.content.ContentValues
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.thor.smartwall.Prefs.gapFraction
import com.thor.smartwall.Prefs.gifUri
import com.thor.smartwall.Prefs.imageUri
import com.thor.smartwall.Prefs.imageUriSecondary
import com.thor.smartwall.Prefs.independentMode
import com.thor.smartwall.Prefs.mode
import com.thor.smartwall.Prefs.orientationVertical
import com.thor.smartwall.Prefs.rotationOverrideDegrees
import com.thor.smartwall.Prefs.swapOrder
import com.thor.smartwall.Prefs.videoSmoothMode
import com.thor.smartwall.Prefs.videoSmoothness
import com.thor.smartwall.Prefs.videoUri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a plain-text debug report a tester can generate with one tap and hand back to us. It
 * bundles the things we'd otherwise have to ask for one screenshot at a time: device model, OS,
 * exactly what Android's DisplayManager reports for each screen, every current app setting, and
 * the recent event log (including any failures the wallpaper service recorded after "Apply").
 */
object DebugReport {

    data class Result(val success: Boolean, val location: String)

    fun build(context: Context): String {
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        sb.appendLine("===== ThorPaper Debug Report =====")
        sb.appendLine("Generated: $now")
        sb.appendLine()

        sb.appendLine("--- Device ---")
        sb.appendLine("Manufacturer: ${Build.MANUFACTURER}")
        sb.appendLine("Model: ${Build.MODEL}")
        sb.appendLine("Device: ${Build.DEVICE}")
        sb.appendLine("Product: ${Build.PRODUCT}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Build ID: ${Build.DISPLAY}")
        sb.appendLine()

        sb.appendLine("--- Displays (what Android reports right now) ---")
        try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val displays = dm.displays
            sb.appendLine("Count: ${displays.size}")
            for (d in displays) {
                @Suppress("DEPRECATION") val pt = android.graphics.Point().also { d.getRealSize(it) }
                sb.appendLine("  id=${d.displayId} name=\"${d.name}\" ${pt.x}x${pt.y} state=${d.state} rotation=${d.rotation}")
            }
        } catch (t: Throwable) {
            sb.appendLine("  ERROR reading displays: ${t.message}")
        }
        sb.appendLine()

        sb.appendLine("--- App resolved screen model ---")
        try {
            val screens = DisplayDetector.findScreens(context, context.swapOrder)
            sb.appendLine("Resolved ${screens.size} screen(s):")
            for (s in screens) {
                sb.appendLine("  order=${s.order} id=${s.displayId} ${s.widthPx}x${s.heightPx}")
            }
        } catch (t: Throwable) {
            sb.appendLine("  ERROR resolving screens: ${t.message}")
        }
        sb.appendLine()

        sb.appendLine("--- Current settings ---")
        sb.appendLine("mode: ${context.mode}")
        sb.appendLine("independentMode (Smart Fit): ${context.independentMode}")
        sb.appendLine("orientationVertical: ${context.orientationVertical}")
        sb.appendLine("swapOrder: ${context.swapOrder}")
        sb.appendLine("gapFraction: ${context.gapFraction}")
        sb.appendLine("rotationOverrideDegrees: ${context.rotationOverrideDegrees}")
        sb.appendLine("videoSmoothMode: ${context.videoSmoothMode}")
        sb.appendLine("videoSmoothness: ${context.videoSmoothness}")
        sb.appendLine("imageUri set: ${context.imageUri != null}")
        sb.appendLine("imageUriSecondary set: ${context.imageUriSecondary != null}")
        sb.appendLine("videoUri set: ${context.videoUri != null}")
        sb.appendLine("gifUri set: ${context.gifUri != null}")
        sb.appendLine()

        sb.appendLine("--- URI permission check ---")
        sb.appendLine(uriPermissionStatus(context, "image", context.imageUri))
        sb.appendLine(uriPermissionStatus(context, "video", context.videoUri))
        sb.appendLine(uriPermissionStatus(context, "gif", context.gifUri))
        sb.appendLine()

        sb.appendLine("--- Battery optimization ---")
        try {
            val pm = context.getSystemService(android.os.PowerManager::class.java)
            sb.appendLine("Ignoring battery optimizations: ${pm.isIgnoringBatteryOptimizations(context.packageName)}")
        } catch (t: Throwable) {
            sb.appendLine("  ERROR: ${t.message}")
        }
        sb.appendLine()

        sb.appendLine("--- Recent event log (oldest first) ---")
        val logs = DebugLog.snapshot()
        if (logs.isEmpty()) {
            sb.appendLine("(no events recorded yet)")
        } else {
            logs.forEach { sb.appendLine(it) }
        }
        sb.appendLine()
        sb.appendLine("===== end of report =====")

        return sb.toString()
    }

    /**
     * A big reason for "does nothing after applying" is a lost URI read permission - so the report
     * checks each stored URI against the permissions Android has actually granted this app.
     */
    private fun uriPermissionStatus(context: Context, label: String, uriStr: String?): String {
        if (uriStr == null) return "$label: (none set)"
        return try {
            val uri = android.net.Uri.parse(uriStr)
            val held = context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }
            val readable = try {
                context.contentResolver.openInputStream(uri)?.use { true } ?: false
            } catch (_: Throwable) {
                false
            }
            "$label: persistedReadPermission=$held actuallyReadable=$readable"
        } catch (t: Throwable) {
            "$label: ERROR ${t.message}"
        }
    }

    /**
     * Saves the report to the device's public Downloads folder so it shows up in Files and can be
     * uploaded. Uses MediaStore on API 29+ (no storage permission needed) and a direct file write
     * on older versions.
     */
    fun saveToDownloads(context: Context): Result {
        val text = build(context)
        val fileName = "thorpaper-debug-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return Result(false, "Couldn't create file in Downloads")
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                Result(true, "Downloads/$fileName")
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, fileName)
                f.writeText(text)
                Result(true, "Downloads/$fileName")
            }
        } catch (t: Throwable) {
            DebugLog.e("Failed to save debug report", t)
            Result(false, t.message ?: "unknown error")
        }
    }
}
