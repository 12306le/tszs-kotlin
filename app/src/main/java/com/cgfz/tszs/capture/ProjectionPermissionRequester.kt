package com.cgfz.tszs.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity

/**
 * 用户授权 MediaProjection 的一次性请求封装。
 * 使用方式:Activity 里调用 register(...),然后 request(),
 * 授权成功后会把 resultCode + Intent 交给回调。
 */
class ProjectionPermissionRequester(private val activity: ComponentActivity) {

    private val mgr: MediaProjectionManager = activity.getSystemService(
        Activity.MEDIA_PROJECTION_SERVICE
    ) as MediaProjectionManager

    private var onResult: ((Int, Intent?) -> Unit)? = null
    private lateinit var launcher: ActivityResultLauncher<Intent>

    fun register() {
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            onResult?.invoke(result.resultCode, result.data)
            onResult = null
        }
    }

    fun request(cb: (resultCode: Int, data: Intent?) -> Unit) {
        onResult = cb
        launcher.launch(mgr.createScreenCaptureIntent())
    }
}
