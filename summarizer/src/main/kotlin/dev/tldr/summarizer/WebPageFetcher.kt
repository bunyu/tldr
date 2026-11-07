package dev.tldr.summarizer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class PageContent(val url: String, val title: String?, val text: String)

/** Downloads a page and pulls out its readable text. Works for public pages, not ones behind a login. */
object WebPageFetcher {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    suspend fun fetch(url: String): PageContent = withContext(Dispatchers.IO) {
        val doc = try {
            Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(20_000)
                .followRedirects(true)
                .get()
        } catch (e: Exception) {
            throw SummaryException("Không tải được trang: ${e.message}", e)
        }
        extract(doc, url)
    }

    fun extract(html: String, url: String): PageContent = extract(Jsoup.parse(html, url), url)

    private fun extract(doc: Document, url: String): PageContent {
        doc.select("script, style, noscript, svg, nav, footer, header, aside, form, iframe, [aria-hidden=true]").remove()
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.title().takeIf { it.isNotBlank() }
        val description = doc.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")?.trim().orEmpty()

        val root = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body()
        val blocks = root?.select("h1, h2, h3, h4, p, li, blockquote, pre, td")
            ?.map { it.text().trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        var text = blocks.joinToString("\n")
        // Pages built from bare <div>s have few block tags; fall back to all text in that case.
        val allText = root?.text().orEmpty()
        if (text.length < 200 && allText.length > text.length * 2) text = allText
        if (description.isNotEmpty() && !text.contains(description)) text = description + "\n" + text
        return PageContent(url = url, title = title, text = text)
    }
}
