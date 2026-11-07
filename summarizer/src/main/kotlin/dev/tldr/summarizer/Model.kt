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

/** [retryable] is true when trying again later may work (overload, timeout, network). */
class SummaryException(
    message: String,
    cause: Throwable? = null,
    val retryable: Boolean = false,
) : Exception(message, cause)

interface Summarizer {
    /**
     * Summarizes [request]. The answer streams in: [onPartial] gets the text received so far
     * (called on a background thread), and the return value is the complete result.
     */
    suspend fun summarize(
        request: SummaryRequest,
        systemPrompt: String = Prompts.DEFAULT_SYSTEM,
        onPartial: (String) -> Unit = {},
        /** Short Vietnamese status updates, e.g. "retrying with another model". */
        onStatus: (String) -> Unit = {},
    ): SummaryResult

    /**
     * Opens the HTTPS connection to the provider ahead of time so the real request skips the
     * DNS/TLS handshake. Best effort: never throws.
     */
    suspend fun warmUp() {}
}

enum class Provider(
    val label: String,
    val defaultModel: String,
    val modelChoices: List<String>,
    /** Model ids the provider no longer serves to new users; a saved one is replaced by [defaultModel]. */
    val retiredModels: Set<String> = emptySet(),
) {
    CLAUDE(
        label = "Claude (Anthropic)",
        defaultModel = "claude-opus-5-5",
        modelChoices = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"),
    ),
    GEMINI(
        label = "Gemini (Google)",
        // Flash-Lite answers fastest and is overloaded less often; plenty for summaries.
        defaultModel = "gemini-3.5-flash-lite",
        modelChoices = listOf("gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-flash-latest"),
        retiredModels = setOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro"),
    );

    /** The model to use for a saved choice: blank or retired ids fall back to the default. */
    fun resolveModel(saved: String?): String {
        val id = saved?.trim().orEmpty()
        return if (id.isEmpty() || id in retiredModels) defaultModel else id
    }

    fun create(apiKey: String, model: String = defaultModel): Summarizer = when (this) {
        CLAUDE -> ClaudeSummarizer(apiKey, model)
        GEMINI -> GeminiSummarizer(apiKey, model)
    }
}
