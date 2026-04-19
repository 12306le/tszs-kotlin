package com.cgfz.tszs.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import com.cgfz.tszs.capture.ScreenCapturer

/**
 * 前台服务持有 MediaProjection 和 ScreenCapturer 生命周期。
 * startForeground 必须在 onStartCommand 开头立即调用,否则 Android 10+ 会抛 ISE。
 */
class CaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "tszs_capture"
        private const val NOTIFICATION_ID = 1001

        @Volatile var instance: CaptureService? = null
            private set
    }

    private val binder = LocalBinder()
    var capturer: ScreenCapturer? = null
        private set

    inner class LocalBinder : Binder() { fun service() = this@CaptureService }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (code != 0 && data != null) startCapture(code, data)
        instance = this
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        if (capturer != null) return   // 已就绪,避免重复创建
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mgr.getMediaProjection(resultCode, data) ?: return
        capturer = ScreenCapturer(projection, currentRealMetrics()).apply { start() }
    }

    /** 旋转时调用,按新屏幕尺寸重建 VirtualDisplay */
    fun onOrientationChanged() {
        capturer?.resizeTo(currentRealMetrics())
    }

    private fun currentRealMetrics(): DisplayMetrics = DisplayMetrics().also {
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(it)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        onOrientationChanged()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "截屏服务", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("图色助手")
            .setContentText("截屏服务运行中")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    override fun onDestroy() {
        instance = null
        capturer?.release()
        capturer = null
        super.onDestroy()
    }
}
