package com.cgfz.tszs.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity

/**
 * 用户授权 MediaProjection 的一次性请求封装。
 * 注意:mgr 必须 lazy,getSystemService 在 Activity onCreate 之前调用会抛 ISE。
 */
class ProjectionPermissionRequester(private val activity: ComponentActivity) {

    private val mgr: MediaProjectionManager by lazy {
        activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

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
