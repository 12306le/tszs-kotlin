package com.cgfz.tszs.overlay

import android.graphics.Bitmap
import com.cgfz.tszs.imageproc.ArgbColor

/**
 * 悬浮窗的全部状态。对应原 JS 的 pictureAttribute、imgList、colorList、nowColor、pattern。
 */
class OverlayState {

    enum class Mode { GET_COLOR, GET_IMG }

    var mode: Mode = Mode.GET_COLOR
    var rightPanelVisible: Boolean = false

    // 图片列表:最后一位是占位的 "+",其余是截图结果
    val imgList: MutableList<Bitmap?> = mutableListOf(null)
    var selectedIndex: Int = 0
    val currentImage: Bitmap? get() = imgList.getOrNull(selectedIndex)

    // 图片在画布里的变换:缩放比例 + 左上角像素坐标 + 偏移(反解平移用)
    var ratio: Float = 0.30f
    var imgTopX: Float = 0f
    var imgTopY: Float = 0f
    var deviationX: Float = 0f
    var deviationY: Float = 0f

    // 指针(取色模式用 focus; 取图模式用 (x1,y1)-(x2,y2) + focus)
    var pointerFocusX: Float = 0f
    var pointerFocusY: Float = 0f
    var pointerX1: Float = 0f
    var pointerY1: Float = 0f
    var pointerX2: Float = 0f
    var pointerY2: Float = 0f
    var pointerOpRadius: Float = 0f   // 判定圆角半径

    // 颜色记录 & 裁剪小图
    val colorList: MutableList<ColorPick> = mutableListOf()
    var smallImg: Bitmap? = null

    // 实时取色结果
    var nowX: Int = 0
    var nowY: Int = 0
    var nowX1: Int = 0
    var nowY1: Int = 0
    var nowX2: Int = 0
    var nowY2: Int = 0
    var nowColor: ArgbColor = ArgbColor(0xFF000000.toInt())

    // 系统提示(右上)
    var promptMsg: String? = null
    var promptExpiry: Long = 0L   // ms since boot

    data class ColorPick(val x: Int, val y: Int, val color: ArgbColor)

    /** 添加截屏;保证最后一项始终是 null(占位符 "+") */
    fun acceptCapture(bmp: Bitmap): Int {
        val sel = selectedIndex
        if (imgList[sel] == null) {
            // 占位符位置放新图,追加新占位
            imgList[sel] = bmp
            imgList.add(null)
        } else {
            imgList.recycleAt(sel)
            imgList[sel] = bmp
        }
        deviationX = 0f; deviationY = 0f
        return sel
    }

    fun deleteCurrent() {
        val sel = selectedIndex
        val img = imgList.getOrNull(sel) ?: return
        img.recycle()
        imgList.removeAt(sel)
        if (imgList.isEmpty() || imgList.last() != null) imgList.add(null)
        selectedIndex = sel.coerceAtMost(imgList.lastIndex).coerceAtLeast(0)
    }

    fun computeRatioFromSeek(progress: Int): Float {
        // 原 JS: 0.3 + (p*0.1)*(p*0.002) = 0.3 + p²/5000
        return (0.30 + progress.toDouble() * progress * 0.0002).toFloat()
    }

    /** 计算 canvas 坐标对应的原图坐标 */
    fun canvasToImage(cx: Float, cy: Float): Pair<Int, Int>? {
        val img = currentImage ?: return null
        val fx = (cx - imgTopX) / ratio
        val fy = (cy - imgTopY) / ratio
        val x = fx.toInt().coerceIn(0, img.width - 1)
        val y = fy.toInt().coerceIn(0, img.height - 1)
        return x to y
    }

    /** 重新计算 imgTopX/Y 使得 (deviationX, deviationY) 为中心 */
    fun applyZoom(focusCanvasX: Float, focusCanvasY: Float, newRatio: Float) {
        val img = currentImage ?: return
        ratio = newRatio
        imgTopX = focusCanvasX - (img.width / 2f + deviationX) * ratio
        imgTopY = focusCanvasY - (img.height / 2f + deviationY) * ratio
    }

    fun movePointer(nx: Float, ny: Float, canvasW: Float, canvasH: Float, edge: Float) {
        val w = pointerX2 - pointerX1
        val h = pointerY2 - pointerY1
        pointerX1 = when {
            nx > edge && nx + w < canvasW - edge -> nx
            nx + w < canvasW - edge              -> edge
            else                                 -> canvasW - edge - w
        }
        pointerY1 = when {
            ny > edge && ny + h < canvasH - edge -> ny
            ny + h < canvasH - edge              -> edge
            else                                 -> canvasH - edge - h
        }
        pointerX2 = pointerX1 + w
        pointerY2 = pointerY1 + h
        pointerFocusX = (pointerX1 + pointerX2) / 2
        pointerFocusY = (pointerY1 + pointerY2) / 2
    }

    fun resizePointerCorner(corner: Int, nx: Float, ny: Float, canvasW: Float, canvasH: Float, edge: Float) {
        val minSize = 100f
        if (corner == 2) {
            pointerX2 = when {
                nx < canvasW - edge && nx - pointerX1 > minSize -> nx
                nx - pointerX1 > minSize                        -> canvasW - edge
                else                                            -> pointerX1 + minSize
            }
            pointerY2 = when {
                ny < canvasH - edge && ny - pointerY1 > minSize -> ny
                ny - pointerY1 > minSize                        -> canvasH - edge
                else                                            -> pointerY1 + minSize
            }
        } else {
            pointerX1 = when {
                nx > edge && pointerX2 - nx > minSize -> nx
                pointerX2 - nx > minSize              -> edge
                else                                  -> pointerX2 - minSize
            }
            pointerY1 = when {
                ny > edge && pointerY2 - ny > minSize -> ny
                pointerY2 - ny > minSize              -> edge
                else                                  -> pointerY2 - minSize
            }
        }
        pointerFocusX = (pointerX1 + pointerX2) / 2
        pointerFocusY = (pointerY1 + pointerY2) / 2
    }

    fun setPrompt(msg: String, durationMs: Long = 2000L) {
        promptMsg = msg
        promptExpiry = System.currentTimeMillis() + durationMs
    }

    fun currentPrompt(): String? {
        val m = promptMsg ?: return null
        if (System.currentTimeMillis() > promptExpiry) {
            promptMsg = null; return null
        }
        return m
    }

    private fun MutableList<Bitmap?>.recycleAt(i: Int) {
        this[i]?.recycle()
    }
}
