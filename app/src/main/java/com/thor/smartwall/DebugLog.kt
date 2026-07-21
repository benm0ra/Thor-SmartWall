package com.thor.smartwall

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A tiny, dependency-free logger that keeps the most recent events both in memory and appended to
 * a small file, so a user can generate a debug report we can actually read even though they can't
 * run adb/logcat. It's intentionally simple: bounded in-memory ring buffer + a capped file. Every
 * meaningful lifecycle/error event in the app and the wallpaper service calls into here.
 *
 * This is NOT a replacement for logcat during development - it's the field-diagnostics channel for
 * a non-technical tester whose only tools are "tap a button" and "upload the file it made".
 */
object DebugLog {

    private const val MAX_LINES = 500
    private const val LOG_FILE = "thorpaper_debug.log"
    private const val TAG = "ThorPaper"

    private val buffer = ConcurrentLinkedQueue<String>()
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var appContext: Context? = null

    /** Call once early (Application.onCreate or first Activity) so file persistence works. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun d(msg: String) = add("D", msg, null)
    fun w(msg: String) = add("W", msg, null)
    fun e(msg: String, t: Throwable? = null) = add("E", msg, t)

    private fun add(level: String, msg: String, t: Throwable?) {
        val line = buildString {
            append(timeFmt.format(Date()))
            append(' ').append(level).append(' ')
            append(msg)
            if (t != null) {
                append(" | ").append(t.javaClass.simpleName).append(": ").append(t.message)
            }
        }
        when (level) {
            "E" -> Log.e(TAG, msg, t)
            "W" -> Log.w(TAG, msg)
            else -> Log.d(TAG, msg)
        }
        buffer.add(line)
        while (buffer.size > MAX_LINES) buffer.poll()
        appendToFile(line)
    }

    private fun appendToFile(line: String) {
        val ctx = appContext ?: return
        try {
            val f = File(ctx.filesDir, LOG_FILE)
            // Cheap cap: if the file gets big, rewrite it from the in-memory buffer instead.
            if (f.exists() && f.length() > 256 * 1024) {
                f.writeText(buffer.joinToString("\n") + "\n")
            } else {
                f.appendText(line + "\n")
            }
        } catch (_: Throwable) {
            // Never let logging crash the app.
        }
    }

    /** The recent in-memory lines, newest last. Used when composing a report. */
    fun snapshot(): List<String> = buffer.toList()

    fun clear() {
        buffer.clear()
        val ctx = appContext ?: return
        try {
            File(ctx.filesDir, LOG_FILE).delete()
        } catch (_: Throwable) {
        }
    }
}
