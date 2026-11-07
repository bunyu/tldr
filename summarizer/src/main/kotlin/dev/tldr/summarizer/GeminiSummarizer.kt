package dev.tldr.summarizer

import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.TimeUnit

/** Google Gemini via its REST API (generateContent). */
class GeminiSummarizer(
    private val apiKey: String,
    private val model: String = Provider.GEMINI.defaultModel,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    private val http: OkHttpClient = defaultClient,
) : Summarizer {

    override suspend fun summarize(request: SummaryRequest, systemPrompt: String): SummaryResult =
        withContext(Dispatchers.IO) {
            val prepared = ContentPrep.prepare(request.text)
            val body = buildJsonObject {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { addJsonObject { put("text", systemPrompt) } }
                }
                putJsonArray("contents") {
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            addJsonObject { put("text", Prompts.userMessage(request, prepared.text)) }
                        }
                    }
                }
            }

            val httpRequest = Request.Builder()
                .url("$baseUrl/models/$model:generateContent")
                .header("x-goog-api-key", apiKey)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val responseText = try {
                http.newCall(httpRequest).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    when {
                        resp.code == 400 && raw.contains("API_KEY_INVALID") ->
                            throw SummaryException("API key Gemini không hợp lệ. Kiểm tra lại trong Cài đặt.")
                        resp.code == 404 ->
                            throw SummaryException(
                                "Mô hình \"$model\" không còn dùng được với Gemini. " +
                                    "Mở TLDR, chọn mô hình khác (ví dụ ${Provider.GEMINI.defaultModel}) rồi thử lại.\n\n" +
                                    "Chi tiết: ${errorMessage(raw)}"
                            )
                        resp.code == 429 ->
                            throw SummaryException("Gemini đang giới hạn tốc độ. Thử lại sau ít phút.")
                        !resp.isSuccessful ->
                            throw SummaryException("Lỗi từ Gemini (${resp.code}): ${errorMessage(raw)}")
                    }
                    raw
                }
            } catch (e: SummaryException) {
                throw e
            } catch (e: Exception) {
                throw SummaryException("Không kết nối được tới Gemini: ${e.message}", e)
            }

            val text = parseText(responseText)
            if (text.isEmpty()) throw SummaryException("Gemini không trả về nội dung (có thể bị chặn bởi bộ lọc an toàn).")
            SummaryResult(markdown = text, inputTruncated = prepared.truncated, model = model)
        }

    /** Pulls error.message out of a Gemini error body, falling back to the raw text. */
    internal fun errorMessage(raw: String): String = try {
        Json.parseToJsonElement(raw).jsonObject["error"]?.jsonObject?.get("message")
            ?.jsonPrimitive?.contentOrNull ?: raw.take(300)
    } catch (e: Exception) {
        raw.take(300)
    }

    internal fun parseText(raw: String): String {
        val root = Json.parseToJsonElement(raw).jsonObject
        val candidates = root["candidates"]?.jsonArray ?: return ""
        val first = candidates.firstOrNull()?.jsonObject ?: return ""
        val parts = (first["content"] as? JsonObject)?.get("parts")?.jsonArray ?: return ""
        return parts.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
            .joinToString("")
            .trim()
    }

    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .callTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
