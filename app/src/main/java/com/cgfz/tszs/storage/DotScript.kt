package com.cgfz.tszs.storage

import com.cgfz.tszs.imageproc.ArgbColor
import com.cgfz.tszs.imageproc.ColorPoint
import com.cgfz.tszs.imageproc.Rect2i
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * 点阵脚本:一次"多点找色 + 多点比色"的完整上下文。
 * 序列化:CBOR(比 JSON 紧凑 30-50%,支持演进)
 */
@Serializable
data class DotScript(
    val version: Int = 1,
    val name: String,
    val createdAt: Long,
    val threshold: Int = 4,
    /** 多点找色的基准色。null 表示该脚本只做比色。 */
    val baseColor: Int? = null,
    /** 多点找色的偏移(相对于基准命中点)。 */
    val findOffsets: List<Dot> = emptyList(),
    /** 多点比色(绝对坐标)。 */
    val comparePoints: List<Dot> = emptyList(),
    /** 搜索区域,null=全屏。 */
    val region: IntArray? = null,
) {
    @Serializable
    data class Dot(val x: Int, val y: Int, val argb: Int)
}

@OptIn(ExperimentalSerializationApi::class)
object DotScriptCodec {
    private val cbor = Cbor { encodeDefaults = false }

    fun encode(script: DotScript): ByteArray = cbor.encodeToByteArray(script)
    fun decode(bytes: ByteArray): DotScript = cbor.decodeFromByteArray<DotScript>(bytes)
}

/** 与 imageproc 模型互转 */
fun DotScript.Dot.toColorPoint(): ColorPoint = ColorPoint(x, y, ArgbColor(argb))
fun ColorPoint.toDot(): DotScript.Dot = DotScript.Dot(x, y, color.value)
fun DotScript.regionRect(): Rect2i? = region?.let { Rect2i(it[0], it[1], it[2], it[3]) }
fun Rect2i.toIntArray(): IntArray = intArrayOf(x, y, w, h)
