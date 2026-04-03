package io.hermes.fix.plugin

import org.agrona.collections.Int2ObjectHashMap
import uk.co.real_logic.artio.dictionary.DictionaryParser
import uk.co.real_logic.artio.dictionary.ir.Dictionary
import uk.co.real_logic.artio.dictionary.ir.Field
import uk.co.real_logic.artio.util.AsciiBuffer
import uk.co.real_logic.artio.util.MutableAsciiBuffer
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Parser for FIX protocol messages using Artio codecs and IR Dictionary.
 */
object FixMessageParser {

    /** Default FIX delimiter */
    const val DEFAULT_DELIMITER = '\u0001'

    /** Alternative delimiters to try */
    private val ALTERNATIVE_DELIMITERS = listOf('|', '\t', '^', ',')


    private var tagNameCache: Int2ObjectHashMap<Field>? = null

    /** Pre-built lookup: (fieldNumber, enumRepresentation) -> description */
    private var valueDescriptionCache: HashMap<Long, String>? = null

    /**
     * Loads a dictionary from an XML stream.
     */
    fun loadDictionary(inputStream: InputStream): Dictionary {
        val dictionaryParser = DictionaryParser(true)
        val dictionary = dictionaryParser.parse(inputStream, null)
        val nameCache = Int2ObjectHashMap<Field>()
        val descCache = HashMap<Long, String>()

        for ((_, field) in dictionary.fields()) {
            nameCache.put(field.number(), field)
            field.values()?.forEach { value ->
                // Pack fieldNumber + value representation into a composite key
                val key = packKey(field.number(), value.toString())
                descCache[key] = value.description() ?: ""
            }
        }
        tagNameCache = nameCache
        valueDescriptionCache = descCache

        return dictionary
    }

    /** Combine field number and value string into a single Long key for small enum values,
     *  falling back to a hash for longer strings. */
    private fun packKey(fieldNumber: Int, value: String): Long {
        return fieldNumber.toLong().shl(32) or (value.hashCode().toLong() and 0xFFFFFFFFL)
    }

    /**
     * Detect the delimiter used in the given text.
     */
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

    /**
     * Parse a single FIX message.
     */
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

        val bytes = text.toByteArray(StandardCharsets.US_ASCII)
        val buffer = MutableAsciiBuffer(bytes)
        val tags = mutableListOf<FixTag>()

        var offset = 0
        val length = buffer.capacity()
        val cache = tagNameCache
        val descCache = valueDescriptionCache
        var hasBeginString = false
        var hasMsgType = false

        while (offset < length) {
            val equalsIndex = findByte(buffer, offset, length, '='.code.toByte())
            if (equalsIndex == -1) break

            val tagIdStr = buffer.getAscii(offset, equalsIndex - offset)
            val tagIdInt = tagIdStr.toIntOrNull() ?: -1

            val delimiterIndex = findByte(buffer, equalsIndex + 1, length, delimiter.code.toByte())
            val valueEnd = if (delimiterIndex == -1) length else delimiterIndex
            val value = buffer.getAscii(equalsIndex + 1, valueEnd - (equalsIndex + 1))

            val field = if (tagIdInt != -1) cache?.get(tagIdInt) else null
            val tagName = field?.name() ?: ""

            val description = if (field != null && descCache != null) {
                descCache[packKey(field.number(), value)] ?: ""
            } else ""

            tags.add(FixTag(tagIdStr, tagName, value, description))

            if (tagIdInt == 8) hasBeginString = true
            else if (tagIdInt == 35) hasMsgType = true

            if (delimiterIndex == -1) break
            offset = delimiterIndex + 1
        }

        return FixMessage(
            rawMessage = text,
            tags = tags,
            delimiter = delimiter,
            isValid = hasBeginString && hasMsgType,
            errorMessage = if (!hasBeginString || !hasMsgType) {
                "Missing required FIX tags (8=BeginString or 35=MsgType)"
            } else null,
            dictionary = dictionary
        )
    }

    private fun findByte(buffer: AsciiBuffer, start: Int, end: Int, byte: Byte): Int {
        for (i in start until end) {
            if (buffer.getByte(i) == byte) return i
        }
        return -1
    }

    /**
     * Parse multiple FIX messages.
     */
    fun parseMessages(text: String, delimiter: Char = DEFAULT_DELIMITER, dictionary: Dictionary? = null): ParsedResult {
        val detected = detectDelimiter(text)
        val actualDelimiter = if (detected.confidence) detected.delimiter else delimiter
        val messages = mutableListOf<FixMessage>()

        val beginStringMatches = Regex("8=FIX[0-9.]+").findAll(text).toList()
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
