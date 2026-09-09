package com.termux.pro.shared.ai;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Reads and writes the AI assistant settings. Values are stored in the default shared
 * preferences file of the app so that the settings screens (which use the same file)
 * automatically stay in sync.
 */
public final class AiSettings {

    private final SharedPreferences mPrefs;

    public AiSettings(Context context) {
        // The default shared preferences file used by androidx.preference screens is
        // "<packageName>_preferences". Using the same file keeps settings in sync.
        mPrefs = context.getApplicationContext().getSharedPreferences(
            context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }

    // ----------------------------------------------------------------------------------
    // Provider
    // ----------------------------------------------------------------------------------

    public String getProvider() {
        String provider = mPrefs.getString(AiConstants.KEY_PROVIDER, null);
        if (provider == null) return AiConstants.PROVIDER_GEMINI;
        return provider;
    }

    public void setProvider(String provider) {
        if (provider != null)
            mPrefs.edit().putString(AiConstants.KEY_PROVIDER, provider).apply();
    }

    public String getProviderLabel() {
        String provider = getProvider();
        for (int i = 0; i < AiConstants.PROVIDER_IDS.length; i++) {
            if (AiConstants.PROVIDER_IDS[i].equals(provider))
                return AiConstants.PROVIDER_LABELS[i];
        }
        return provider;
    }

    // ----------------------------------------------------------------------------------
    // API key (encrypted at rest). A separate key slot exists for every provider so
    // that switching providers never destroys another provider's key.
    // ----------------------------------------------------------------------------------

    private String apiKeyPrefKey() {
        return AiConstants.KEY_API_KEY + "_" + getProvider();
    }

    /** Returns the decrypted API key of the current provider or "" when none is set. */
    public String getApiKey() {
        String stored = mPrefs.getString(apiKeyPrefKey(), null);
        String decrypted = AiKeyCipher.decrypt(stored);
        return decrypted == null ? "" : decrypted;
    }

    /** Stores a new API key for the current provider (encrypted). Returns false when encryption failed. */
    public boolean setApiKey(String apiKey) {
        String prefKey = apiKeyPrefKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            mPrefs.edit().remove(prefKey).apply();
            return true;
        }
        String encrypted = AiKeyCipher.encrypt(apiKey.trim());
        if (encrypted == null) return false;
        mPrefs.edit().putString(prefKey, encrypted).apply();
        return true;
    }

    /** True when a (non-empty, decryptable) key is stored. */
    public boolean hasApiKey() {
        return !getApiKey().isEmpty();
    }

    // ----------------------------------------------------------------------------------
    // Model
    // ----------------------------------------------------------------------------------

    public String getModelKey() {
        switch (getProvider()) {
            case AiConstants.PROVIDER_GEMINI: return AiConstants.KEY_MODEL_GEMINI;
            case AiConstants.PROVIDER_OPENAI: return AiConstants.KEY_MODEL_OPENAI;
            case AiConstants.PROVIDER_ANTHROPIC: return AiConstants.KEY_MODEL_ANTHROPIC;
            case AiConstants.PROVIDER_GROQ: return AiConstants.KEY_MODEL_GROQ;
            case AiConstants.PROVIDER_OLLAMA: return AiConstants.KEY_MODEL_OLLAMA;
            default: return AiConstants.KEY_MODEL_GEMINI;
        }
    }

    public String getModel() {
        String model = mPrefs.getString(getModelKey(), null);
        if (model == null || model.trim().isEmpty())
            return AiConstants.DEFAULT_MODELS.get(getProvider());
        return model;
    }

    public void setModel(String model) {
        if (model != null)
            mPrefs.edit().putString(getModelKey(), model).apply();
    }

    // ----------------------------------------------------------------------------------
    // Ollama
    // ----------------------------------------------------------------------------------

    public String getOllamaUrl() {
        String url = mPrefs.getString(AiConstants.KEY_OLLAMA_URL, null);
        if (url == null || url.trim().isEmpty())
            return AiConstants.DEFAULT_OLLAMA_URL;
        return url.trim();
    }

    public void setOllamaUrl(String url) {
        if (url != null)
            mPrefs.edit().putString(AiConstants.KEY_OLLAMA_URL, url.trim()).apply();
    }

    // ----------------------------------------------------------------------------------
    // Behaviour
    // ----------------------------------------------------------------------------------

    /** Whether the user must approve every command before the AI runs it. */
    public boolean shouldAskConfirmation() {
        return mPrefs.getBoolean(AiConstants.KEY_ASK_CONFIRMATION, true);
    }

    public int getMaxRetries() {
        return parsePositiveInt(mPrefs.getString(AiConstants.KEY_MAX_RETRIES, null),
            AiConstants.DEFAULT_MAX_RETRIES);
    }

    public int getCommandTimeoutSeconds() {
        return parsePositiveInt(mPrefs.getString(AiConstants.KEY_COMMAND_TIMEOUT, null),
            AiConstants.DEFAULT_COMMAND_TIMEOUT_SECONDS);
    }

    public long getCommandTimeoutMillis() {
        return getCommandTimeoutSeconds() * 1000L;
    }

    private static int parsePositiveInt(String value, int def) {
        if (value == null) return def;
        try {
            int i = Integer.parseInt(value.trim());
            return i > 0 ? i : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** True when the setup is complete enough to chat (ollama never needs a key). */
    public boolean isConfigured() {
        if (AiConstants.PROVIDER_OLLAMA.equals(getProvider())) return true;
        return hasApiKey();
    }
}
