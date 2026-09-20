package com.orange.echocards.speech.vosk

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.vosk.Model
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Vosk 模型的单例持有者。[Model] 昂贵且不可变，只能创建一次并跨线程复用。
 *
 * 首次访问时把 `assets/model-cn/` 递归拷到 `filesDir/model-cn/`，之后直接复用；
 * 拷贝与加载都在 IO 线程，避免阻塞主线程。
 */
object VoskModelProvider {
    private val mutex = Mutex()
    @Volatile private var model: Model? = null

    suspend fun get(context: Context): Model = mutex.withLock {
        model ?: withContext(Dispatchers.IO) {
            Model(ensureUnpacked(context).absolutePath).also { model = it }
        }
    }

    private fun ensureUnpacked(context: Context): File {
        val target = File(context.filesDir, MODEL_DIR)
        if (isUnpacked(target)) return target

        // 先解压到临时目录再改名，避免解压中途被杀进程后留下"看起来已完成"的半成品。
        val staging = File(context.filesDir, "$MODEL_DIR.tmp")
        staging.deleteRecursively()
        copyAssetDir(context, MODEL_DIR, staging)
        target.deleteRecursively()
        if (!staging.renameTo(target)) {
            staging.deleteRecursively()
            throw IOException("离线模型解压失败")
        }
        return target
    }

    /** 必须有真实的模型文件，且非空——只看目录存在会把解压失败的结果当成成功。 */
    private fun isUnpacked(dir: File): Boolean =
        dir.resolve("am/final.mdl").let { it.isFile && it.length() > 0 }

    private fun copyAssetDir(context: Context, assetPath: String, target: File) {
        val assets = context.assets
        target.mkdirs()
        val children = assets.list(assetPath) ?: return
        for (name in children) {
            val childAsset = "$assetPath/$name"
            val childTarget = File(target, name)
            // assets.list() 对文件路径返回空数组而非 null，不能用它判断是不是目录。
            try {
                assets.open(childAsset).use { input ->
                    childTarget.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: FileNotFoundException) {
                childTarget.mkdirs()
                copyAssetDir(context, childAsset, childTarget)
            }
        }
    }

    private const val MODEL_DIR = "model-cn"
}
