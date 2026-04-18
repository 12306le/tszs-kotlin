package com.cgfz.tszs

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局异常处理。崩溃栈写到 /Android/data/com.cgfz.tszs/files/crash.log
 * 文件管理器可见,免 adb。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { writeCrash(t, e) }
            Log.e(TAG, "uncaught on ${t.name}", e)
            prev?.uncaughtException(t, e)
        }
    }

    private fun writeCrash(t: Thread, e: Throwable) {
        val dir = getExternalFilesDir(null) ?: filesDir
        val f = File(dir, "crash.log")
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val header = "\n==== ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} " +
            "thread=${t.name} ====\n"
        f.appendText(header + sw.toString())
    }

    companion object { private const val TAG = "tszs-app" }
}
