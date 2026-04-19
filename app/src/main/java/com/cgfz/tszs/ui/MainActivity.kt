package com.cgfz.tszs.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.util.Log
import com.cgfz.tszs.service.CaptureService
import com.cgfz.tszs.service.OverlayService
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {

    private var opencvOk = false

    // 运行时权限(通知)
    private val runtimePerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        Log.i("tszs", "runtime perms = $grants")
    }

    // 截屏授权
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) {
            startCaptureService(r.resultCode, data)
            startService(Intent(this, OverlayService::class.java))
            statusText = "截屏 + 悬浮窗已启动"
        } else {
            statusText = "截屏授权被拒绝"
        }
    }

    private var statusText by mutableStateOf("准备中…")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        opencvOk = runCatching { OpenCVLoader.initLocal() }
            .onFailure { Log.e("tszs", "OpenCV init failed", it) }
            .getOrDefault(false)

        requestRuntimePermissions()

        setContent {
            MaterialTheme {
                Scaffold { pad ->
                    Column(
                        Modifier.fillMaxSize().padding(pad).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("图色助手 Pro (Kotlin + OpenCV)", style = MaterialTheme.typography.titleLarge)
                        Text("OpenCV: ${if (opencvOk) "OK" else "FAIL"}")
                        Text("状态: $statusText")

                        Button(onClick = ::onClickStart, modifier = Modifier.fillMaxWidth()) {
                            Text("一键启动(授权 + 启动悬浮窗)")
                        }
                        Button(onClick = { requestOverlay() }, modifier = Modifier.fillMaxWidth()) {
                            Text("仅申请悬浮窗权限")
                        }
                        Button(onClick = ::requestRuntimePermissions, modifier = Modifier.fillMaxWidth()) {
                            Text("仅申请通知权限")
                        }
                    }
                }
            }
        }
    }

    /** 一键:悬浮窗 → 通知权限 → 截屏授权 → 启动服务 */
    private fun onClickStart() {
        if (!Settings.canDrawOverlays(this)) {
            statusText = "需要悬浮窗权限,请在系统设置里允许后再回来点一次"
            requestOverlay(); return
        }
        requestRuntimePermissions()
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        if (perms.isNotEmpty()) runtimePerms.launch(perms.toTypedArray())
    }

    private fun requestOverlay() {
        if (Settings.canDrawOverlays(this)) return
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun startCaptureService(code: Int, data: Intent) {
        val svc = Intent(this, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_RESULT_CODE, code)
            .putExtra(CaptureService.EXTRA_DATA, data)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
        else startService(svc)
    }
}
