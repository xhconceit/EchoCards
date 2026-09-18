package com.orange.echocards

import com.orange.echocards.importdata.ImportException
import com.orange.echocards.importdata.JsonDeckImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonDeckImportTest {
    @Test
    fun parsesAndNormalizesValidDeck() {
        val result = JsonDeckImport.parse(
            """{"title":" 物理 ","description":null,"cards":[{"title":" 惯性 ","content":" 保持状态 ","speechText":null}]}"""
        )

        assertEquals("物理", result.title)
        assertEquals("", result.description)
        assertEquals("惯性", result.cards.single().title)
        assertEquals("保持状态", result.cards.single().content)
        assertEquals("", result.cards.single().speechText)
    }

    @Test
    fun equivalentJsonProducesSameFingerprint() {
        val first = JsonDeckImport.parse(
            """{"title":"物理","cards":[{"title":"惯性","content":"保持状态"}]}"""
        )
        val second = JsonDeckImport.parse(
            """{"ignored":1,"cards":[{"memoryTip":null,"content":" 保持状态 ","title":" 惯性 "}],"title":" 物理 "}"""
        )

        assertEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun cardOrderAndContentAffectFingerprint() {
        val first = JsonDeckImport.parse(
            """{"title":"物理","cards":[{"title":"A","content":"1"},{"title":"B","content":"2"}]}"""
        )
        val reordered = JsonDeckImport.parse(
            """{"title":"物理","cards":[{"title":"B","content":"2"},{"title":"A","content":"1"}]}"""
        )
        val changed = JsonDeckImport.parse(
            """{"title":"物理","cards":[{"title":"A","content":"3"},{"title":"B","content":"2"}]}"""
        )

        assertNotEquals(first.fingerprint, reordered.fingerprint)
        assertNotEquals(first.fingerprint, changed.fingerprint)
    }

    @Test
    fun rejectsMalformedAndInvalidFields() {
        assertThrows(ImportException::class.java) { JsonDeckImport.parse("{bad") }
        assertThrows(ImportException::class.java) { JsonDeckImport.parse("{}") }
        assertThrows(ImportException::class.java) { JsonDeckImport.parse("""{"title":"x","cards":[]}""") }
        val error = assertThrows(ImportException::class.java) {
            JsonDeckImport.parse("""{"title":"x","cards":[{"title":"a","content":2}]}""")
        }
        assertEquals("第 1 张卡片的 content 必须是非空字符串", error.message)
    }

    @Test
    fun rejectsTrailingJson() {
        assertThrows(ImportException::class.java) {
            JsonDeckImport.parse("""{"title":"x","cards":[{"title":"a","content":"b"}]} {}""")
        }
    }
}
