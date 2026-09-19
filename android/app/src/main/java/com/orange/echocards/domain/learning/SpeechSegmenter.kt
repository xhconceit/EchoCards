package com.orange.echocards.domain.learning

/**
 * 手动朗读的分段规则，见 docs/architecture/learning-engine.md 第 6 节。
 *
 * Android `TextToSpeech` 不保证原句中间精确暂停，所以第一版的“继续”精度就是分段边界：
 * 先按句末标点切句，过长的句子再按逗号等停顿切，仍然过长就按长度硬切。
 */
object SpeechSegmenter {

    private const val MAX_SEGMENT_LENGTH = 36
    private val sentenceEnd = Regex("(?<=[。！？!?；;\\n])")
    private val clauseEnd = Regex("(?<=[，,、：:])")

    fun split(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return emptyList()
        return normalized.split(sentenceEnd)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { sentence ->
                if (sentence.length <= MAX_SEGMENT_LENGTH) listOf(sentence)
                else sentence.split(clauseEnd).map { it.trim() }.filter { it.isNotEmpty() }
                    .flatMap { clause ->
                        if (clause.length <= MAX_SEGMENT_LENGTH) listOf(clause)
                        else clause.chunked(MAX_SEGMENT_LENGTH)
                    }
            }
    }
}
