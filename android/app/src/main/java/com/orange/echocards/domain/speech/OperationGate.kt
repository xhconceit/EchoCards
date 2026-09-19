package com.orange.echocards.domain.speech

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 操作门：只有当前 operationId 的事件才放行。
 *
 * 暂停、切卡、重读、切模式、退出和进入后台时先 [invalidate]，随后到达的旧回调一律被忽略，
 * 这是 docs/reference/speech-api.md 第 6 节和节点 1 验收里“旧回调不能影响新卡片”的落点。
 *
 * 同时保证同一个操作最多产生一次终止事件（completed / stopped / error）。
 * TTS 与识别回调来自系统线程，因此内部用原子引用。
 */
class OperationGate {

    private class State(val operationId: String) {
        var terminated = false
    }

    private val state = AtomicReference<State?>(null)
    private val ignoredCount = AtomicInteger(0)

    /** 当前有效的 operationId；没有进行中的操作时为 null。 */
    val activeOperationId: String?
        get() = state.get()?.operationId

    /** 被忽略的迟到事件数，用于探针页和测试观察。 */
    val ignored: Int
        get() = ignoredCount.get()

    /** 开始新操作，旧操作立即失效。 */
    fun begin(operationId: String) {
        state.set(State(operationId))
    }

    /** 使当前操作失效，返回被失效的 operationId。 */
    fun invalidate(): String? {
        val previous = state.getAndSet(null) ?: return null
        return previous.operationId
    }

    /** 普通事件：operationId 匹配且该操作尚未终止才放行。 */
    fun accept(operationId: String): Boolean {
        val current = state.get()
        if (current != null && current.operationId == operationId && !current.terminated) return true
        ignoredCount.incrementAndGet()
        return false
    }

    /** 终止事件：operationId 匹配且此前没有终止过才放行。 */
    fun acceptTerminal(operationId: String): Boolean {
        val current = state.get()
        if (current == null || current.operationId != operationId) {
            ignoredCount.incrementAndGet()
            return false
        }
        return if (current.terminated) {
            ignoredCount.incrementAndGet()
            false
        } else {
            current.terminated = true
            true
        }
    }
}
