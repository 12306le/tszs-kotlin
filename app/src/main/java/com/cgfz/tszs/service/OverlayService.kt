package com.cgfz.tszs.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cgfz.tszs.R
import com.cgfz.tszs.imageproc.ArgbColor
import com.cgfz.tszs.imageproc.ColorPoint
import com.cgfz.tszs.imageproc.ImageOps
import com.cgfz.tszs.imageproc.ImageOps.toMatRgba
import com.cgfz.tszs.overlay.ImageCanvasView
import com.cgfz.tszs.overlay.OverlayDialogs
import com.cgfz.tszs.overlay.OverlayState
import com.cgfz.tszs.storage.DotScript
import com.cgfz.tszs.storage.DotScriptStore
import com.cgfz.tszs.storage.toDot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 悬浮窗主服务:
 *   onCreate 挂一个"小圆点 handle",点击 → 替换为主面板
 *   主面板内各按钮负责调用动作。画布交互由 ImageCanvasView 自包含。
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var handleView: View? = null
    private var panelView: View? = null
    private var expanded = false

    private val state = OverlayState()
    private var canvas: ImageCanvasView? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            canvas?.invalidate()
            main.postDelayed(this, 100L)
        }
    }

    // 颜色记录适配器
    private lateinit var colorAdapter: ColorAdapter
    private lateinit var imgSlotLayout: LinearLayout
    private lateinit var smallImgView: ImageView
    private lateinit var ratioText: TextView
    private lateinit var colorRecordContainer: View
    private lateinit var smallImgContainer: View
    private lateinit var rightPanel: View
    private lateinit var modeButton: ImageView
    private lateinit var zoomBar: SeekBar
    private lateinit var store: DotScriptStore

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        store = DotScriptStore(this)
        showHandle()
        main.post(ticker)
    }

    // ================ 悬浮窗展开/收起 ================
    private fun showHandle() {
        val v = LayoutInflater.from(this).inflate(R.layout.overlay_handle, null, false)
        val lp = makeLP(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0; lp.y = 200
        bindDragAndClick(v, lp, onClick = { swapToPanel(lp.x, lp.y) })
        wm.addView(v, lp)
        handleView = v
        expanded = false
    }

    private fun swapToPanel(x: Int, y: Int) {
        handleView?.let { wm.removeView(it); handleView = null }

        val v = LayoutInflater.from(this).inflate(R.layout.overlay_main, null, false)
        panelView = v
        canvas = v.findViewById(R.id.canvas)
        canvas!!.state = state

        // 画布尺寸:占屏幕宽 95%,宽:高 = 宽度 * 0.9
        val dm = resources.displayMetrics
        val pw = (dm.widthPixels * 0.96).toInt()
        val ph = (dm.widthPixels * 0.95).toInt()

        val lp = makeLP(pw, ph)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = x.coerceAtMost(dm.widthPixels - pw).coerceAtLeast(0)
        lp.y = y.coerceAtMost(dm.heightPixels - ph).coerceAtLeast(0)

        bindPanel(v, lp)
        wm.addView(v, lp)
        expanded = true
    }

    private fun swapToHandle() {
        panelView?.let { wm.removeView(it); panelView = null }
        canvas = null
        showHandle()
    }

    // ================ 主面板接线 ================
    private fun bindPanel(v: View, lp: WindowManager.LayoutParams) {
        // 顶部 7 功能
        v.findViewById<ImageView>(R.id.act_upload).setOnClickListener { onUpload() }
        v.findViewById<ImageView>(R.id.act_download).setOnClickListener { onDownloadFromClipboard() }
        v.findViewById<ImageView>(R.id.act_binarize).setOnClickListener { onBinarize() }
        v.findViewById<ImageView>(R.id.act_resize).setOnClickListener { onResize() }
        v.findViewById<ImageView>(R.id.act_capture).setOnClickListener { onCapture() }
        v.findViewById<ImageView>(R.id.act_remove).setOnClickListener { onRemove() }
        v.findViewById<ImageView>(R.id.act_exit).setOnClickListener { stopSelf() }

        // 小圆点(左上)拖动 + 单击收起
        bindDragAndClick(v.findViewById(R.id.window_operate), lp, onClick = {
            swapToHandle()
        }, allowParentMove = true, parentView = v)

        // 侧边 6 按钮
        modeButton = v.findViewById(R.id.opt_mode)
        v.findViewById<ImageView>(R.id.opt_toggle_panel).setOnClickListener { onTogglePanel() }
        modeButton.setOnClickListener { onSwitchMode() }
        v.findViewById<ImageView>(R.id.opt_pick).setOnClickListener { onPickOrCrop() }
        v.findViewById<ImageView>(R.id.opt_test).setOnClickListener { onTest() }
        v.findViewById<ImageView>(R.id.opt_save).setOnClickListener { onSave() }
        v.findViewById<ImageView>(R.id.opt_copy).setOnClickListener { onCopy() }

        // 缩放条
        zoomBar = v.findViewById(R.id.zoom_bar)
        ratioText = v.findViewById(R.id.ratio_text)
        zoomBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val r = state.computeRatioFromSeek(p)
                ratioText.text = "%.2fX".format(r)
                state.currentImage?.let {
                    state.applyZoom(canvas!!.width / 2f, canvas!!.height / 2f, r)
                    canvas?.invalidate()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // 图片槽位
        imgSlotLayout = v.findViewById(R.id.img_slot_list)
        refreshSlots()

        // 颜色记录
        val rv = v.findViewById<RecyclerView>(R.id.color_list)
        rv.layoutManager = LinearLayoutManager(this)
        colorAdapter = ColorAdapter(state.colorList,
            onCopyClick = { idx ->
                val c = state.colorList[idx]
                OverlayDialogs.setClipboard(this, "${c.x},${c.y},${c.color.toHex()}")
                state.setPrompt("已复制 ${c.x},${c.y}")
            },
            onDeleteClick = { idx ->
                state.colorList.removeAt(idx)
                colorAdapter.notifyDataSetChanged()
            })
        rv.adapter = colorAdapter

        v.findViewById<View>(R.id.btn_clear_colors).setOnClickListener {
            state.colorList.clear(); colorAdapter.notifyDataSetChanged()
        }

        rightPanel = v.findViewById(R.id.right_panel)
        colorRecordContainer = v.findViewById(R.id.color_record)
        smallImgContainer = v.findViewById(R.id.small_img_record)
        smallImgView = v.findViewById(R.id.small_img)

        // 画布回调:点击左上信息框 → 复制坐标
        canvas!!.listener = object : ImageCanvasView.Listener {
            override fun onInfoBoxClicked() {
                val str = if (state.mode == OverlayState.Mode.GET_COLOR)
                    "${state.nowX},${state.nowY},${state.nowColor.toHex()}"
                else "${state.nowX1},${state.nowY1},${state.nowX2},${state.nowY2}"
                OverlayDialogs.setClipboard(this@OverlayService, str)
                state.setPrompt("已复制: $str")
            }
        }

        applyMode() // 初始化模式 UI
    }

    private fun bindDragAndClick(
        handle: View, lp: WindowManager.LayoutParams,
        onClick: () -> Unit,
        allowParentMove: Boolean = false,
        parentView: View? = null,
    ) {
        val touchSlop = 20f
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var swiped = false
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y; swiped = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!swiped && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) swiped = true
                    if (swiped) {
                        lp.x = (startX + dx).toInt()
                        lp.y = (startY + dy).toInt()
                        val moveTarget = parentView ?: handle
                        runCatching { wm.updateViewLayout(moveTarget, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> if (!swiped) onClick()
            }
            true
        }
    }

    private fun makeLP(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )

    // ================ 动作 ================
    private fun onCapture() {
        val capturer = CaptureService.instance?.capturer
        if (capturer == null) {
            state.setPrompt("截屏服务未就绪,先在主界面授权")
            toast("截屏服务未就绪,请先在主界面授权"); return
        }
        // 隐藏面板截图 → 还原
        panelView?.visibility = View.INVISIBLE
        scope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) {
                    kotlinx.coroutines.delay(120)  // 等面板真正消失
                    capturer.captureBitmap().copy(Bitmap.Config.ARGB_8888, true)
                }
                state.acceptCapture(bmp)
                // 初始 ratio 0.3 居中
                canvas?.let {
                    state.applyZoom(it.width / 2f, it.height / 2f, 0.30f)
                    zoomBar.progress = 0
                    ratioText.text = "0.30X"
                }
                refreshSlots()
                state.setPrompt("截屏完成")
            } catch (t: Throwable) {
                state.setPrompt("截屏失败:${t.message}")
            } finally {
                panelView?.visibility = View.VISIBLE
                canvas?.invalidate()
            }
        }
    }

    private fun onRemove() {
        if (state.currentImage == null) { state.setPrompt("当前无图片"); return }
        state.deleteCurrent()
        refreshSlots(); canvas?.invalidate()
    }

    private fun onBinarize() {
        val img = state.currentImage ?: run { state.setPrompt("请先截图"); return }
        OverlayDialogs.binarize(this, state.nowColor.toHex(), "8") { r ->
            r ?: return@binarize
            scope.launch(Dispatchers.Default) {
                try {
                    val mat = img.toMatRgba()
                    val center = ArgbColor.parse(r.first)
                    val tol = r.second.toIntOrNull() ?: 8
                    val dst = ImageOps.binarize(mat, center, tol)
                    val out = Bitmap.createBitmap(dst.cols(), dst.rows(), Bitmap.Config.ARGB_8888)
                    org.opencv.android.Utils.matToBitmap(dst, out)
                    mat.release(); dst.release()
                    withContext(Dispatchers.Main) {
                        state.acceptCapture(out); refreshSlots()
                        state.setPrompt("二值化完成"); canvas?.invalidate()
                    }
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) { state.setPrompt("二值化失败:${t.message}") }
                }
            }
        }
    }

    private fun onResize() {
        val img = state.currentImage ?: run { state.setPrompt("请先截图"); return }
        OverlayDialogs.resize(this, "720", "1280") { pair ->
            pair ?: return@resize
            val (w, h) = pair
            scope.launch(Dispatchers.Default) {
                val mat = img.toMatRgba()
                val dst = ImageOps.resize(mat, w, h)
                val out = Bitmap.createBitmap(dst.cols(), dst.rows(), Bitmap.Config.ARGB_8888)
                org.opencv.android.Utils.matToBitmap(dst, out)
                mat.release(); dst.release()
                withContext(Dispatchers.Main) {
                    state.acceptCapture(out); refreshSlots()
                    state.setPrompt("尺寸已改为 ${w}×${h}"); canvas?.invalidate()
                }
            }
        }
    }

    private fun onUpload() { state.setPrompt("云端上传未实现,此版本占位") }
    private fun onDownloadFromClipboard() {
        // 从剪贴板 base64 加载一张图
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: run {
            state.setPrompt("剪贴板为空"); return
        }
        try {
            val bytes = android.util.Base64.decode(text, android.util.Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) {
                state.acceptCapture(bmp); refreshSlots()
                state.setPrompt("从剪贴板读取图片成功"); canvas?.invalidate()
            } else state.setPrompt("剪贴板不是有效图片 base64")
        } catch (t: Throwable) { state.setPrompt("解码失败:${t.message}") }
    }

    private fun onTogglePanel() {
        state.rightPanelVisible = !state.rightPanelVisible
        rightPanel.visibility = if (state.rightPanelVisible) View.VISIBLE else View.GONE
    }
    private fun onSwitchMode() {
        state.mode = if (state.mode == OverlayState.Mode.GET_COLOR) OverlayState.Mode.GET_IMG else OverlayState.Mode.GET_COLOR
        applyMode()
    }
    private fun applyMode() {
        if (state.mode == OverlayState.Mode.GET_COLOR) {
            state.pointerOpRadius = 0f
            smallImgContainer.visibility = View.GONE
            colorRecordContainer.visibility = View.VISIBLE
            modeButton.setImageResource(R.drawable.ic_colorize)
            state.setPrompt("切换到取点色模式")
        } else {
            state.pointerOpRadius = 40f
            colorRecordContainer.visibility = View.GONE
            smallImgContainer.visibility = View.VISIBLE
            modeButton.setImageResource(R.drawable.ic_crop_free)
            state.setPrompt("切换到取小图模式")
        }
        canvas?.invalidate()
    }

    private fun onPickOrCrop() {
        val img = state.currentImage ?: run { state.setPrompt("请先截图"); return }
        if (state.mode == OverlayState.Mode.GET_COLOR) {
            state.colorList.add(OverlayState.ColorPick(state.nowX, state.nowY, state.nowColor))
            colorAdapter.notifyItemInserted(state.colorList.size - 1)
            state.setPrompt("取色成功")
        } else {
            // 裁剪小图
            val x = state.nowX1; val y = state.nowY1
            val w = state.nowX2 - state.nowX1; val h = state.nowY2 - state.nowY1
            if (w <= 0 || h <= 0) { state.setPrompt("框范围太小"); return }
            try {
                val clipped = Bitmap.createBitmap(img, x, y, w, h)
                state.smallImg?.recycle()
                state.smallImg = clipped
                smallImgView.setImageBitmap(clipped)
                state.setPrompt("裁剪成功 ${w}×${h}")
            } catch (t: Throwable) { state.setPrompt("裁剪失败:${t.message}") }
        }
    }

    private fun onTest() {
        val img = state.currentImage ?: run { state.setPrompt("请先截图"); return }
        if (state.mode == OverlayState.Mode.GET_COLOR) {
            OverlayDialogs.single(this, "点色测试", arrayOf("多点找色", "多点比色")) { idx ->
                if (state.colorList.size < 2) { state.setPrompt("至少 2 个点"); return@single }
                scope.launch(Dispatchers.Default) {
                    val mat = img.toMatRgba()
                    val t0 = System.currentTimeMillis()
                    val msg: String = if (idx == 0) {
                        val base = state.colorList[0]
                        val offsets = state.colorList.drop(1).map {
                            ColorPoint(it.x - base.x, it.y - base.y, it.color)
                        }
                        val r = ImageOps.findMultiColors(mat, base.color, offsets, 4)
                        val ms = System.currentTimeMillis() - t0
                        if (r == null) "多点找色失败" else {
                            withContext(Dispatchers.Main) { locate(r.x, r.y) }
                            "多点找色:(${r.x},${r.y}) ${ms}ms"
                        }
                    } else {
                        val pts = state.colorList.map { ColorPoint(it.x, it.y, it.color) }
                        val n = ImageOps.detectsColorCount(mat, pts, 4)
                        "匹配 $n/${pts.size}"
                    }
                    mat.release()
                    withContext(Dispatchers.Main) { state.setPrompt(msg); canvas?.invalidate() }
                }
            }
        } else {
            val small = state.smallImg ?: run { state.setPrompt("请先裁剪小图"); return }
            scope.launch(Dispatchers.Default) {
                val mat = img.toMatRgba()
                val tmpl = small.toMatRgba()
                val t0 = System.currentTimeMillis()
                val r = ImageOps.findImage(mat, tmpl, 0.9f)
                val ms = System.currentTimeMillis() - t0
                mat.release(); tmpl.release()
                withContext(Dispatchers.Main) {
                    if (r == null) state.setPrompt("没找到图片(${ms}ms)")
                    else { locate(r.x, r.y); state.setPrompt("找到图片:(${r.x},${r.y}) ${ms}ms") }
                    canvas?.invalidate()
                }
            }
        }
    }

    private fun locate(imgX: Int, imgY: Int) {
        val img = state.currentImage ?: return
        state.deviationX = imgX + 0.5f - img.width / 2f
        state.deviationY = imgY + 0.5f - img.height / 2f
        state.applyZoom(canvas!!.width / 2f, canvas!!.height / 2f, state.ratio)
    }

    private fun onSave() {
        if (state.mode == OverlayState.Mode.GET_COLOR) {
            if (state.colorList.size < 2) { state.setPrompt("至少 2 个点"); return }
            OverlayDialogs.input(this, "点阵名称", "点阵1") { name ->
                name?.takeIf { it.isNotBlank() }?.let {
                    val base = state.colorList[0]
                    val find = state.colorList.drop(1).map {
                        DotScript.Dot(it.x - base.x, it.y - base.y, it.color.value)
                    }
                    val cmp = state.colorList.map { DotScript.Dot(it.x, it.y, it.color.value) }
                    val script = DotScript(
                        name = it, createdAt = System.currentTimeMillis(),
                        baseColor = base.color.value, findOffsets = find, comparePoints = cmp
                    )
                    store.save(script)
                    state.setPrompt("已保存点阵: $it")
                }
            }
        } else {
            val small = state.smallImg ?: run { state.setPrompt("无小图"); return }
            OverlayDialogs.input(this, "图片名称", "图片1") { name ->
                name?.takeIf { it.isNotBlank() }?.let {
                    val dir = File(filesDir, "images").apply { mkdirs() }
                    val f = File(dir, "$it.png")
                    FileOutputStream(f).use { os -> small.compress(Bitmap.CompressFormat.PNG, 100, os) }
                    state.setPrompt("已保存: ${f.absolutePath}")
                }
            }
        }
    }

    private fun onCopy() {
        if (state.mode == OverlayState.Mode.GET_COLOR) {
            if (state.colorList.size < 2) { state.setPrompt("至少 2 个点"); return }
            OverlayDialogs.single(this, "复制点色数组", arrayOf("多点找色 JSON", "多点比色 JSON")) { i ->
                val text = if (i == 0) {
                    val b = state.colorList[0]
                    val offs = state.colorList.drop(1).map { "[${it.x - b.x},${it.y - b.y},\"${it.color.toHex()}\"]" }
                    "[\"${b.color.toHex()}\",[${offs.joinToString(",")}]]"
                } else {
                    state.colorList.joinToString(",", "[", "]") { "[${it.x},${it.y},\"${it.color.toHex()}\"]" }
                }
                OverlayDialogs.setClipboard(this, text)
                state.setPrompt("已复制点色数组")
            }
        } else {
            val small = state.smallImg ?: run { state.setPrompt("无小图"); return }
            OverlayDialogs.confirm(this, "复制图片 base64?", null) { ok ->
                if (!ok) return@confirm
                val baos = java.io.ByteArrayOutputStream()
                small.compress(Bitmap.CompressFormat.PNG, 100, baos)
                val b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                OverlayDialogs.setClipboard(this, b64)
                state.setPrompt("已复制图片 base64(${b64.length} 字节)")
            }
        }
    }

    // ================ 图片槽位 ================
    private fun refreshSlots() {
        imgSlotLayout.removeAllViews()
        val inflater = LayoutInflater.from(this)
        state.imgList.forEachIndexed { i, img ->
            val v = inflater.inflate(R.layout.item_img_slot, imgSlotLayout, false) as TextView
            v.text = if (img == null) "+" else (i + 1).toString()
            v.isSelected = (i == state.selectedIndex)
            v.isActivated = (img != null)
            v.setOnClickListener {
                state.selectedIndex = i
                state.deviationX = 0f; state.deviationY = 0f
                state.currentImage?.let {
                    state.applyZoom(canvas!!.width / 2f, canvas!!.height / 2f, 0.30f)
                    zoomBar.progress = 0
                }
                refreshSlots(); canvas?.invalidate()
            }
            imgSlotLayout.addView(v)
        }
    }

    // ================ 颜色记录 adapter ================
    private class ColorAdapter(
        val list: List<OverlayState.ColorPick>,
        val onCopyClick: (Int) -> Unit,
        val onDeleteClick: (Int) -> Unit,
    ) : RecyclerView.Adapter<ColorVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ColorVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_color_record, parent, false)
            return ColorVH(v)
        }
        override fun getItemCount() = list.size
        override fun onBindViewHolder(holder: ColorVH, position: Int) {
            val c = list[position]
            holder.x.text = c.x.toString()
            holder.y.text = c.y.toString()
            holder.color.text = c.color.toHex()
            holder.preview.setBackgroundColor(c.color.value)
            holder.del.setOnClickListener { onDeleteClick(holder.adapterPosition) }
            holder.itemView.setOnClickListener { onCopyClick(holder.adapterPosition) }
        }
    }

    private class ColorVH(v: View) : RecyclerView.ViewHolder(v) {
        val del: ImageView = v.findViewById(R.id.btn_del)
        val x: TextView = v.findViewById(R.id.tv_x)
        val y: TextView = v.findViewById(R.id.tv_y)
        val color: TextView = v.findViewById(R.id.tv_color)
        val preview: View = v.findViewById(R.id.color_preview)
    }

    private fun toast(m: String) = OverlayDialogs.toast(this, m)

    // ================ 前台服务 ================
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

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        scope.cancel()
        handleView?.let { runCatching { wm.removeView(it) } }
        panelView?.let { runCatching { wm.removeView(it) } }
        state.imgList.filterNotNull().forEach { it.recycle() }
        state.smallImg?.recycle()
        super.onDestroy()
    }
}
