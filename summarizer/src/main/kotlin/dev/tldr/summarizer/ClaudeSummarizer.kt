package dev.tldr.summarizer

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ClaudeSummarizer(
    apiKey: String,
    private val model: String = Provider.CLAUDE.defaultModel,
) : Summarizer {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .build()

    override suspend fun summarize(request: SummaryRequest, systemPrompt: String): SummaryResult =
        withContext(Dispatchers.IO) {
            val prepared = ContentPrep.prepare(request.text)
            val builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16_000L)
                .system(systemPrompt)
                .addUserMessage(Prompts.userMessage(request, prepared.text))
                // Summaries are a simple task; low effort keeps them fast and cheap.
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())

            if (supportsServerFallback(model)) {
                // If a safety classifier declines, the API retries on a recommended fallback model.
                builder.addBeta("server-side-fallback-2026-07-01")
                builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }

            val message = try {
                client.beta().messages().create(builder.build())
            } catch (e: UnauthorizedException) {
                throw SummaryException("API key Claude không hợp lệ. Kiểm tra lại trong Cài đặt.", e)
            } catch (e: RateLimitException) {
                throw SummaryException("Claude đang giới hạn tốc độ. Thử lại sau ít phút.", e)
            } catch (e: AnthropicServiceException) {
                throw SummaryException("Lỗi từ Claude (${e.statusCode()}): ${e.message}", e)
            } catch (e: Exception) {
                throw SummaryException("Không kết nối được tới Claude: ${e.message}", e)
            }

            if (message.stopReason().orElse(null) == BetaStopReason.REFUSAL) {
                throw SummaryException("Claude đã từ chối tóm tắt nội dung này.")
            }

            val text = message.content()
                .mapNotNull { block -> block.text().orElse(null)?.text() }
                .joinToString("")
                .trim()
            if (text.isEmpty()) throw SummaryException("Claude không trả về nội dung.")

            SummaryResult(markdown = text, inputTruncated = prepared.truncated, model = model)
        }

    companion object {
        /** Haiku has no server-side fallback; sending the parameter would be pointless. */
        fun supportsServerFallback(model: String): Boolean = !model.contains("haiku")
    }
}
