package dev.tldr.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.tldr.summarizer.Prompts
import dev.tldr.summarizer.Provider
import dev.tldr.summarizer.Summarizer

/** User settings. API keys are stored encrypted with a key from the Android Keystore. */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "tldr_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var provider: Provider
        get() = prefs.getString(KEY_PROVIDER, null)
            ?.let { name -> Provider.entries.firstOrNull { it.name == name } }
            ?: Provider.CLAUDE
        set(value) = prefs.edit().putString(KEY_PROVIDER, value.name).apply()

    fun apiKey(provider: Provider): String = prefs.getString("api_key_${provider.name}", "").orEmpty()
    fun setApiKey(provider: Provider, key: String) =
        prefs.edit().putString("api_key_${provider.name}", key.trim()).apply()

    fun model(provider: Provider): String = provider.resolveModel(prefs.getString("model_${provider.name}", null))
    fun setModel(provider: Provider, model: String) =
        prefs.edit().putString("model_${provider.name}", model.trim()).apply()

    var systemPrompt: String
        get() = prefs.getString(KEY_PROMPT, null)?.takeIf { it.isNotBlank() } ?: Prompts.DEFAULT_SYSTEM
        set(value) = prefs.edit().putString(KEY_PROMPT, value).apply()

    var bubbleEnabled: Boolean
        get() = prefs.getBoolean(KEY_BUBBLE, true)
        set(value) = prefs.edit().putBoolean(KEY_BUBBLE, value).apply()

    /** The configured summarizer, or null when no API key has been entered yet. */
    fun summarizer(): Summarizer? {
        val p = provider
        val key = apiKey(p)
        if (key.isBlank()) return null
        return p.create(key, model(p))
    }

    private companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_PROMPT = "system_prompt"
        const val KEY_BUBBLE = "bubble_enabled"
    }
}
