package io.hermes.fix.plugin

import uk.co.real_logic.artio.dictionary.ir.Dictionary
import uk.co.real_logic.artio.dictionary.ir.Field

/**
 * Represents a FIX protocol tag-value pair
 */
data class FixTag(
    val tagId: String,
    val tagName: String,
    val value: String,
    val description: String = ""
)

/**
 * Represents a parsed FIX message with metadata for summary display
 */
data class FixMessage(
    val rawMessage: String,
    val tags: List<FixTag>,
    val delimiter: Char,
    val isValid: Boolean,
    val errorMessage: String? = null,
    val dictionary: Dictionary? = null
) {
    val time: String = tags.find { it.tagId == "52" }?.value?.substringAfter('T') ?: "N/A"
    val sender: String = tags.find { it.tagId == "49" }?.value ?: "N/A"
    val target: String = tags.find { it.tagId == "56" }?.value ?: "N/A"
    val msgType: String = tags.find { it.tagId == "35" }?.let { tag ->
        // In Artio IR Dictionary, getMessage() should be correct. Let's check when compiling.
        dictionary?.messages()?.find { it.fullType() == tag.value }?.name() ?: "Unknown (${tag.value})"
    } ?: "Unknown"
}

/**
 * Result of parsing one or more FIX messages
 */
data class ParsedResult(
    val messages: List<FixMessage>,
    val delimiter: Char,
    val detectedDelimiter: Boolean
)
