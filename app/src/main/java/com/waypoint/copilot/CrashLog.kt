package com.waypoint.copilot

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Saves the details of a crash so the next launch can show them. */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "last-crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                file(app).writeText(
                    "Waypoint ${BuildInfo.version(app)} on Android ${android.os.Build.VERSION.RELEASE} " +
                        "(${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL})\n" +
                        "Thread: ${thread.name}\n\n$sw"
                )
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, error)
        }
    }

    /** Returns the saved crash (once), or null. */
    fun take(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        val text = try { f.readText() } catch (_: Throwable) { null }
        f.delete()
        return text
    }
}

object BuildInfo {
    fun version(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Throwable) { "?" }
}
