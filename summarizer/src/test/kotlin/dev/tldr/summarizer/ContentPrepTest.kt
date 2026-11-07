package dev.tldr.summarizer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentPrepTest {
    @Test
    fun dropsBlankAndRepeatedLines() {
        val prepared = ContentPrep.prepare("Hello\n\n  Hello  \nThích\nWorld\tthere\nThích")
        assertEquals("Hello\nThích\nWorld there", prepared.text)
        assertFalse(prepared.truncated)
    }

    @Test
    fun capsVeryLongInput() {
        val raw = (1..20_000).joinToString("\n") { "line number $it" }
        val prepared = ContentPrep.prepare(raw)
        assertEquals(ContentPrep.MAX_CHARS, prepared.text.length)
        assertTrue(prepared.truncated)
    }

    @Test
    fun findsUrlInSharedText() {
        assertEquals("https://example.com/a?b=1", UrlFinder.firstUrl("Xem nè: https://example.com/a?b=1."))
        assertTrue(UrlFinder.isMostlyUrl("Bài hay https://vnexpress.net/abc.html"))
        assertFalse(UrlFinder.isMostlyUrl("no link here"))
    }

    @Test
    fun userMessageIncludesContext() {
        val msg = Prompts.userMessage(SummaryRequest("x", sourceApp = "Facebook", url = "https://fb.com/1"), "body")
        assertTrue(msg.contains("Ứng dụng: Facebook"))
        assertTrue(msg.contains("URL: https://fb.com/1"))
        assertTrue(msg.contains("<noi_dung>\nbody\n</noi_dung>"))
    }

    @Test
    fun haikuSkipsServerFallback() {
        assertFalse(ClaudeSummarizer.supportsServerFallback("claude-haiku-5-5"))
        assertTrue(ClaudeSummarizer.supportsServerFallback("claude-opus-5-5"))
    }
}
