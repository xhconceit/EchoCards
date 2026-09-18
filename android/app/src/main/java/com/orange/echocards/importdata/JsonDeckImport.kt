package com.orange.echocards.importdata

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class ImportedCard(
    val title: String,
    val content: String,
    val speechText: String,
    val memoryTip: String,
)

data class ImportedDeck(
    val title: String,
    val description: String,
    val cards: List<ImportedCard>,
    val fingerprint: String,
)

class ImportException(message: String) : IllegalArgumentException(message)

object JsonDeckImport {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_CARDS = 1000

    fun read(resolver: ContentResolver, uri: Uri): ImportedDeck {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size == -1) break
                if (output.size() + size > MAX_BYTES) throw ImportException("文件不能超过 2 MiB")
                output.write(buffer, 0, size)
            }
            output.toByteArray()
        } ?: throw ImportException("无法读取所选文件")
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: Exception) {
            throw ImportException("文件必须使用 UTF-8 编码")
        }
        return parse(text)
    }

    fun parse(text: String): ImportedDeck {
        val root = try { JsonParser(text).parse() as? Map<*, *> ?: throw ImportException("顶层必须是卡组对象") }
        catch (error: ImportException) { throw error }
        catch (_: Exception) { throw ImportException("JSON 格式错误，请检查文件内容") }
        val title = required(root, "title", "卡组 title")
        val description = optional(root, "description", "卡组 description")
        val array = root["cards"] as? List<*> ?: throw ImportException("cards 必须是非空数组")
        if (array.isEmpty()) throw ImportException("cards 必须是非空数组")
        if (array.size > MAX_CARDS) throw ImportException("最多导入 1000 张卡片")
        val cards = array.mapIndexed { index, item ->
            val value = item as? Map<*, *>
                ?: throw ImportException("第 ${index + 1} 张卡片必须是对象")
            val prefix = "第 ${index + 1} 张卡片的 "
            ImportedCard(
                title = required(value, "title", "${prefix}title"),
                content = required(value, "content", "${prefix}content"),
                speechText = optional(value, "speechText", "${prefix}speechText"),
                memoryTip = optional(value, "memoryTip", "${prefix}memoryTip"),
            )
        }
        return ImportedDeck(title, description, cards, fingerprint(title, description, cards))
    }

    fun fingerprint(title: String, description: String, cards: List<ImportedCard>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            fun part(value: String) {
                val encoded = value.trim().toByteArray(StandardCharsets.UTF_8)
                output.writeInt(encoded.size)
                output.write(encoded)
            }
            part(title)
            part(description)
            output.writeInt(cards.size)
            cards.forEach { card ->
                part(card.title)
                part(card.content)
                part(card.speechText)
                part(card.memoryTip)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun required(obj: Map<*, *>, key: String, label: String): String =
        (obj[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ImportException("$label 必须是非空字符串")

    private fun optional(obj: Map<*, *>, key: String, label: String): String {
        val value = obj[key]
        if (value == null) return ""
        return (value as? String)?.trim() ?: throw ImportException("$label 必须是字符串")
    }
}

private class JsonParser(private val source: String) {
    private var index = 0

    fun parse(): Any? {
        val value = value()
        whitespace()
        if (index != source.length) fail()
        return value
    }

    private fun value(): Any? {
        whitespace()
        if (index >= source.length) fail()
        return when (source[index]) {
            '{' -> objectValue()
            '[' -> arrayValue()
            '"' -> stringValue()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            '-', in '0'..'9' -> numberValue()
            else -> fail()
        }
    }

    private fun objectValue(): Map<String, Any?> {
        index++
        whitespace()
        val result = linkedMapOf<String, Any?>()
        if (take('}')) return result
        while (true) {
            whitespace()
            if (index >= source.length || source[index] != '"') fail()
            val key = stringValue()
            whitespace()
            if (!take(':')) fail()
            result[key] = value()
            whitespace()
            if (take('}')) return result
            if (!take(',')) fail()
        }
    }

    private fun arrayValue(): List<Any?> {
        index++
        whitespace()
        val result = mutableListOf<Any?>()
        if (take(']')) return result
        while (true) {
            result += value()
            whitespace()
            if (take(']')) return result
            if (!take(',')) fail()
        }
    }

    private fun stringValue(): String {
        if (!take('"')) fail()
        val result = StringBuilder()
        while (index < source.length) {
            val char = source[index++]
            when (char) {
                '"' -> return result.toString()
                '\\' -> {
                    if (index >= source.length) fail()
                    result.append(when (val escaped = source[index++]) {
                        '"', '\\', '/' -> escaped
                        'b' -> '\b'
                        'f' -> '\u000C'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> {
                            if (index + 4 > source.length) fail()
                            source.substring(index, index + 4).toIntOrNull(16)?.toChar() ?: fail()
                                .also { index += 4 }
                        }
                        else -> fail()
                    })
                }
                else -> {
                    if (char.code < 0x20) fail()
                    result.append(char)
                }
            }
        }
        fail()
    }

    private fun numberValue(): Number {
        val start = index
        if (source[index] == '-') index++
        while (index < source.length && source[index].isDigit()) index++
        if (index < source.length && source[index] == '.') {
            index++
            while (index < source.length && source[index].isDigit()) index++
        }
        if (index < source.length && source[index] in "eE") {
            index++
            if (index < source.length && source[index] in "+-") index++
            while (index < source.length && source[index].isDigit()) index++
        }
        val token = source.substring(start, index)
        return token.toLongOrNull() ?: token.toDoubleOrNull() ?: fail()
    }

    private fun <T> literal(word: String, result: T): T {
        if (!source.startsWith(word, index)) fail()
        index += word.length
        return result
    }

    private fun whitespace() { while (index < source.length && source[index].isWhitespace()) index++ }
    private fun take(char: Char): Boolean = if (index < source.length && source[index] == char) { index++; true } else false
    private fun fail(): Nothing = throw IllegalArgumentException("invalid json")
}
