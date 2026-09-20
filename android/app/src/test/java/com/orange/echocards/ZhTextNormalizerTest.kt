package com.orange.echocards

import com.orange.echocards.domain.learning.matching.ZhTextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

/** 覆盖 docs/architecture/speech-matching.md 第 4 至 6 节的标准化规则。 */
class ZhTextNormalizerTest {

    private fun normalize(text: String) = ZhTextNormalizer.normalize(text, "zh-CN")

    @Test
    fun removesPunctuationAndWhitespace() {
        assertEquals(
            "惯性是物体保持运动状态的性质",
            normalize("“惯性”，是物体保持运动状态的性质。"),
        )
    }

    @Test
    fun convertsFullWidthCharactersAndLowercasesLetters() {
        assertEquals("abc123", normalize("ＡＢＣ　１２３"))
        assertEquals("h2o", normalize("H₂O"))
    }

    @Test
    fun dropsEveryPunctuationMarkFromTheAgreedSet() {
        val punctuation = "，。！？、：；“”‘’,.!?:;'\"（）()【】[]"
        assertEquals("", normalize(punctuation))
    }

    @Test
    fun keepsDigitsAndLettersUntouched() {
        assertEquals("2026", normalize("2026"))
        assertEquals("34", normalize("3/4"))
    }

    @Test
    fun appliesTheSmallReplacementTable() {
        assertEquals("50百分之", normalize("50%"))
        assertEquals("50百分之", normalize("５０％"))
        assertEquals("25摄氏度", normalize("25°C"))
        assertEquals("1等于1", normalize("1=1"))
        assertEquals("1加1", normalize("1+1"))
        assertEquals("5减3", normalize("5-3"))
    }

    @Test
    fun normalizationIsStableForAlreadyNormalizedText() {
        val once = normalize("“惯性”，是物体保持运动状态的性质。")
        assertEquals(once, normalize(once))
    }
}
