package dev.tldr.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.tldr.app.databinding.ActivitySummaryBinding
import dev.tldr.summarizer.ContentPrep
import dev.tldr.summarizer.SummaryException
import dev.tldr.summarizer.SummaryRequest
import dev.tldr.summarizer.SummaryResult
import dev.tldr.summarizer.UrlFinder
import dev.tldr.summarizer.WebPageFetcher
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows a Vietnamese summary in a dialog-style window. Started by the bubble, by "Share → TLDR",
 * or by selecting text and picking "Tóm tắt".
 */
class SummaryActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySummaryBinding
    private lateinit var settings: SettingsStore
    private lateinit var markwon: Markwon
    private var job: Job? = null
    private var summaryText: String = ""

    /** What we were asked to summarize. */
    private data class Input(val text: String, val sourceApp: String?, val url: String?, val title: String?, val fetchPage: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsStore(this)
        markwon = Markwon.create(this)

        binding.close.setOnClickListener { finish() }
        binding.copy.setOnClickListener { copySummary() }
        binding.share.setOnClickListener { shareSummary() }

        start(parseInput(intent))
    }

    override fun onResume() {
        super.onResume()
        // Keep the bubble from covering the summary.
        TldrAccessibilityService.instance?.setBubbleVisible(false)
    }

    override fun onPause() {
        TldrAccessibilityService.instance?.setBubbleVisible(true)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        start(parseInput(intent))
    }

    private fun parseInput(intent: Intent): Input = when (intent.action) {
        Intent.ACTION_SEND -> {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            val url = UrlFinder.firstUrl(shared)
            // A bare link: download the page. Text with a link in it: summarize the text itself.
            Input(shared, sourceApp = null, url = url, title = subject, fetchPage = url != null && UrlFinder.isMostlyUrl(shared))
        }
        Intent.ACTION_PROCESS_TEXT -> {
            val selected = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
            Input(selected, sourceApp = null, url = null, title = null, fetchPage = false)
        }
        else -> Input(
            text = intent.getStringExtra(EXTRA_TEXT).orEmpty(),
            sourceApp = intent.getStringExtra(EXTRA_SOURCE_APP),
            url = intent.getStringExtra(EXTRA_URL),
            title = null,
            fetchPage = false,
        )
    }

    private fun start(input: Input) {
        job?.cancel()
        binding.sourceLine.text = listOfNotNull(input.sourceApp, input.title, input.url).joinToString(" · ")
        binding.fullPage.visibility = View.GONE
        binding.fullPage.setText(R.string.full_page)
        binding.fullPage.setOnClickListener { start(input.copy(fetchPage = true)) }

        val summarizer = settings.summarizer()
        if (summarizer == null) {
            showError(getString(R.string.no_key), offerSettings = true)
            return
        }

        showLoading(if (input.fetchPage) R.string.loading_page else R.string.loading)
        val startedAt = System.currentTimeMillis()
        var streaming = false
        job = lifecycleScope.launch {
            // Show how long we've been waiting until the first words arrive.
            val ticker = launch {
                while (isActive && !streaming) {
                    delay(1000)
                    val secs = (System.currentTimeMillis() - startedAt) / 1000
                    if (!streaming) binding.loadingText.text = getString(R.string.loading_elapsed, secs)
                }
            }
            try {
                var request = SummaryRequest(input.text, input.sourceApp, input.url, input.title)
                if (input.fetchPage && input.url != null) {
                    val page = WebPageFetcher.fetch(input.url)
                    // Pages behind a login (e.g. Facebook) come back nearly empty; keep the screen text then.
                    if (page.text.length > input.text.length) {
                        request = request.copy(text = page.text, title = page.title ?: input.title)
                    }
                    binding.loadingText.setText(R.string.loading)
                }
                if (request.text.trim().length < ContentPrep.MIN_CHARS) {
                    showError(getString(R.string.no_text), offerSettings = false)
                    return@launch
                }
                val result = summarizer.summarize(request, settings.systemPrompt) { partial ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (!streaming) {
                            streaming = true
                            showStreaming()
                        }
                        markwon.setMarkdown(binding.summary, partial)
                    }
                }
                streaming = true
                withContext(Dispatchers.Main) {
                    showResult(result, canFetchPage = input.url != null && !input.fetchPage)
                }
            } catch (e: SummaryException) {
                streaming = true
                showError(e.message ?: e.toString(), offerSettings = false)
            } finally {
                ticker.cancel()
            }
        }
    }

    private fun showLoading(textRes: Int) {
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        binding.loading.visibility = View.VISIBLE
        binding.loadingText.setText(textRes)
        binding.scroll.visibility = View.GONE
        binding.note.visibility = View.GONE
        binding.copy.isEnabled = false
        binding.share.isEnabled = false
    }

    /** First words have arrived: swap the spinner for the summary text, which keeps growing. */
    private fun showStreaming() {
        expandWindow()
        binding.loading.visibility = View.GONE
        binding.scroll.visibility = View.VISIBLE
    }

    private fun showResult(result: SummaryResult, canFetchPage: Boolean) {
        summaryText = result.markdown
        expandWindow()
        binding.loading.visibility = View.GONE
        binding.scroll.visibility = View.VISIBLE
        markwon.setMarkdown(binding.summary, result.markdown)
        binding.note.visibility = View.VISIBLE
        binding.note.text = buildString {
            append(result.model)
            if (result.inputTruncated) append(" · ").append(getString(R.string.truncated_note))
        }
        binding.fullPage.visibility = if (canFetchPage) View.VISIBLE else View.GONE
        binding.copy.isEnabled = true
        binding.share.isEnabled = true
    }

    private fun showError(message: String, offerSettings: Boolean) {
        summaryText = ""
        binding.loading.visibility = View.GONE
        binding.scroll.visibility = View.VISIBLE
        expandWindow()
        binding.summary.text = message
        if (offerSettings) {
            binding.fullPage.visibility = View.VISIBLE
            binding.fullPage.setText(R.string.open_settings)
            binding.fullPage.setOnClickListener {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
        }
    }

    /** Gives the scrollable summary most of the screen. */
    private fun expandWindow() {
        val height = (resources.displayMetrics.heightPixels * 0.8).toInt()
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height)
    }

    private fun copySummary() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("TLDR", summaryText))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareSummary() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, summaryText)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    companion object {
        const val EXTRA_TEXT = "dev.tldr.extra.TEXT"
        const val EXTRA_SOURCE_APP = "dev.tldr.extra.SOURCE_APP"
        const val EXTRA_URL = "dev.tldr.extra.URL"
    }
}
