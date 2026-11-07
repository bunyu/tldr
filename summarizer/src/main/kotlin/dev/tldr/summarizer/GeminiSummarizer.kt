package dev.tldr.summarizer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** Google Gemini via its REST API, streamed with streamGenerateContent (server-sent events). */
class GeminiSummarizer(
    private val apiKey: String,
    private val model: String = Provider.GEMINI.defaultModel,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    private val http: OkHttpClient = defaultClient,
    private val backoffMs: Long = 2_000,
) : Summarizer {

    override suspend fun summarize(
        request: SummaryRequest,
        systemPrompt: String,
        onPartial: (String) -> Unit,
        onStatus: (String) -> Unit,
    ): SummaryResult = withContext(Dispatchers.IO) {
        val prepared = ContentPrep.prepare(request.text)
        val userText = Prompts.userMessage(request, prepared.text)

        // Try the chosen model a few times, then the backup models. Overload (503) and rate
        // limits (429) are usually short-lived or specific to one model.
        val models = listOf(model) + FALLBACK_MODELS.filter { it != model }
        var lastError: SummaryException? = null
        for ((index, current) in models.withIndex()) {
            if (index > 0) onStatus("Gemini $model đang quá tải, chuyển sang $current…")
            var withThinking = usesThinkingLevel(current)
            var attempt = 0
            while (attempt < ATTEMPTS_PER_MODEL) {
                val outcome = try {
                    val text = stream(current, systemPrompt, userText, withThinking, onPartial)
                    return@withContext SummaryResult(text, prepared.truncated, current)
                } catch (e: HttpError) {
                    e
                }
                when {
                    // Some models don't accept a thinking level; retry once without it.
                    outcome.code == 400 && withThinking && outcome.body.contains("thinking", ignoreCase = true) -> {
                        withThinking = false
                        continue
                    }
                    outcome.code in TRANSIENT_CODES -> {
                        lastError = errorFor(outcome.code, outcome.body, current)
                        attempt++
                        if (attempt < ATTEMPTS_PER_MODEL) {
                            onStatus("Gemini đang bận, thử lại lần $attempt…")
                            delay(backoffMs * attempt)
                        }
                    }
                    // 404 on a backup model just means it isn't available to this key; move on.
                    outcome.code == 404 && index > 0 -> break
                    else -> throw errorFor(outcome.code, outcome.body, current)
                }
            }
        }
        throw lastError ?: SummaryException("Gemini không trả lời.", retryable = true)
    }

    private class HttpError(val code: Int, val body: String) : Exception()

    /** One streamed request. Returns the full text; throws [HttpError] for non-2xx responses. */
    private fun stream(
        model: String,
        systemPrompt: String,
        userText: String,
        withThinking: Boolean,
        onPartial: (String) -> Unit,
    ): String {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", systemPrompt) } }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", userText) } }
                }
            }
            if (withThinking) {
                // Gemini 3 models think at "medium" by default, which makes a summary take far longer.
                putJsonObject("generationConfig") {
                    putJsonObject("thinkingConfig") { put("thinkingLevel", "low") }
                }
            }
        }

        val httpRequest = Request.Builder()
            .url("$baseUrl/models/$model:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val text = StringBuilder()
        try {
            http.newCall(httpRequest).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpError(resp.code, resp.body?.string().orEmpty())
                val source = resp.body?.source() ?: throw HttpError(502, "")
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val chunk = parseText(line.removePrefix("data:").trim())
                    if (chunk.isNotEmpty()) {
                        text.append(chunk)
                        onPartial(text.toString())
                    }
                }
            }
        } catch (e: HttpError) {
            throw e
        } catch (e: InterruptedIOException) {
            throw SummaryException(
                "Gemini trả lời quá lâu (hơn $TIMEOUT_SECONDS giây). Bấm Thử lại, hoặc chọn mô hình nhanh hơn như gemini-3.5-flash-lite.",
                e,
                retryable = true,
            )
        } catch (e: Exception) {
            throw SummaryException("Không kết nối được tới Gemini: ${e.message}", e, retryable = true)
        }

        val result = text.toString().trim()
        if (result.isEmpty()) throw SummaryException("Gemini không trả về nội dung (có thể bị chặn bởi bộ lọc an toàn).")
        return result
    }

    private fun errorFor(code: Int, raw: String, model: String): SummaryException = when {
        code == 400 && raw.contains("API_KEY_INVALID") ->
            SummaryException("API key Gemini không hợp lệ. Kiểm tra lại trong Cài đặt.")
        code == 404 ->
            SummaryException(
                "Mô hình \"$model\" không còn dùng được với Gemini. " +
                    "Mở TLDR, chọn mô hình khác (ví dụ ${Provider.GEMINI.defaultModel}) rồi thử lại.\n\n" +
                    "Chi tiết: ${errorMessage(raw)}"
            )
        code == 429 -> SummaryException("Gemini đang giới hạn tốc độ (hết lượt miễn phí trong phút này). Bấm Thử lại sau ít phút.", retryable = true)
        code >= 500 -> SummaryException("Máy chủ Gemini đang quá tải, đã thử lại vài lần và cả mô hình dự phòng nhưng chưa được. Bấm Thử lại sau giây lát.", retryable = true)
        else -> SummaryException("Lỗi từ Gemini ($code): ${errorMessage(raw)}")
    }

    /** Pulls error.message out of a Gemini error body, falling back to the raw text. */
    internal fun errorMessage(raw: String): String = try {
        Json.parseToJsonElement(raw).jsonObject["error"]?.jsonObject?.get("message")
            ?.jsonPrimitive?.contentOrNull ?: raw.take(300)
    } catch (e: Exception) {
        raw.take(300)
    }

    /** Text parts of one response (or one streamed chunk), skipping the model's thought summaries. */
    internal fun parseText(raw: String): String {
        val root = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            return ""
        }
        val candidates = root["candidates"]?.jsonArray ?: return ""
        val first = candidates.firstOrNull()?.jsonObject ?: return ""
        val parts = (first["content"] as? JsonObject)?.get("parts")?.jsonArray ?: return ""
        return parts.mapNotNull { part ->
            val obj = part as? JsonObject ?: return@mapNotNull null
            if (obj["thought"]?.jsonPrimitive?.contentOrNull == "true") return@mapNotNull null
            obj["text"]?.jsonPrimitive?.contentOrNull
        }.joinToString("")
    }

    companion object {
        const val TIMEOUT_SECONDS = 90L
        private const val ATTEMPTS_PER_MODEL = 2
        private val TRANSIENT_CODES = setOf(429, 500, 502, 503, 504)

        /** Tried in order when the chosen model is overloaded. */
        val FALLBACK_MODELS = listOf("gemini-3.6-flash", "gemini-3.5-flash-lite")

        fun usesThinkingLevel(model: String): Boolean =
            model.startsWith("gemini-3") || model.startsWith("gemini-4") || model.endsWith("-latest")

        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS + 30, TimeUnit.SECONDS)
            .build()
    }
}
