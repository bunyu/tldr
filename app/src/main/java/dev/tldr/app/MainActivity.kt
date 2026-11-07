package dev.tldr.app

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import dev.tldr.app.databinding.ActivityMainBinding
import dev.tldr.summarizer.Prompts
import dev.tldr.summarizer.Provider

/** Setup screen: enable the bubble, pick a provider, enter an API key, tweak the prompt. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsStore
    private var shownProvider: Provider = Provider.CLAUDE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsStore(this)

        // Android 15 draws apps edge to edge; keep the toolbar and content clear of the system bars.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        binding.openAppInfo.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
        }

        binding.openAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.bubbleSwitch.isChecked = settings.bubbleEnabled
        binding.bubbleSwitch.setOnCheckedChangeListener { _, checked ->
            settings.bubbleEnabled = checked
            TldrAccessibilityService.instance?.let { if (checked) it.showBubble() else it.hideBubble() }
        }

        shownProvider = settings.provider
        binding.providerGroup.check(if (shownProvider == Provider.CLAUDE) R.id.providerClaude else R.id.providerGemini)
        showProviderFields(shownProvider)
        binding.providerGroup.setOnCheckedChangeListener { _, checkedId ->
            saveProviderFields(shownProvider)
            shownProvider = if (checkedId == R.id.providerClaude) Provider.CLAUDE else Provider.GEMINI
            showProviderFields(shownProvider)
        }

        binding.prompt.setText(settings.systemPrompt)
        binding.resetPrompt.setOnClickListener { binding.prompt.setText(Prompts.DEFAULT_SYSTEM) }

        binding.save.setOnClickListener {
            save()
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }

        binding.tryIt.setOnClickListener {
            save()
            startActivity(
                Intent(this, SummaryActivity::class.java)
                    .putExtra(SummaryActivity.EXTRA_TEXT, getString(R.string.sample_text))
                    .putExtra(SummaryActivity.EXTRA_SOURCE_APP, getString(R.string.app_name))
            )
        }
    }

    override fun onResume() {
        super.onResume()
        val enabled = isServiceEnabled()
        binding.serviceStatus.setText(if (enabled) R.string.service_on else R.string.service_off)
        binding.setupSteps.visibility = if (enabled) android.view.View.GONE else android.view.View.VISIBLE
        binding.openAppInfo.visibility = binding.setupSteps.visibility
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    private fun showProviderFields(provider: Provider) {
        binding.apiKey.setText(settings.apiKey(provider))
        binding.model.setSimpleItems(provider.modelChoices.toTypedArray())
        binding.model.setText(settings.model(provider), false)
        binding.keyHelp.setText(if (provider == Provider.CLAUDE) R.string.claude_key_help else R.string.gemini_key_help)
    }

    private fun saveProviderFields(provider: Provider) {
        settings.setApiKey(provider, binding.apiKey.text?.toString().orEmpty())
        settings.setModel(provider, binding.model.text?.toString().orEmpty())
    }

    private fun save() {
        settings.provider = shownProvider
        saveProviderFields(shownProvider)
        // Store "" for the default so future improvements to the built-in prompt still apply.
        val prompt = binding.prompt.text?.toString().orEmpty()
        settings.systemPrompt = if (prompt.trim() == Prompts.DEFAULT_SYSTEM.trim()) "" else prompt
    }

    private fun isServiceEnabled(): Boolean {
        if (TldrAccessibilityService.instance != null) return true
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = ComponentName(this, TldrAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }
}
