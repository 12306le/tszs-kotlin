package com.cgfz.tszs

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局异常处理。
 *   1. /sdcard/Download/tszs-crash.log   (MediaStore, Android 10+ 无需权限)
 *   2. /Android/data/com.cgfz.tszs/files/crash.log   (app 私有外部)
 *   3. filesDir/crash.log                (app 内部,最后兜底)
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
            "thread=${t.name} proc=${android.os.Process.myPid()} ====\n"
        val body = header + sw.toString()

        runCatching { File(filesDir, "crash.log").appendText(body) }
        runCatching { getExternalFilesDir(null)?.let { File(it, "crash.log").appendText(body) } }
        runCatching { writeToPublicDownloads(body) }
    }

    private fun writeToPublicDownloads(body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 通过 MediaStore 写入公共 Download 目录,无需运行时权限
            val resolver = contentResolver
            val uri = findExistingOrCreate(resolver)
            resolver.openOutputStream(uri, "wa")?.use { os ->
                os.write(body.toByteArray())
            }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(dir, "tszs-crash.log").appendText(body)
        }
    }

    private fun findExistingOrCreate(resolver: android.content.ContentResolver): android.net.Uri {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=?",
            arrayOf("tszs-crash.log"),
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                return android.content.ContentUris.withAppendedId(collection, id)
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "tszs-crash.log")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        return resolver.insert(collection, values)!!
    }

    companion object { private const val TAG = "tszs-app" }
}
