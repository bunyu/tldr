package dev.tldr.summarizer

/** What the user wants summarized. */
data class SummaryRequest(
    /** Raw text: screen text, shared text, or an extracted web page. */
    val text: String,
    /** Where it came from, e.g. "Facebook" or "Chrome". Optional, helps the model ignore UI noise. */
    val sourceApp: String? = null,
    /** Page URL if known. */
    val url: String? = null,
    /** Page or post title if known. */
    val title: String? = null,
)

data class SummaryResult(
    /** Markdown summary in Vietnamese. */
    val markdown: String,
    /** True when the input was longer than [ContentPrep.MAX_CHARS] and got cut. */
    val inputTruncated: Boolean,
    val model: String,
)

class SummaryException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface Summarizer {
    suspend fun summarize(request: SummaryRequest, systemPrompt: String = Prompts.DEFAULT_SYSTEM): SummaryResult
}

enum class Provider(val label: String, val defaultModel: String, val modelChoices: List<String>) {
    CLAUDE(
        label = "Claude (Anthropic)",
        defaultModel = "claude-opus-5-5",
        modelChoices = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"),
    ),
    GEMINI(
        label = "Gemini (Google)",
        defaultModel = "gemini-2.5-flash",
        modelChoices = listOf("gemini-2.5-flash", "gemini-2.5-pro"),
    );

    fun create(apiKey: String, model: String = defaultModel): Summarizer = when (this) {
        CLAUDE -> ClaudeSummarizer(apiKey, model)
        GEMINI -> GeminiSummarizer(apiKey, model)
    }
}
