package dev.tldr.summarizer

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GeminiSummarizerTest {
    private fun MockWebServer.base() = url("/v1beta").toString().trimEnd('/')

    @Test
    fun streamsChunksAndSetsLowThinking() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(
                        "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"**TL;DR:** \"}]}}]}\r\n\r\n" +
                            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"plan\",\"thought\":true},{\"text\":\"Xin chào\"}]}}]}\r\n\r\n"
                    )
            )
            val partials = mutableListOf<String>()
            val s = GeminiSummarizer("k", "gemini-3.8-flash", baseUrl = server.base())
            val result = s.summarize(SummaryRequest("Nội dung")) { partials.add(it) }
            assertEquals("**TL;DR:** Xin chào", result.markdown)
            assertEquals(listOf("**TL;DR:** ", "**TL;DR:** Xin chào"), partials)

            val recorded = server.takeRequest()
            assertEquals("/v1beta/models/gemini-3.8-flash:streamGenerateContent?alt=sse", recorded.path)
            assertEquals("k", recorded.getHeader("x-goog-api-key"))
            val sent = recorded.body.readUtf8()
            assertTrue(sent.contains("systemInstruction"))
            assertTrue(sent.contains("Nội dung"))
            assertTrue(sent.contains("\"thinkingLevel\":\"low\""))
        }
    }

    @Test
    fun rateLimitBecomesFriendlyError() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
            val s = GeminiSummarizer("k", baseUrl = server.base())
            val e = assertFailsWith<SummaryException> { s.summarize(SummaryRequest("abc")) }
            assertTrue(e.message!!.contains("giới hạn"))
        }
    }

    @Test
    fun retiredModelGivesActionableError() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(404)
                    .setBody("""{"error":{"code":404,"message":"This model models/gemini-2.5-flash is no longer available to new users."}}""")
            )
            val s = GeminiSummarizer("k", "gemini-2.5-flash", baseUrl = server.base())
            val e = assertFailsWith<SummaryException> { s.summarize(SummaryRequest("abc")) }
            assertTrue(e.message!!.contains("chọn mô hình khác"))
            assertTrue(e.message!!.contains("no longer available"))
            assertTrue(!e.message!!.contains("{"))
        }
    }

    @Test
    fun savedRetiredModelFallsBackToDefault() {
        assertEquals("gemini-3.8-flash", Provider.GEMINI.resolveModel("gemini-2.5-flash"))
        assertEquals("gemini-3.8-flash", Provider.GEMINI.resolveModel(""))
        assertEquals("gemini-3.5-flash-lite", Provider.GEMINI.resolveModel("gemini-3.5-flash-lite"))
    }
}
