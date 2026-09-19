package com.orange.echocards.speech.vosk

import org.json.JSONObject

/**
 * 解析 Vosk 返回的 JSON：
 * - 部分结果：`{"partial":"..."}`
 * - 最终结果：`{"text":"..."}`
 */
object VoskResultParser {
    fun partial(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return runCatching { JSONObject(json).optString("partial").orEmpty() }.getOrDefault("")
    }

    fun finalText(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return runCatching { JSONObject(json).optString("text").orEmpty() }.getOrDefault("")
    }
}
