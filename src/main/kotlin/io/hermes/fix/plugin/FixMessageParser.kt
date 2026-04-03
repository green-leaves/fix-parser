package io.hermes.fix.plugin

import uk.co.real_logic.artio.dictionary.DictionaryParser
import uk.co.real_logic.artio.dictionary.ir.Dictionary
import uk.co.real_logic.artio.dictionary.ir.Field
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Parser for FIX protocol messages.
 * Auto-detects whether Agrona UnsafeBuffer is available (requires --add-opens JVM arg).
 * Falls back to a high-performance byte-array parser when it's not.
 */
object FixMessageParser {

    const val DEFAULT_DELIMITER = '\u0001'
    private val ALTERNATIVE_DELIMITERS = listOf('|', '\t', '^', ',')
    private val BEGIN_STRING_REGEX = Regex("8=FIX[0-9.]+")

    private var tagNameCache: HashMap<Int, Field>? = null
    private var valueDescriptionCache: HashMap<Long, String>? = null

    /** Whether Agrona UnsafeBuffer is usable in this JVM */
    private val unsafeAvailable: Boolean by lazy {
        try {
            val buffer = org.agrona.concurrent.UnsafeBuffer(ByteArray(1))
            buffer.getByte(0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun loadDictionary(inputStream: InputStream): Dictionary {
        val dictionaryParser = DictionaryParser(true)
        val dictionary = dictionaryParser.parse(inputStream, null)
        val nameCache = HashMap<Int, Field>()
        val descCache = HashMap<Long, String>()

        for ((_, field) in dictionary.fields()) {
            nameCache[field.number()] = field
            field.values()?.forEach { value ->
                val key = packKey(field.number(), value.toString())
                descCache[key] = value.description() ?: ""
            }
        }
        tagNameCache = nameCache
        valueDescriptionCache = descCache

        return dictionary
    }

    private fun packKey(fieldNumber: Int, value: String): Long {
        return fieldNumber.toLong().shl(32) or (value.hashCode().toLong() and 0xFFFFFFFFL)
    }

    fun detectDelimiter(text: String): DelimiterResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return DelimiterResult(DEFAULT_DELIMITER, false)
        }

        val counts = mutableMapOf<Char, Int>()
        for (char in trimmed) {
            if (char == DEFAULT_DELIMITER || char in ALTERNATIVE_DELIMITERS) {
                counts[char] = counts.getOrDefault(char, 0) + 1
            }
        }

        val bestDelimiter = counts.maxByOrNull { it.value }
        if (bestDelimiter != null && bestDelimiter.value >= 2) {
            return DelimiterResult(bestDelimiter.key, true)
        }

        return DelimiterResult(DEFAULT_DELIMITER, false)
    }

    fun parseMessage(text: String, delimiter: Char = DEFAULT_DELIMITER, dictionary: Dictionary? = null): FixMessage {
        if (text.isBlank()) {
            return FixMessage(
                rawMessage = text,
                tags = emptyList(),
                delimiter = delimiter,
                isValid = false,
                errorMessage = "Empty message",
                dictionary = dictionary
            )
        }

        val tags = if (unsafeAvailable) {
            parseWithAgrona(text, delimiter)
        } else {
            parseWithByteArray(text, delimiter)
        }

        val hasBeginString = tags.any { it.first == 8 }
        val hasMsgType = tags.any { it.first == 35 }

        return FixMessage(
            rawMessage = text,
            tags = tags.map { it.second },
            delimiter = delimiter,
            isValid = hasBeginString && hasMsgType,
            errorMessage = if (!hasBeginString || !hasMsgType) {
                "Missing required FIX tags (8=BeginString or 35=MsgType)"
            } else null,
            dictionary = dictionary
        )
    }

    // =========================================================================
    // Agrona-based parser (zero-copy, uses UnsafeBuffer)
    // =========================================================================
    private fun parseWithAgrona(text: String, delimiter: Char): List<Pair<Int, FixTag>> {
        val bytes = text.toByteArray(StandardCharsets.US_ASCII)
        val buffer = uk.co.real_logic.artio.util.MutableAsciiBuffer(bytes)
        val tags = ArrayList<Pair<Int, FixTag>>(32)
        val cache = tagNameCache
        val descCache = valueDescriptionCache
        val delimByte = delimiter.code.toByte()
        val eqByte = '='.code.toByte()

        var offset = 0
        val length = bytes.size

        while (offset < length) {
            // Find '='
            var eqIdx = offset
            while (eqIdx < length && buffer.getByte(eqIdx) != eqByte) eqIdx++
            if (eqIdx >= length) break

            val tagIdStr = buffer.getAscii(offset, eqIdx - offset)
            val tagIdInt = parseIntFast(tagIdStr)

            // Find delimiter
            var delimIdx = eqIdx + 1
            while (delimIdx < length && buffer.getByte(delimIdx) != delimByte) delimIdx++
            val value = buffer.getAscii(eqIdx + 1, delimIdx - (eqIdx + 1))

            tags.add(buildTag(tagIdInt, tagIdStr, value, cache, descCache))

            if (delimIdx >= length) break
            offset = delimIdx + 1
        }

        return tags
    }

    // =========================================================================
    // Byte-array parser (no Agrona dependency, still operates on raw bytes)
    // =========================================================================
    private fun parseWithByteArray(text: String, delimiter: Char): List<Pair<Int, FixTag>> {
        val bytes = text.toByteArray(StandardCharsets.US_ASCII)
        val tags = ArrayList<Pair<Int, FixTag>>(32)
        val cache = tagNameCache
        val descCache = valueDescriptionCache
        val delimByte = delimiter.code.toByte()
        val eqByte = '='.code.toByte()

        var offset = 0
        val length = bytes.size

        while (offset < length) {
            // Find '='
            var eqIdx = offset
            while (eqIdx < length && bytes[eqIdx] != eqByte) eqIdx++
            if (eqIdx >= length) break

            val tagIdStr = String(bytes, offset, eqIdx - offset, StandardCharsets.US_ASCII)
            val tagIdInt = parseIntFast(tagIdStr)

            // Find delimiter
            var delimIdx = eqIdx + 1
            while (delimIdx < length && bytes[delimIdx] != delimByte) delimIdx++
            val value = String(bytes, eqIdx + 1, delimIdx - (eqIdx + 1), StandardCharsets.US_ASCII)

            tags.add(buildTag(tagIdInt, tagIdStr, value, cache, descCache))

            if (delimIdx >= length) break
            offset = delimIdx + 1
        }

        return tags
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    /** Fast integer parse — avoids exception overhead for non-numeric tag IDs */
    private fun parseIntFast(s: String): Int {
        if (s.isEmpty()) return -1
        var result = 0
        for (c in s) {
            if (c < '0' || c > '9') return -1
            result = result * 10 + (c - '0')
        }
        return result
    }

    private fun buildTag(
        tagIdInt: Int, tagIdStr: String, value: String,
        cache: HashMap<Int, Field>?, descCache: HashMap<Long, String>?
    ): Pair<Int, FixTag> {
        val field = if (tagIdInt != -1) cache?.get(tagIdInt) else null
        val tagName = field?.name() ?: ""
        val description = if (field != null && descCache != null) {
            descCache[packKey(field.number(), value)] ?: ""
        } else ""

        return tagIdInt to FixTag(tagIdStr, tagName, value, description)
    }

    fun parseMessages(text: String, delimiter: Char = DEFAULT_DELIMITER, dictionary: Dictionary? = null): ParsedResult {
        val detected = detectDelimiter(text)
        val actualDelimiter = if (detected.confidence) detected.delimiter else delimiter
        val messages = mutableListOf<FixMessage>()

        val beginStringMatches = BEGIN_STRING_REGEX.findAll(text).toList()
        if (beginStringMatches.size <= 1) {
            messages.add(parseMessage(text.trim(), actualDelimiter, dictionary))
        } else {
            val splitPoints = mutableListOf<Int>()
            for (match in beginStringMatches) {
                if (match.range.first > 0) splitPoints.add(match.range.first)
            }

            var start = 0
            for (point in splitPoints) {
                val chunk = text.substring(start, point).trim()
                if (chunk.isNotBlank()) messages.add(parseMessage(chunk, actualDelimiter, dictionary))
                start = point
            }
            val lastChunk = text.substring(start).trim()
            if (lastChunk.isNotBlank()) messages.add(parseMessage(lastChunk, actualDelimiter, dictionary))
        }

        return ParsedResult(
            messages = messages,
            delimiter = actualDelimiter,
            detectedDelimiter = detected.confidence
        )
    }

    fun formatMessage(message: FixMessage): String {
        return message.tags.joinToString(message.delimiter.toString()) { tag ->
            "${tag.tagId}=${tag.value}"
        }
    }

    data class DelimiterResult(val delimiter: Char, val confidence: Boolean)
}
