package com.cgfz.tszs.storage

import android.content.Context
import java.io.File

/**
 * 点阵脚本文件管理。文件放在 app 内部 files/scripts/ 下,文件名是 name.dot。
 * 调用方 UI 层负责重名检查/UI 列表展示。
 */
class DotScriptStore(context: Context) {

    private val dir: File = File(context.filesDir, "scripts").apply { if (!exists()) mkdirs() }

    fun list(): List<String> = dir.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".dot") }
        ?.map { it.nameWithoutExtension }
        ?.sorted()
        ?: emptyList()

    fun save(script: DotScript) {
        File(dir, "${script.name}.dot").writeBytes(DotScriptCodec.encode(script))
    }

    fun load(name: String): DotScript? {
        val f = File(dir, "$name.dot")
        return if (f.exists()) DotScriptCodec.decode(f.readBytes()) else null
    }

    fun delete(name: String): Boolean = File(dir, "$name.dot").delete()
}
