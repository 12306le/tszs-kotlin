package com.cgfz.tszs.capture

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.opencv.android.Utils
import org.opencv.core.Mat
import kotlin.coroutines.resume

/**
 * 截屏核心:MediaProjection + ImageReader。
 * 单次 capture() 或 stream() 连续获取帧,直接产出 Bitmap / OpenCV Mat。
 */
class ScreenCapturer(
    private val projection: MediaProjection,
    private val metrics: DisplayMetrics,
) {
    private val width = metrics.widthPixels
    private val height = metrics.heightPixels
    private val density = metrics.densityDpi

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private val readerThread = HandlerThread("capture-reader").apply { start() }
    private val readerHandler = Handler(readerThread.looper)

    // 复用 Bitmap,避免反复分配
    private var reusableBitmap: Bitmap? = null

    fun start() {
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        display = projection.createVirtualDisplay(
            "tszs-capture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, readerHandler
        )
    }

    /** 阻塞获取下一帧 Bitmap。调用方负责在非主线程调用。 */
    suspend fun captureBitmap(): Bitmap = suspendCancellableCoroutine { cont ->
        val r = reader ?: run {
            cont.resumeWith(Result.failure(IllegalStateException("capturer not started")))
            return@suspendCancellableCoroutine
        }
        val listener = object : ImageReader.OnImageAvailableListener {
            override fun onImageAvailable(reader: ImageReader) {
                reader.setOnImageAvailableListener(null, null)
                val image = reader.acquireLatestImage() ?: run {
                    cont.resumeWith(Result.failure(IllegalStateException("null image")))
                    return
                }
                try {
                    cont.resume(imageToBitmap(image))
                } catch (t: Throwable) {
                    cont.resumeWith(Result.failure(t))
                } finally {
                    image.close()
                }
            }
        }
        r.setOnImageAvailableListener(listener, readerHandler)
        cont.invokeOnCancellation { r.setOnImageAvailableListener(null, null) }
    }

    /** 作为 Mat 获取,避免 Bitmap→Mat 的多余拷贝时可重载此方法 */
    suspend fun captureMat(): Mat {
        val bmp = captureBitmap()
        val mat = Mat()
        Utils.bitmapToMat(bmp, mat)
        return mat
    }

    /** 连续帧流,用于实时取色/找图循环。每次 emit 的 Bitmap 会被下一次覆写。 */
    fun stream(): Flow<Bitmap> = callbackFlow {
        val r = reader ?: throw IllegalStateException("capturer not started")
        val listener = ImageReader.OnImageAvailableListener { rd ->
            val image = rd.acquireLatestImage() ?: return@OnImageAvailableListener
            try { trySend(imageToBitmap(image)) } finally { image.close() }
        }
        r.setOnImageAvailableListener(listener, readerHandler)
        awaitClose { r.setOnImageAvailableListener(null, null) }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val realWidth = width + rowPadding / pixelStride
        val bmp = reusableBitmap?.takeIf { it.width == realWidth && it.height == height }
            ?: Bitmap.createBitmap(realWidth, height, Bitmap.Config.ARGB_8888)
                .also { reusableBitmap = it }

        buffer.rewind()
        bmp.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) bmp
        else Bitmap.createBitmap(bmp, 0, 0, width, height)
    }

    fun stop() {
        try { display?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        reusableBitmap?.recycle()
        reusableBitmap = null
        reader = null
        display = null
    }

    fun release() {
        stop()
        readerThread.quitSafely()
        try { projection.stop() } catch (_: Throwable) {}
    }

    companion object { private const val TAG = "ScreenCapturer" }
}
