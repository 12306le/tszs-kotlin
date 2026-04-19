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
    initialMetrics: DisplayMetrics,
) {
    @Volatile private var width = initialMetrics.widthPixels
    @Volatile private var height = initialMetrics.heightPixels
    @Volatile private var density = initialMetrics.densityDpi

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
        val r = buildReader(width, height)
        reader = r
        display = projection.createVirtualDisplay(
            "tszs-capture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, readerHandler
        )
    }

    /**
     * 旋转时调用。关键:**不重建 VirtualDisplay**,只替换 ImageReader 的 surface。
     * 这样 MediaProjection 对象保持活着,不会触发系统 stop,用户无需重新授权。
     * 这是 AutoJs 等开源项目处理旋转的标准做法。
     */
    fun resizeTo(newMetrics: DisplayMetrics) {
        val newW = newMetrics.widthPixels
        val newH = newMetrics.heightPixels
        val newDensity = newMetrics.densityDpi
        if (newW == width && newH == height && newDensity == density) return

        val oldReader = reader
        val newReader = buildReader(newW, newH)

        try {
            // 切换 VirtualDisplay 的 surface,然后 resize(API 20+,token 保持)
            display?.surface = newReader.surface
            display?.resize(newW, newH, newDensity)
        } catch (t: Throwable) {
            Log.e(TAG, "resize virtual display failed", t)
            try { newReader.close() } catch (_: Throwable) {}
            return
        }

        reader = newReader
        width = newW; height = newH; density = newDensity
        synchronized(frameLock) {
            latestBitmap?.recycle()
            latestBitmap = null
            frameCount = 0L
        }
        // 延迟关旧 reader,防止还有 in-flight 帧读它
        readerHandler.postDelayed({
            try { oldReader?.close() } catch (_: Throwable) {}
        }, 500L)
    }

    private fun buildReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ rd ->
            val image: Image = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val bmp = imageToIndependentBitmap(image, w)
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
        return r
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

    private fun imageToIndependentBitmap(image: Image, expectedWidth: Int = width): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * expectedWidth
        val realWidth = expectedWidth + rowPadding / pixelStride
        val raw = Bitmap.createBitmap(realWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        raw.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) raw
        else {
            val cropped = Bitmap.createBitmap(raw, 0, 0, expectedWidth, image.height)
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
