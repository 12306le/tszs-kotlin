package com.cgfz.tszs.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.opencv.android.Utils
import org.opencv.core.Mat

/**
 * 截屏核心。
 *
 * 关键设计:**持续消费 + 缓存最新帧**。
 * 原因:MediaProjection 在屏幕静止时可能不再推送新帧,
 *       单次 setOnImageAvailableListener 后 await 可能永久挂起。
 * 改法:start() 时就挂一个持久 listener,每帧消费后写入 latestBitmap。
 *       captureBitmap() 从 latest 读副本,快速返回。首次还没帧时等一会儿。
 */
class ScreenCapturer(
    private val projection: MediaProjection,
    metrics: DisplayMetrics,
) {
    private val width = metrics.widthPixels
    private val height = metrics.heightPixels
    private val density = metrics.densityDpi

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private val readerThread = HandlerThread("capture-reader").apply { start() }
    private val readerHandler = Handler(readerThread.looper)

    // API 34+ 强制要求
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stop() }
    }

    // 持久缓存(最新一帧的 immutable 副本)
    private val frameLock = Any()
    @Volatile private var latestBitmap: Bitmap? = null
    @Volatile private var frameCount: Long = 0L

    fun start() {
        projection.registerCallback(projectionCallback, readerHandler)
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ rd ->
            val image: Image = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val bmp = imageToIndependentBitmap(image)
                synchronized(frameLock) {
                    latestBitmap?.recycle()
                    latestBitmap = bmp
                    frameCount++
                }
            } catch (t: Throwable) {
                Log.e(TAG, "consume image failed", t)
            } finally {
                image.close()
            }
        }, readerHandler)
        display = projection.createVirtualDisplay(
            "tszs-capture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, readerHandler
        )
    }

    /** 阻塞获取最新帧的副本。缺省 3s 超时。 */
    suspend fun captureBitmap(timeoutMs: Long = 3000L): Bitmap {
        val deadline = System.currentTimeMillis() + timeoutMs
        // 等到至少一帧到达
        while (latestBitmap == null && System.currentTimeMillis() < deadline) {
            delay(30)
        }
        synchronized(frameLock) {
            val b = latestBitmap ?: error("截屏超时 ${timeoutMs}ms 没收到帧")
            return b.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    /** 强制获取 stale 后的新帧:等 frameCount 增长再取。用于"隐藏悬浮窗后截屏"。 */
    suspend fun captureFreshBitmap(timeoutMs: Long = 2000L): Bitmap {
        val sentinel = frameCount
        val deadline = System.currentTimeMillis() + timeoutMs
        while (frameCount == sentinel && System.currentTimeMillis() < deadline) {
            delay(20)
        }
        return captureBitmap(timeoutMs)
    }

    suspend fun captureMat(): Mat {
        val bmp = captureBitmap()
        val mat = Mat()
        Utils.bitmapToMat(bmp, mat)
        return mat
    }

    private fun imageToIndependentBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val realWidth = width + rowPadding / pixelStride
        val raw = Bitmap.createBitmap(realWidth, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        raw.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) raw
        else {
            val cropped = Bitmap.createBitmap(raw, 0, 0, width, height)
            raw.recycle()
            cropped
        }
    }

    fun stop() {
        try { display?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        synchronized(frameLock) {
            latestBitmap?.recycle()
            latestBitmap = null
        }
        reader = null
        display = null
    }

    fun release() {
        stop()
        readerThread.quitSafely()
        try { projection.unregisterCallback(projectionCallback) } catch (_: Throwable) {}
        try { projection.stop() } catch (_: Throwable) {}
    }

    companion object { private const val TAG = "ScreenCapturer" }
}
