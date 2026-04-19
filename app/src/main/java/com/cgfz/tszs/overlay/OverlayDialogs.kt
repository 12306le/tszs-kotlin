package com.cgfz.tszs.overlay

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

object OverlayDialogs {

    fun setClipboard(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("tszs", text))
    }

    fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    /** 在悬浮窗 service 内弹 AlertDialog 必须设 window type,否则 BadTokenException */
    private fun prepare(dlg: AlertDialog) {
        val w = dlg.window ?: return
        w.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE
        )
    }

    fun single(ctx: Context, title: String, options: Array<String>, cb: (Int) -> Unit) {
        val dlg = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setItems(options) { _, which -> cb(which) }
            .create()
        prepare(dlg); dlg.show()
    }

    fun confirm(ctx: Context, title: String, msg: String?, cb: (Boolean) -> Unit) {
        val dlg = AlertDialog.Builder(ctx)
            .setTitle(title).setMessage(msg)
            .setPositiveButton("确定") { _, _ -> cb(true) }
            .setNegativeButton("取消") { _, _ -> cb(false) }
            .create()
        prepare(dlg); dlg.show()
    }

    fun input(ctx: Context, title: String, prefill: String, cb: (String?) -> Unit) {
        val et = EditText(ctx).apply { setText(prefill) }
        val dlg = AlertDialog.Builder(ctx)
            .setTitle(title).setView(et)
            .setPositiveButton("确定") { _, _ -> cb(et.text.toString()) }
            .setNegativeButton("取消") { _, _ -> cb(null) }
            .create()
        prepare(dlg); dlg.show()
    }

    /** 二值化对话框:颜色 + 容差 */
    fun binarize(ctx: Context, defaultColor: String, defaultAbout: String, cb: (Pair<String, String>?) -> Unit) {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val colorEt = EditText(ctx).apply {
            hint = "颜色值 (#rrggbb)"
            setText(defaultColor)
        }
        val aboutEt = EditText(ctx).apply {
            hint = "容差"
            setText(defaultAbout)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(colorEt); root.addView(aboutEt)
        val dlg = AlertDialog.Builder(ctx)
            .setTitle("图片二值化").setView(root)
            .setPositiveButton("确定") { _, _ ->
                cb(Pair(colorEt.text.toString(), aboutEt.text.toString()))
            }
            .setNegativeButton("取消") { _, _ -> cb(null) }
            .create()
        prepare(dlg); dlg.show()
    }

    /** 设置尺寸:宽 + 高 */
    fun resize(ctx: Context, defW: String, defH: String, cb: (Pair<Int, Int>?) -> Unit) {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val wEt = EditText(ctx).apply { hint = "宽 W"; setText(defW); inputType = InputType.TYPE_CLASS_NUMBER }
        val hEt = EditText(ctx).apply { hint = "高 H"; setText(defH); inputType = InputType.TYPE_CLASS_NUMBER }
        root.addView(wEt); root.addView(hEt)
        val dlg = AlertDialog.Builder(ctx)
            .setTitle("设置图片尺寸").setView(root)
            .setPositiveButton("确定") { _, _ ->
                val w = wEt.text.toString().toIntOrNull()
                val h = hEt.text.toString().toIntOrNull()
                cb(if (w != null && h != null) w to h else null)
            }
            .setNegativeButton("取消") { _, _ -> cb(null) }
            .create()
        prepare(dlg); dlg.show()
    }
}
