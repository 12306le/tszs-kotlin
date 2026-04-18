package com.cgfz.tszs.imageproc

/** ARGB 颜色。24 位 RGB + alpha。 */
@JvmInline
value class ArgbColor(val value: Int) {
    val a: Int get() = (value ushr 24) and 0xff
    val r: Int get() = (value ushr 16) and 0xff
    val g: Int get() = (value ushr 8) and 0xff
    val b: Int get() = value and 0xff

    /** 输出 "#AARRGGBB" 形式 */
    fun toHex(): String = "#%08x".format(value)

    companion object {
        /** 解析 "#AARRGGBB" / "#RRGGBB" / "AARRGGBB" */
        fun parse(hex: String): ArgbColor {
            val s = hex.removePrefix("#").removePrefix("0x")
            val v = when (s.length) {
                6 -> 0xff000000.toInt() or s.toLong(16).toInt()
                8 -> s.toLong(16).toInt()
                else -> throw IllegalArgumentException("bad color: $hex")
            }
            return ArgbColor(v)
        }
    }
}

/** 屏幕坐标 */
data class Point2i(val x: Int, val y: Int)

/** 多点找色/比色使用的点色单元 */
data class ColorPoint(val x: Int, val y: Int, val color: ArgbColor)

/** 矩形 */
data class Rect2i(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right get() = x + w
    val bottom get() = y + h
}
