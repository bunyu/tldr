package dev.tldr.summarizer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebPageFetcherTest {
    @Test
    fun extractsArticleAndDropsChrome() {
        val html = """
            <html><head><title>T</title><meta property="og:title" content="Bài viết"></head>
            <body><nav>Menu Home</nav><article><h1>Tiêu đề</h1><p>Đoạn một.</p><p>Đoạn hai.</p></article>
            <footer>Copyright</footer><script>var x=1</script></body></html>
        """.trimIndent()
        val page = WebPageFetcher.extract(html, "https://example.com")
        assertEquals("Bài viết", page.title)
        assertTrue(page.text.contains("Đoạn một.\nĐoạn hai."))
        assertFalse(page.text.contains("Menu"))
        assertFalse(page.text.contains("Copyright"))
    }
}
