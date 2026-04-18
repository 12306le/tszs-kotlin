package com.cgfz.tszs.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cgfz.tszs.capture.ProjectionPermissionRequester
import com.cgfz.tszs.service.CaptureService
import com.cgfz.tszs.service.OverlayService
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {

    private val projectionPerm = ProjectionPermissionRequester(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpenCVLoader.initLocal()
        projectionPerm.register()

        setContent {
            MaterialTheme {
                Scaffold { pad ->
                    var status by remember { mutableStateOf("未授权") }
                    Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
                        Text("图色助手 Pro (Kotlin + OpenCV)", style = MaterialTheme.typography.titleLarge)
                        Text("状态: $status")
                        Button(onClick = { requestOverlay() }) { Text("1. 申请悬浮窗权限") }
                        Button(onClick = {
                            projectionPerm.request { code, data ->
                                if (data != null) {
                                    startCaptureService(code, data)
                                    status = "截屏服务已启动"
                                }
                            }
                        }) { Text("2. 授权截屏并启动") }
                        Button(onClick = {
                            startService(Intent(this@MainActivity, OverlayService::class.java))
                            status = "悬浮窗已启动"
                        }) { Text("3. 启动悬浮窗") }
                    }
                }
            }
        }
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
