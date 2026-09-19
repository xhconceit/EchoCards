package com.orange.echocards.speech.vosk

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.vosk.Model
import java.io.File

/**
 * Vosk 模型的单例持有者。[Model] 昂贵且不可变，只能创建一次并跨线程复用。
 *
 * 首次访问时把 `assets/model-cn/` 递归拷到 `filesDir/model-cn/`（以 `uuid` 文件存在为已解压标志），
 * 之后直接复用；拷贝与加载都在 IO 线程，避免阻塞主线程。
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
        val target = File(context.filesDir, "model-cn")
        if (!target.resolve("uuid").exists()) {
            target.deleteRecursively()
            copyAssetDir(context, "model-cn", target)
        }
        return target
    }

    private fun copyAssetDir(context: Context, assetPath: String, target: File) {
        val assets = context.assets
        target.mkdirs()
        val children = assets.list(assetPath) ?: return
        for (name in children) {
            val childAsset = "$assetPath/$name"
            val childTarget = File(target, name)
            val grandchildren = assets.list(childAsset)
            if (grandchildren == null) {
                assets.open(childAsset).use { input ->
                    childTarget.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                childTarget.mkdirs()
                copyAssetDir(context, childAsset, childTarget)
            }
        }
    }
}
