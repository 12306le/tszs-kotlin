package com.cgfz.tszs.imageproc

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * 图色核心算法。所有接口接受 Bitmap 或 Mat (RGBA 4 通道),内部尽量避免多余拷贝。
 *
 * 算法与原 JS API 对应:
 *   findMultiColors     -> findMultiColors
 *   detectsColor        -> detectColor
 *   findImage           -> findImage (matchTemplate)
 *   images.interval     -> binarize (inRange)
 *   images.resize       -> resize
 *   images.clip         -> clip
 *   images.pixel        -> pixelAt
 */
object ImageOps {

    // ========== 取像素 ==========

    /** 取 (x,y) 像素的 ARGB。Mat 要求 CV_8UC4 且按 RGBA 顺序(Utils.bitmapToMat 的默认)。 */
    fun pixelAt(mat: Mat, x: Int, y: Int): ArgbColor {
        val px = ByteArray(4)
        mat.get(y, x, px)
        val r = px[0].toInt() and 0xff
        val g = px[1].toInt() and 0xff
        val b = px[2].toInt() and 0xff
        val a = px[3].toInt() and 0xff
        return ArgbColor((a shl 24) or (r shl 16) or (g shl 8) or b)
    }

    // ========== 多点找色 ==========

    /**
     * 在 [image] 中找到基准色 [baseColor] 的位置,使得每个偏移 [offsets] 相对该位置的颜色
     * 都与期望颜色在每通道差 <= [threshold]。命中第一个就返回。
     *
     * 核心优化:
     *   1. 用 inRange 在 GPU 友好的向量指令下生成基准色候选 mask,比 JS 逐像素扫快 50-100 倍。
     *   2. 候选点数量通常远小于整图,再逐点校验偏移,整体仍是线性的。
     *
     * 若传入 [region] 则只在此矩形内搜索。
     */
    fun findMultiColors(
        image: Mat,
        baseColor: ArgbColor,
        offsets: List<ColorPoint>,
        threshold: Int = 4,
        region: Rect2i? = null,
    ): Point2i? {
        val roi: Mat = if (region != null) {
            val r = Rect(region.x, region.y, region.w, region.h)
            Mat(image, r)
        } else image

        val mask = Mat()
        Core.inRange(
            roi,
            colorToScalar(baseColor, -threshold),
            colorToScalar(baseColor, +threshold),
            mask
        )

        val candidates = MatOfPoint()
        Core.findNonZero(mask, candidates)
        mask.release()
        if (roi !== image) roi.release()

        val total = candidates.total().toInt()
        if (total == 0) { candidates.release(); return null }

        val pts = candidates.toArray()
        candidates.release()

        val baseOffX = region?.x ?: 0
        val baseOffY = region?.y ?: 0

        for (p in pts) {
            val bx = p.x.toInt() + baseOffX
            val by = p.y.toInt() + baseOffY
            if (checkOffsets(image, bx, by, offsets, threshold)) {
                return Point2i(bx, by)
            }
        }
        return null
    }

    /** 多点比色:所有 [points] 都满足容差返回 true,否则 false。 */
    fun detectsAllColors(image: Mat, points: List<ColorPoint>, threshold: Int = 4): Boolean {
        for (p in points) if (!detectColor(image, p.x, p.y, p.color, threshold)) return false
        return true
    }

    /** 返回命中点数,对应原 `多点比色` 的 n 计数 */
    fun detectsColorCount(image: Mat, points: List<ColorPoint>, threshold: Int = 4): Int {
        var n = 0
        for (p in points) if (detectColor(image, p.x, p.y, p.color, threshold)) n++
        return n
    }

    /** 单点容差比色 */
    fun detectColor(image: Mat, x: Int, y: Int, expect: ArgbColor, threshold: Int = 4): Boolean {
        if (x < 0 || y < 0 || x >= image.cols() || y >= image.rows()) return false
        val c = pixelAt(image, x, y)
        return max(
            max(diff(c.r, expect.r), diff(c.g, expect.g)),
            diff(c.b, expect.b)
        ) <= threshold
    }

    private fun checkOffsets(
        image: Mat, bx: Int, by: Int,
        offsets: List<ColorPoint>, threshold: Int
    ): Boolean {
        val w = image.cols(); val h = image.rows()
        for (o in offsets) {
            val xx = bx + o.x; val yy = by + o.y
            if (xx < 0 || yy < 0 || xx >= w || yy >= h) return false
            val c = pixelAt(image, xx, yy)
            if (max(max(diff(c.r, o.color.r), diff(c.g, o.color.g)), diff(c.b, o.color.b)) > threshold)
                return false
        }
        return true
    }

    // ========== 模板匹配(找图)==========

    /**
     * 在 [image] 中查找 [template],相似度 >= [threshold] 时返回最匹配的左上角坐标。
     * 对应原 findImage,使用 TM_CCOEFF_NORMED(效果稳定)。
     */
    fun findImage(
        image: Mat, template: Mat,
        threshold: Float = 0.9f,
        region: Rect2i? = null,
    ): Point2i? {
        val roi: Mat = if (region != null) Mat(image, Rect(region.x, region.y, region.w, region.h)) else image
        val result = Mat()
        Imgproc.matchTemplate(roi, template, result, Imgproc.TM_CCOEFF_NORMED)
        val mmr = Core.minMaxLoc(result)
        result.release()
        if (roi !== image) roi.release()
        return if (mmr.maxVal >= threshold) {
            val ox = region?.x ?: 0
            val oy = region?.y ?: 0
            Point2i(mmr.maxLoc.x.toInt() + ox, mmr.maxLoc.y.toInt() + oy)
        } else null
    }

    // ========== 二值化(颜色区间)==========

    /**
     * 对应 JS 的 `images.interval(img, color, about)`:以 [center] 为中心,
     * 每通道容差 [tolerance],在区间内的像素置白,其他置黑。结果为单通道 CV_8U。
     */
    fun binarize(image: Mat, center: ArgbColor, tolerance: Int): Mat {
        val dst = Mat()
        Core.inRange(
            image,
            colorToScalar(center, -tolerance),
            colorToScalar(center, +tolerance),
            dst
        )
        return dst
    }

    // ========== 基本几何操作 ==========

    fun resize(image: Mat, newW: Int, newH: Int): Mat {
        val dst = Mat()
        Imgproc.resize(image, dst, org.opencv.core.Size(newW.toDouble(), newH.toDouble()))
        return dst
    }

    fun clip(image: Mat, x: Int, y: Int, w: Int, h: Int): Mat =
        Mat(image, Rect(x, y, w, h)).clone()

    // ========== Bitmap <-> Mat ==========

    fun Bitmap.toMatRgba(): Mat = Mat(height, width, CvType.CV_8UC4).also { Utils.bitmapToMat(this, it) }

    fun Mat.toBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(cols(), rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(this, bmp)
        return bmp
    }

    // ========== 内部工具 ==========

    private fun colorToScalar(c: ArgbColor, delta: Int): Scalar {
        // RGBA 顺序(与 Utils.bitmapToMat 保持一致)
        return Scalar(
            clamp(c.r + delta).toDouble(),
            clamp(c.g + delta).toDouble(),
            clamp(c.b + delta).toDouble(),
            0.0,   // alpha 忽略,范围下限
        ).let {
            if (delta >= 0) Scalar(it.`val`[0], it.`val`[1], it.`val`[2], 255.0) else it
        }
    }

    private fun clamp(v: Int) = min(255, max(0, v))
    private fun diff(a: Int, b: Int) = if (a > b) a - b else b - a
}
