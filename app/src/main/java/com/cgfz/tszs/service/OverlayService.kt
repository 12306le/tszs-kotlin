package com.cgfz.tszs.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cgfz.tszs.imageproc.ArgbColor
import com.cgfz.tszs.imageproc.ImageOps
import com.cgfz.tszs.imageproc.ImageOps.toMatRgba
import com.cgfz.tszs.overlay.ColorRecord
import com.cgfz.tszs.overlay.OverlayHost
import com.cgfz.tszs.overlay.OverlayRoot
import com.cgfz.tszs.overlay.OverlayUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Mat

/**
 * 悬浮窗前台服务。CaptureService 必须已启动(可访问 capturer)。
 */
class OverlayService : Service() {

    private lateinit var host: OverlayHost
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var uiState by mutableStateOf(OverlayUiState(expanded = false))
    private var currentBitmap: android.graphics.Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        host = OverlayHost(this)
        host.show {
            OverlayRoot(
                state = uiState,
                onCapture = ::doCapture,
                onFindTest = ::doFindTest,
                onCompareTest = ::doCompareTest,
                onSaveScript = { /* 交给上层模块 */ },
                onDeleteColor = { idx ->
                    uiState = uiState.copy(colors = uiState.colors.toMutableList().also { it.removeAt(idx) })
                },
                onClearColors = { uiState = uiState.copy(colors = emptyList()) },
                onDrag = { dx, dy ->
                    host.updatePosition(host.params.x + dx.toInt(), host.params.y + dy.toInt())
                },
                onToggle = { uiState = uiState.copy(expanded = !uiState.expanded) },
            )
        }
    }

    private fun doCapture() {
        val capturer = CaptureService.instance?.capturer ?: run {
            uiState = uiState.copy(lastMessage = "截屏服务未启动")
            return
        }
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { capturer.captureBitmap() }
            currentBitmap = bmp
            uiState = uiState.copy(lastMessage = "截屏 OK ${bmp.width}×${bmp.height}")
        }
    }

    private fun doFindTest() {
        val bmp = currentBitmap ?: run {
            uiState = uiState.copy(lastMessage = "先截屏"); return
        }
        val colors = uiState.colors
        if (colors.size < 2) {
            uiState = uiState.copy(lastMessage = "至少 2 个颜色记录"); return
        }
        scope.launch {
            val (p, ms) = withContext(Dispatchers.Default) {
                val mat: Mat = bmp.toMatRgba()
                val base = ArgbColor(colors[0].argb.toInt())
                val offsets = colors.drop(1).map {
                    com.cgfz.tszs.imageproc.ColorPoint(
                        it.x - colors[0].x, it.y - colors[0].y, ArgbColor(it.argb.toInt())
                    )
                }
                val t = System.currentTimeMillis()
                val found = ImageOps.findMultiColors(mat, base, offsets, threshold = 4)
                mat.release()
                found to (System.currentTimeMillis() - t)
            }
            uiState = uiState.copy(
                lastMessage = if (p == null) "没找到(${ms}ms)" else "命中 (${p.x},${p.y}) ${ms}ms"
            )
        }
    }

    private fun doCompareTest() {
        val bmp = currentBitmap ?: run {
            uiState = uiState.copy(lastMessage = "先截屏"); return
        }
        scope.launch {
            val (n, ms) = withContext(Dispatchers.Default) {
                val mat = bmp.toMatRgba()
                val pts = uiState.colors.map {
                    com.cgfz.tszs.imageproc.ColorPoint(it.x, it.y, ArgbColor(it.argb.toInt()))
                }
                val t = System.currentTimeMillis()
                val c = ImageOps.detectsColorCount(mat, pts, 4)
                mat.release()
                c to (System.currentTimeMillis() - t)
            }
            uiState = uiState.copy(lastMessage = "命中 $n/${uiState.colors.size} ${ms}ms")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        host.close()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = "tszs_overlay"
        if (nm.getNotificationChannel(ch) == null) {
            nm.createNotificationChannel(
                NotificationChannel(ch, "悬浮窗", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = Notification.Builder(this, ch)
            .setContentTitle("图色助手")
            .setContentText("悬浮窗运行中")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1002, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1002, n)
        }
    }
}
