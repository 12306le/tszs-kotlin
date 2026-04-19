package com.cgfz.tszs.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.cgfz.tszs.imageproc.ArgbColor
import com.cgfz.tszs.imageproc.ImageOps
import com.cgfz.tszs.imageproc.ImageOps.toMatRgba
import kotlin.math.abs

/**
 * 核心画布 View:
 *   - 绘制图片(缩放+平移)
 *   - 绘制指针(取色模式圆点 / 取图模式矩形)
 *   - 左上信息框(坐标+颜色)
 *   - 右上系统提示框
 *   - 手势:拖图片、拖指针、拖框角、点信息框 → 回调
 */
class ImageCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    interface Listener {
        fun onInfoBoxClicked()
        fun onStateChanged() = Unit
    }

    var state: OverlayState = OverlayState()
    var listener: Listener? = null

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(255, 255, 230, 130)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        color = Color.WHITE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
    }

    // ================ 布局 / 初始化 ================
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 首次布局好之后居中指针焦点和默认框
        if (oldw == 0 && oldh == 0) {
            state.pointerFocusX = w / 2f
            state.pointerFocusY = h / 2f
            state.pointerX1 = w / 2f - 100
            state.pointerY1 = h / 2f - 100
            state.pointerX2 = w / 2f + 100
            state.pointerY2 = h / 2f + 100
        }
    }

    // ================ 绘制 ================
    override fun onDraw(canvas: Canvas) {
        canvas.drawARGB(255, 250, 200, 170)   // 桃色背景
        val img = state.currentImage
        if (img == null) {
            drawSystemPrompt(canvas, "请先截图才能执行图色操作")
            return
        }

        // 实时取色
        updateNowColor(img)

        // 动态画笔色(对比度)
        val c = state.nowColor
        strokePaint.setARGB(255,
            if (c.r > 127) 0 else 255,
            if (c.g > 127) 0 else 255,
            if (c.b > 127) 0 else 255)
        textPaint.setARGB(255,
            if (c.r > 127) 0 else 255,
            if (c.g > 127) 0 else 255,
            if (c.b > 127) 0 else 255)
        fillPaint.setARGB(255, c.r, c.g, c.b)

        // 绘制图片
        val mtx = Matrix().apply {
            postScale(state.ratio, state.ratio)
            postTranslate(state.imgTopX, state.imgTopY)
        }
        canvas.drawBitmap(img, mtx, null)

        // 信息框 / 指针
        if (state.mode == OverlayState.Mode.GET_COLOR) {
            drawInfoBoxColor(canvas)
            canvas.drawCircle(state.pointerFocusX, state.pointerFocusY, 10f, strokePaint)
        } else {
            drawInfoBoxRegion(canvas)
            canvas.drawRect(state.pointerX1, state.pointerY1, state.pointerX2, state.pointerY2, strokePaint)
            canvas.drawCircle(state.pointerFocusX, state.pointerFocusY, 10f, strokePaint)
        }

        // 系统提示
        state.currentPrompt()?.let { drawSystemPrompt(canvas, it) }
    }

    private fun drawSystemPrompt(canvas: Canvas, msg: String) {
        val w = 360f; val h = 44f
        val x = width - w - 8f; val y = 8f
        val r = Rect(x.toInt(), y.toInt(), (x + w).toInt(), (y + h).toInt())
        fillPaint.color = Color.BLACK
        canvas.drawRect(r, fillPaint)
        canvas.drawRect(r, strokePaint)
        textPaint.color = Color.WHITE
        canvas.drawText(msg, x + 12f, y + 30f, textPaint)
    }

    private fun drawInfoBoxColor(canvas: Canvas) {
        val w = 360f; val h = 44f; val x = 8f; val y = 8f
        val box = Rect(x.toInt(), y.toInt(), (x + w).toInt(), (y + h).toInt())
        fillPaint.setARGB(255, state.nowColor.r, state.nowColor.g, state.nowColor.b)
        canvas.drawRect(box, fillPaint)
        canvas.drawRect(box, strokePaint)
        canvas.drawText("X:${state.nowX}", x + 8f, y + 30f, textPaint)
        canvas.drawText("Y:${state.nowY}", x + 100f, y + 30f, textPaint)
        canvas.drawText(state.nowColor.toHex(), x + 200f, y + 30f, textPaint)
    }

    private fun drawInfoBoxRegion(canvas: Canvas) {
        val w = 500f; val h = 44f; val x = 8f; val y = 8f
        val box = Rect(x.toInt(), y.toInt(), (x + w).toInt(), (y + h).toInt())
        fillPaint.setARGB(255, state.nowColor.r, state.nowColor.g, state.nowColor.b)
        canvas.drawRect(box, fillPaint)
        canvas.drawRect(box, strokePaint)
        canvas.drawText("X1:${state.nowX1}", x + 8f, y + 30f, textPaint)
        canvas.drawText("Y1:${state.nowY1}", x + 130f, y + 30f, textPaint)
        canvas.drawText("X2:${state.nowX2}", x + 250f, y + 30f, textPaint)
        canvas.drawText("Y2:${state.nowY2}", x + 370f, y + 30f, textPaint)
    }

    private fun updateNowColor(img: android.graphics.Bitmap) {
        val fx = ((state.pointerFocusX - state.imgTopX) / state.ratio).toInt()
            .coerceIn(0, img.width - 1)
        val fy = ((state.pointerFocusY - state.imgTopY) / state.ratio).toInt()
            .coerceIn(0, img.height - 1)
        state.nowX = fx; state.nowY = fy
        val argb = img.getPixel(fx, fy)
        state.nowColor = ArgbColor(argb)

        val (x1, y1) = pointerToImg(state.pointerX1, state.pointerY1, img) ?: (0 to 0)
        val (x2, y2) = pointerToImg(state.pointerX2, state.pointerY2, img) ?: (0 to 0)
        state.nowX1 = x1; state.nowY1 = y1
        state.nowX2 = x2; state.nowY2 = y2
    }

    private fun pointerToImg(cx: Float, cy: Float, img: android.graphics.Bitmap): Pair<Int, Int>? {
        val x = ((cx - state.imgTopX) / state.ratio).toInt().coerceIn(0, img.width - 1)
        val y = ((cy - state.imgTopY) / state.ratio).toInt().coerceIn(0, img.height - 1)
        return x to y
    }

    // ================ 手势 ================
    private enum class Aim { NONE, POINTER_XY1, POINTER_XY2, POINTER_FOCUS, INFO_BOX, IMG }

    private var downX = 0f; private var downY = 0f
    private var origImgTopX = 0f; private var origImgTopY = 0f
    private var origDevX = 0f; private var origDevY = 0f
    private var origP1x = 0f; private var origP1y = 0f
    private var origP2x = 0f; private var origP2y = 0f
    private var origFx = 0f; private var origFy = 0f
    private var aim = Aim.NONE

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY
                origImgTopX = state.imgTopX; origImgTopY = state.imgTopY
                origDevX = state.deviationX; origDevY = state.deviationY
                origP1x = state.pointerX1; origP1y = state.pointerY1
                origP2x = state.pointerX2; origP2y = state.pointerY2
                origFx = state.pointerFocusX; origFy = state.pointerFocusY
                aim = detectAim(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                val img = state.currentImage ?: return true
                val ratio = state.ratio
                val edge = 60f
                when (aim) {
                    Aim.POINTER_XY1 -> {
                        state.resizePointerCorner(1, origP1x + dx, origP1y + dy, width.toFloat(), height.toFloat(), edge)
                        state.deviationX = origDevX - (origFx - state.pointerFocusX) / ratio
                        state.deviationY = origDevY - (origFy - state.pointerFocusY) / ratio
                    }
                    Aim.POINTER_XY2 -> {
                        state.resizePointerCorner(2, origP2x + dx, origP2y + dy, width.toFloat(), height.toFloat(), edge)
                        state.deviationX = origDevX - (origFx - state.pointerFocusX) / ratio
                        state.deviationY = origDevY - (origFy - state.pointerFocusY) / ratio
                    }
                    Aim.POINTER_FOCUS -> {
                        state.movePointer(origP1x + dx, origP1y + dy, width.toFloat(), height.toFloat(), edge)
                        state.deviationX = origDevX - (origFx - state.pointerFocusX) / ratio
                        state.deviationY = origDevY - (origFy - state.pointerFocusY) / ratio
                    }
                    Aim.INFO_BOX -> {
                        if (abs(dx) > 10 || abs(dy) > 10) aim = Aim.IMG
                    }
                    Aim.IMG -> {
                        state.imgTopX = origImgTopX + dx
                        state.imgTopY = origImgTopY + dy
                        state.deviationX = origDevX - dx / ratio
                        state.deviationY = origDevY - dy / ratio
                    }
                    else -> {}
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (aim == Aim.INFO_BOX && abs(event.rawX - downX) < 10 && abs(event.rawY - downY) < 10) {
                    listener?.onInfoBoxClicked()
                }
                listener?.onStateChanged()
                return true
            }
        }
        return true
    }

    private fun detectAim(x: Float, y: Float): Aim {
        val r = state.pointerOpRadius.takeIf { it > 0 } ?: 40f
        fun near(px: Float, py: Float) = x in (px - r)..(px + r) && y in (py - r)..(py + r)
        if (state.mode == OverlayState.Mode.GET_IMG) {
            if (near(state.pointerX2, state.pointerY2)) return Aim.POINTER_XY2
            if (near(state.pointerX1, state.pointerY1)) return Aim.POINTER_XY1
        }
        if (near(state.pointerFocusX, state.pointerFocusY)) return Aim.POINTER_FOCUS
        // 左上信息框区域
        val infoW = if (state.mode == OverlayState.Mode.GET_COLOR) 360f else 500f
        if (x in 8f..(8f + infoW) && y in 8f..52f && state.currentImage != null) return Aim.INFO_BOX
        return if (state.currentImage != null) Aim.IMG else Aim.NONE
    }
}
