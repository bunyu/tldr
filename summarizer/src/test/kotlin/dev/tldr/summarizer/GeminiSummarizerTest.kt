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

    private fun sse(text: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"$text\"}]}}]}\r\n\r\n")

    private val overloaded = """{"error":{"code":503,"message":"This model is currently experiencing high demand."}}"""

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
            val result = s.summarize(SummaryRequest("Nội dung"), onPartial = { partials.add(it) })
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
    fun overloadIsRetriedOnSameModel() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody(overloaded))
            server.enqueue(sse("ok"))
            val statuses = mutableListOf<String>()
            val s = GeminiSummarizer("k", "gemini-3.8-flash", baseUrl = server.base(), backoffMs = 1)
            val result = s.summarize(SummaryRequest("abc"), onStatus = { statuses.add(it) })
            assertEquals("ok", result.markdown)
            assertEquals("gemini-3.8-flash", result.model)
            assertEquals(1, statuses.size)
        }
    }

    @Test
    fun persistentOverloadFallsBackToAnotherModel() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody(overloaded))
            server.enqueue(MockResponse().setResponseCode(503).setBody(overloaded))
            server.enqueue(sse("dự phòng"))
            val s = GeminiSummarizer("k", "gemini-3.8-flash", baseUrl = server.base(), backoffMs = 1)
            val result = s.summarize(SummaryRequest("abc"))
            assertEquals("dự phòng", result.markdown)
            assertEquals("gemini-3.6-flash", result.model)
            server.takeRequest(); server.takeRequest()
            assertTrue(server.takeRequest().path!!.contains("gemini-3.6-flash"))
        }
    }

    @Test
    fun allModelsOverloadedGivesRetryableVietnameseError() = runBlocking {
        MockWebServer().use { server ->
            repeat(6) { server.enqueue(MockResponse().setResponseCode(503).setBody(overloaded)) }
            val s = GeminiSummarizer("k", "gemini-3.8-flash", baseUrl = server.base(), backoffMs = 1)
            val e = assertFailsWith<SummaryException> { s.summarize(SummaryRequest("abc")) }
            assertTrue(e.retryable)
            assertTrue(e.message!!.contains("quá tải"))
        }
    }

    @Test
    fun rejectedThinkingLevelIsDropped() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"thinking_level is not supported"}}"""))
            server.enqueue(sse("ok"))
            val s = GeminiSummarizer("k", "gemini-3.5-flash-lite", baseUrl = server.base(), backoffMs = 1)
            assertEquals("ok", s.summarize(SummaryRequest("abc")).markdown)
            assertTrue(server.takeRequest().body.readUtf8().contains("thinkingLevel"))
            assertTrue(!server.takeRequest().body.readUtf8().contains("thinkingLevel"))
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
        assertEquals("gemini-3.5-flash-lite", Provider.GEMINI.resolveModel("gemini-2.5-flash"))
        assertEquals("gemini-3.5-flash-lite", Provider.GEMINI.resolveModel(""))
        assertEquals("gemini-3.8-flash", Provider.GEMINI.resolveModel("gemini-3.8-flash"))
    }
}
