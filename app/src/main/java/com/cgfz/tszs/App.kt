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
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val header = "\n==== ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} " +
            "thread=${t.name} ====\n"
        val body = header + sw.toString()

        // 两处都写,内部目录保证能写上,外部方便用户读
        runCatching { File(filesDir, "crash.log").appendText(body) }
        runCatching {
            val ext = getExternalFilesDir(null) ?: return@runCatching
            File(ext, "crash.log").appendText(body)
        }
    }

    companion object { private const val TAG = "tszs-app" }
}
