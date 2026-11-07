package dev.tldr.summarizer

object ContentPrep {
    /** Roughly 40-50k tokens. Screen captures are far smaller; this only bites on huge web pages. */
    const val MAX_CHARS = 150_000

    /** Minimum amount of text worth sending to a model. */
    const val MIN_CHARS = 20

    data class Prepared(val text: String, val truncated: Boolean)

    /**
     * Normalizes whitespace, drops blank lines and repeated lines (accessibility trees often expose the
     * same label twice), and caps the length.
     */
    fun prepare(raw: String): Prepared {
        val seen = HashSet<String>()
        val lines = raw.lineSequence()
            .map { it.replace(Regex("[\\t\\u00A0 ]+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .filter { seen.add(it) }
            .toList()
        val joined = lines.joinToString("\n")
        return if (joined.length > MAX_CHARS) {
            Prepared(joined.substring(0, MAX_CHARS), truncated = true)
        } else {
            Prepared(joined, truncated = false)
        }
    }
}

object UrlFinder {
    private val URL = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)

    /** First http(s) URL in [text], with trailing punctuation removed. */
    fun firstUrl(text: String): String? =
        URL.find(text)?.value?.trimEnd('.', ',', ';', ':', ')', ']', '!', '?')

    /** True when [text] is basically just a link (what most apps put in a share intent). */
    fun isMostlyUrl(text: String): Boolean {
        val url = firstUrl(text) ?: return false
        return text.replace(url, "").trim().length < 80
    }
}
