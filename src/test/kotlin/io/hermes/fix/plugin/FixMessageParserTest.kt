package io.hermes.fix.plugin

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class FixMessageParserTest {

    private val SOH = '\u0001'
    private val PIPE = '|'

    // ─── Sample messages ──────────────────────────────────────────────────────

    /** Minimal valid SOH-delimited FIX message */
    private fun sohMsg(
        msgType: String = "D",
        sender: String = "BUYER",
        target: String = "SELLER",
        time: String = "20240101-12:30:00"
    ) = "8=FIX.4.4${SOH}9=10${SOH}35=$msgType${SOH}49=$sender${SOH}56=$target${SOH}52=$time${SOH}10=123${SOH}"

    /** Same message but pipe-delimited */
    private fun pipeMsg(
        msgType: String = "D",
        sender: String = "BUYER",
        target: String = "SELLER",
        time: String = "20240101-12:30:00"
    ) = "8=FIX.4.4|9=10|35=$msgType|49=$sender|56=$target|52=$time|10=123|"

    // ─── detectDelimiter ──────────────────────────────────────────────────────

    @Nested
    inner class DetectDelimiter {

        @Test
        fun `detects SOH when it appears multiple times`() {
            val result = FixMessageParser.detectDelimiter(sohMsg())
            assertEquals(SOH, result.delimiter)
            assertTrue(result.confidence)
        }

        @Test
        fun `detects pipe when it appears multiple times`() {
            val result = FixMessageParser.detectDelimiter(pipeMsg())
            assertEquals(PIPE, result.delimiter)
            assertTrue(result.confidence)
        }

        @Test
        fun `returns SOH with no confidence for empty string`() {
            val result = FixMessageParser.detectDelimiter("")
            assertEquals(SOH, result.delimiter)
            assertFalse(result.confidence)
        }

        @Test
        fun `returns no confidence when delimiter appears only once`() {
            val result = FixMessageParser.detectDelimiter("8=FIX.4.4|35=D")
            assertFalse(result.confidence)
        }

        @Test
        fun `picks delimiter with highest occurrence count`() {
            // SOH appears 7 times, pipe only once — should prefer SOH
            val mixed = sohMsg() + "|extra"
            val result = FixMessageParser.detectDelimiter(mixed)
            assertEquals(SOH, result.delimiter)
        }
    }

    // ─── parseMessage ─────────────────────────────────────────────────────────

    @Nested
    inner class ParseMessage {

        @Test
        fun `parses all tags from SOH-delimited message`() {
            val msg = FixMessageParser.parseMessage(sohMsg(), SOH)
            val tagIds = msg.tags.map { it.tagId }
            assertTrue(tagIds.containsAll(listOf("8", "9", "35", "49", "56", "52", "10")))
        }

        @Test
        fun `parses all tags from pipe-delimited message`() {
            val msg = FixMessageParser.parseMessage(pipeMsg(), PIPE)
            val tagIds = msg.tags.map { it.tagId }
            assertTrue(tagIds.containsAll(listOf("8", "9", "35", "49", "56", "52", "10")))
        }

        @Test
        fun `tag values are parsed correctly`() {
            val msg = FixMessageParser.parseMessage(sohMsg(msgType = "D", sender = "ACME"), SOH)
            assertEquals("D", msg.tags.find { it.tagId == "35" }?.value)
            assertEquals("ACME", msg.tags.find { it.tagId == "49" }?.value)
        }

        @Test
        fun `valid message has isValid true`() {
            val msg = FixMessageParser.parseMessage(sohMsg(), SOH)
            assertTrue(msg.isValid)
            assertNull(msg.errorMessage)
        }

        @Test
        fun `empty input produces invalid message`() {
            val msg = FixMessageParser.parseMessage("", SOH)
            assertFalse(msg.isValid)
            assertNotNull(msg.errorMessage)
        }

        @Test
        fun `message missing BeginString tag 8 is invalid`() {
            val noTag8 = "35=D${SOH}49=BUYER${SOH}56=SELLER${SOH}"
            val msg = FixMessageParser.parseMessage(noTag8, SOH)
            assertFalse(msg.isValid)
        }

        @Test
        fun `message missing MsgType tag 35 is invalid`() {
            val noTag35 = "8=FIX.4.4${SOH}49=BUYER${SOH}56=SELLER${SOH}"
            val msg = FixMessageParser.parseMessage(noTag35, SOH)
            assertFalse(msg.isValid)
        }

        @Test
        fun `rawMessage is preserved exactly`() {
            val raw = sohMsg()
            val msg = FixMessageParser.parseMessage(raw, SOH)
            assertEquals(raw, msg.rawMessage)
        }

        @Test
        fun `delimiter is preserved on parsed message`() {
            val msg = FixMessageParser.parseMessage(pipeMsg(), PIPE)
            assertEquals(PIPE, msg.delimiter)
        }
    }

    // ─── FixMessage computed properties ───────────────────────────────────────

    @Nested
    inner class FixMessageProperties {

        @Test
        fun `sender is extracted from tag 49`() {
            val msg = FixMessageParser.parseMessage(sohMsg(sender = "CORP_A"), SOH)
            assertEquals("CORP_A", msg.sender)
        }

        @Test
        fun `target is extracted from tag 56`() {
            val msg = FixMessageParser.parseMessage(sohMsg(target = "CORP_B"), SOH)
            assertEquals("CORP_B", msg.target)
        }

        @Test
        fun `time is extracted from tag 52 after the T separator`() {
            val msg = FixMessageParser.parseMessage(sohMsg(time = "20240101T09:30:00"), SOH)
            assertEquals("09:30:00", msg.time)
        }

        @Test
        fun `time falls back to full tag 52 value when no T separator`() {
            // FIX standard uses "YYYYMMDD-HH:MM:SS" — no 'T', so full value is returned
            val msg = FixMessageParser.parseMessage(sohMsg(time = "20240101-09:30:00"), SOH)
            assertEquals("20240101-09:30:00", msg.time)
        }

        @Test
        fun `sender defaults to N-A when tag 49 absent`() {
            val raw = "8=FIX.4.4${SOH}35=D${SOH}56=SELLER${SOH}"
            val msg = FixMessageParser.parseMessage(raw, SOH)
            assertEquals("N/A", msg.sender)
        }

        @Test
        fun `target defaults to N-A when tag 56 absent`() {
            val raw = "8=FIX.4.4${SOH}35=D${SOH}49=BUYER${SOH}"
            val msg = FixMessageParser.parseMessage(raw, SOH)
            assertEquals("N/A", msg.target)
        }

        @Test
        fun `time defaults to N-A when tag 52 absent`() {
            val raw = "8=FIX.4.4${SOH}35=D${SOH}49=BUYER${SOH}56=SELLER${SOH}"
            val msg = FixMessageParser.parseMessage(raw, SOH)
            assertEquals("N/A", msg.time)
        }
    }

    // ─── parseMessages ────────────────────────────────────────────────────────

    @Nested
    inner class ParseMessages {

        @Test
        fun `parses a single message`() {
            val result = FixMessageParser.parseMessages(sohMsg())
            assertEquals(1, result.messages.size)
        }

        @Test
        fun `splits two concatenated messages on BeginString boundary`() {
            val combined = sohMsg(msgType = "D") + sohMsg(msgType = "8")
            val result = FixMessageParser.parseMessages(combined)
            assertEquals(2, result.messages.size)
        }

        @Test
        fun `each split message is parsed independently`() {
            val combined = sohMsg(msgType = "D", sender = "A") + sohMsg(msgType = "8", sender = "B")
            val result = FixMessageParser.parseMessages(combined)
            assertEquals("A", result.messages[0].sender)
            assertEquals("B", result.messages[1].sender)
        }

        @Test
        fun `auto-detects pipe delimiter for multi-message input`() {
            val combined = pipeMsg(msgType = "D") + pipeMsg(msgType = "8")
            val result = FixMessageParser.parseMessages(combined)
            assertEquals(PIPE, result.delimiter)
            assertTrue(result.detectedDelimiter)
        }

        @Test
        fun `blank input produces a single invalid message`() {
            val result = FixMessageParser.parseMessages("   ")
            assertEquals(1, result.messages.size)
            assertFalse(result.messages[0].isValid)
        }

        @Test
        fun `splits three concatenated messages correctly`() {
            val combined = sohMsg(msgType = "D") + sohMsg(msgType = "8") + sohMsg(msgType = "0")
            val result = FixMessageParser.parseMessages(combined)
            assertEquals(3, result.messages.size)
        }
    }

    // ─── formatMessage ────────────────────────────────────────────────────────

    @Nested
    inner class FormatMessage {

        @Test
        fun `formats tags as tag=value joined by delimiter`() {
            val msg = FixMessageParser.parseMessage(sohMsg(), SOH)
            val formatted = FixMessageParser.formatMessage(msg)
            assertTrue(formatted.contains("8=FIX.4.4"))
            assertTrue(formatted.contains("35=D"))
            assertTrue(formatted.contains("49=BUYER"))
        }

        @Test
        fun `uses message delimiter when formatting`() {
            val msg = FixMessageParser.parseMessage(pipeMsg(), PIPE)
            val formatted = FixMessageParser.formatMessage(msg)
            assertTrue(formatted.contains(PIPE))
            assertFalse(formatted.contains(SOH))
        }

        @Test
        fun `round-trip parse then format preserves all tag values`() {
            val msg = FixMessageParser.parseMessage(sohMsg(), SOH)
            val formatted = FixMessageParser.formatMessage(msg)
            val reparsed = FixMessageParser.parseMessage(formatted, SOH)
            assertEquals(msg.tags.map { it.tagId to it.value },
                         reparsed.tags.map { it.tagId to it.value })
        }
    }
}
