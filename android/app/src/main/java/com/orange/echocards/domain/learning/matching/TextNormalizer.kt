package com.orange.echocards.domain.learning.matching

import java.text.Normalizer

/**
 * 文本标准化，见 docs/architecture/speech-matching.md 第 4 节。
 *
 * 目标文本和识别文本必须走同一条流程，否则覆盖率没有意义。
 */
interface TextNormalizer {
    fun normalize(text: String, language: String): String
}

/**
 * 中文 `v1` 标准化。
 *
 * 步骤顺序与设计文档第 4 节的列表不同：替换表必须放在删除标点**之前**，
 * 否则 `%`、`=`、`+`、`-` 会先被当标点删掉；另外 NFKC 已经把全角 `％`（U+FF05）
 * 折成半角 `%`，所以替换表的键是半角落法。
 */
object ZhTextNormalizer : TextNormalizer {

    /** 第 6 节：只做少量稳定替换；`2026`、`3/4`、`H₂O` 这类不确定读法的内容不自动转换。 */
    private val replacements = listOf(
        "%" to "百分之",
        "°c" to "摄氏度",
        "=" to "等于",
        "+" to "加",
        "-" to "减",
    )

    override fun normalize(text: String, language: String): String {
        val unified = Normalizer.normalize(text, Normalizer.Form.NFKC).let(::toHalfWidth).lowercase()
        var replaced = unified
        for ((from, to) in replacements) replaced = replaced.replace(from, to)
        return buildString(replaced.length) {
            for (character in replaced) if (isKept(character)) append(character)
        }
    }

    /** 全角字符折成半角，全角空格折成普通空格。 */
    private fun toHalfWidth(text: String): String = buildString(text.length) {
        for (character in text) {
            when (character.code) {
                in 0xFF01..0xFF5E -> append((character.code - 0xFEE0).toChar())
                0x3000 -> append(' ')
                else -> append(character)
            }
        }
    }

    /** 第 5 节：标点、空白都不参与匹配；保留中文、数字和英文字母。 */
    private fun isKept(character: Char): Boolean = when (character.code) {
        in 0x4E00..0x9FFF -> true
        in 0x3400..0x4DBF -> true
        0x3007 -> true
        else -> character.isLetterOrDigit()
    }
}
