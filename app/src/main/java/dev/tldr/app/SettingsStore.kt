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

    private val prefs: SharedPreferences by lazy {
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

    init {
        migrateDefaultModel()
    }

    /**
     * Before 0.1.5 the settings screen saved whatever model was shown, so users who never chose
     * one are pinned to the old default. Move them to the new, faster default once.
     */
    private fun migrateDefaultModel() {
        if (prefs.getBoolean(KEY_MIGRATED_015, false)) return
        val key = "model_${Provider.GEMINI.name}"
        if (prefs.getString(key, null) == "gemini-3.8-flash") prefs.edit().remove(key).apply()
        prefs.edit().putBoolean(KEY_MIGRATED_015, true).apply()
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
    /** Stores "" for the default model, so a future change of default applies automatically. */
    fun setModel(provider: Provider, model: String) {
        val id = model.trim().takeIf { it != provider.defaultModel }.orEmpty()
        prefs.edit().putString("model_${provider.name}", id).apply()
    }

    var systemPrompt: String
        get() = prefs.getString(KEY_PROMPT, null)?.takeIf { it.isNotBlank() } ?: Prompts.DEFAULT_SYSTEM
        set(value) = prefs.edit().putString(KEY_PROMPT, value).apply()

    var bubbleEnabled: Boolean
        get() = prefs.getBoolean(KEY_BUBBLE, true)
        set(value) = prefs.edit().putBoolean(KEY_BUBBLE, value).apply()

    /**
     * The configured summarizer, or null when no API key has been entered yet. The instance is
     * reused while settings stay the same, so a connection opened by [Summarizer.warmUp] is kept.
     */
    fun summarizer(): Summarizer? {
        val p = provider
        val key = apiKey(p)
        if (key.isBlank()) return null
        val config = Triple(p, key, model(p))
        synchronized(SettingsStore) {
            cached?.takeIf { it.first == config }?.let { return it.second }
            return p.create(key, config.third).also { cached = config to it }
        }
    }

    private companion object {
        var cached: Pair<Triple<Provider, String, String>, Summarizer>? = null

        const val KEY_PROVIDER = "provider"
        const val KEY_PROMPT = "system_prompt"
        const val KEY_BUBBLE = "bubble_enabled"
        const val KEY_MIGRATED_015 = "migrated_default_model_015"
    }
}
