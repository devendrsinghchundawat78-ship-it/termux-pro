package com.termux.pro.app.fragments.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.pro.R;
import com.termux.pro.app.AiAgentActivity;
import com.termux.pro.shared.ai.AiClient;
import com.termux.pro.shared.ai.AiClient.AiTestResult;
import com.termux.pro.shared.ai.AiConstants;
import com.termux.pro.shared.ai.AiSettings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings screen for the AI assistant: provider, API key (stored encrypted), model,
 * connection test and safety limits.
 *
 * The "Test Connection" button is the heart of the screen: it gives the user clear,
 * simple feedback for every possible problem (wrong key, wrong provider, no internet,
 * rate limit, model not available...) instead of a generic error.
 */
@Keep
public class AiAssistantPreferencesFragment extends PreferenceFragmentCompat {

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private AiSettings mSettings;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.ai_assistant_preferences, rootKey);
        Context context = getContext();
        if (context == null) return;

        mSettings = new AiSettings(context);

        ListPreference providerPreference = findPreference("ai_provider");
        if (providerPreference != null) {
            providerPreference.setEntries(AiConstants.PROVIDER_LABELS);
            providerPreference.setEntryValues(AiConstants.PROVIDER_IDS);
            providerPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                mSettings.setProvider(String.valueOf(newValue));
                refreshUi();
                return true;
            });
        }

        EditTextPreference apiKeyPreference = findPreference("ai_api_key");
        if (apiKeyPreference != null) {
            apiKeyPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                String key = newValue == null ? "" : String.valueOf(newValue).trim();
                boolean stored = mSettings.setApiKey(key);
                if (!key.isEmpty() && !stored) {
                    Toast.makeText(getContext(), R.string.ai_key_store_failed, Toast.LENGTH_LONG).show();
                }
                refreshApiKeySummary();
                return false; // value is managed by AiSettings (encrypted), not persisted raw
            });
        }

        Preference testPreference = findPreference("ai_test_connection");
        if (testPreference != null) {
            testPreference.setOnPreferenceClickListener(preference -> {
                runTestConnection(preference);
                return true;
            });
        }

        Preference openAgentPreference = findPreference("ai_agent_open");
        if (openAgentPreference != null) {
            openAgentPreference.setOnPreferenceClickListener(preference -> {
                startActivity(new android.content.Intent(getContext(), AiAgentActivity.class));
                return true;
            });
        }

        Preference helpPreference = findPreference("ai_help");
        if (helpPreference != null) {
            helpPreference.setOnPreferenceClickListener(preference -> {
                showHelpDialog();
                return true;
            });
        }

        refreshUi();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mSettings != null) refreshUi();
    }

    // ----------------------------------------------------------------------------------

    /** Re-syncs all preferences with the stored settings (after any change). */
    private void refreshUi() {
        if (mSettings == null) return;
        String provider = mSettings.getProvider();

        // Provider list
        ListPreference providerPreference = findPreference("ai_provider");
        if (providerPreference != null && providerPreference.getValue() == null) {
            providerPreference.setValue(provider);
        }

        // API key summary
        refreshApiKeySummary();

        // Model lists
        for (String providerId : AiConstants.PROVIDER_IDS) {
            String key = modelKeyForProvider(providerId);
            if (key == null) continue;
            ListPreference modelPreference = findPreference(key);
            if (modelPreference == null) continue;

            List<String> options = new ArrayList<>(AiConstants.MODEL_OPTIONS.get(providerId));
            modelPreference.setEntries(options.toArray(new String[0]));
            modelPreference.setEntryValues(options.toArray(new String[0]));
            modelPreference.setVisible(providerId.equals(provider));
        }

        // Current model selection
        String modelKey = modelKeyForProvider(provider);
        if (modelKey != null) {
            ListPreference modelPreference = findPreference(modelKey);
            if (modelPreference != null && modelPreference.getValue() == null) {
                modelPreference.setValue(AiConstants.DEFAULT_MODELS.get(provider));
            }
        }

        // Ollama URL visibility
        Preference ollamaUrlPreference = findPreference("ai_ollama_url");
        if (ollamaUrlPreference != null) {
            boolean isOllama = AiConstants.PROVIDER_OLLAMA.equals(provider);
            ollamaUrlPreference.setVisible(isOllama);
            if (isOllama) {
                ollamaUrlPreference.setSummary(getString(R.string.ai_ollama_url_summary_current, mSettings.getOllamaUrl()));
            }
        }

        // Test connection button summary
        Preference testPreference = findPreference("ai_test_connection");
        if (testPreference != null) {
            testPreference.setSummary(getString(R.string.ai_test_connection_summary_default));
        }
    }

    private void refreshApiKeySummary() {
        EditTextPreference apiKeyPreference = findPreference("ai_api_key");
        if (apiKeyPreference == null || mSettings == null) return;

        String hint = AiConstants.KEY_HINTS.get(mSettings.getProvider());
        String key = mSettings.getApiKey();
        if (key.isEmpty()) {
            apiKeyPreference.setSummary(getString(R.string.ai_api_key_summary_empty));
        } else {
            String masked = maskKey(key);
            apiKeyPreference.setSummary(getString(R.string.ai_api_key_summary_set, masked));
        }
        apiKeyPreference.setDialogMessage(getString(R.string.ai_api_key_dialog_message, hint));
    }

    private static String maskKey(String key) {
        if (key.length() <= 4) return "••••";
        return "••••••••" + key.substring(key.length() - 4);
    }

    // ----------------------------------------------------------------------------------
    // Test connection
    // ----------------------------------------------------------------------------------

    private void runTestConnection(final Preference testPreference) {
        if (mSettings == null || getContext() == null) return;
        final Context context = getContext().getApplicationContext();

        String provider = mSettings.getProvider();
        if (!AiConstants.PROVIDER_OLLAMA.equals(provider) && !mSettings.hasApiKey()) {
            new AlertDialog.Builder(getContext())
                .setTitle(R.string.ai_test_no_key_title)
                .setMessage(R.string.ai_test_no_key_message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return;
        }

        testPreference.setEnabled(false);
        testPreference.setSummary(R.string.ai_test_running);

        mExecutor.execute(() -> {
            final AiTestResult result = AiClient.testConnection(new AiSettings(context));
            final List<String> models = result.models;

            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (getView() == null) return;
                testPreference.setEnabled(true);

                if (result.ok) {
                    testPreference.setSummary(getString(R.string.ai_test_ok, models.size()));
                    updateModelsFromResult(context, models);
                } else {
                    testPreference.setSummary(R.string.ai_test_failed);
                }

                String title = result.ok
                    ? getString(R.string.ai_test_result_title_ok)
                    : getString(R.string.ai_test_result_title_failed);
                new AlertDialog.Builder(getContext())
                    .setTitle(title)
                    .setMessage(result.message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            });
        });
    }

    /** After a successful test, merge the fetched model ids into the model list. */
    private void updateModelsFromResult(Context context, List<String> fetchedModels) {
        if (fetchedModels == null || fetchedModels.isEmpty() || mSettings == null) return;

        String provider = mSettings.getProvider();
        String key = modelKeyForProvider(provider);
        if (key == null) return;

        ListPreference modelPreference = findPreference(key);
        if (modelPreference == null) return;

        // Merge: defaults first (common models), then the fetched ones (missing only),
        // then pick the current model if present, otherwise the provider default.
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        merged.addAll(AiConstants.MODEL_OPTIONS.get(provider));
        merged.addAll(fetchedModels);

        String[] options = merged.toArray(new String[0]);
        modelPreference.setEntries(options);
        modelPreference.setEntryValues(options);

        String current = mSettings.getModel();
        if (!merged.contains(current)) {
            String defaultModel = AiConstants.DEFAULT_MODELS.get(provider);
            mSettings.setModel(merged.contains(defaultModel) ? defaultModel : options[0]);
            modelPreference.setValue(mSettings.getModel());
        } else {
            modelPreference.setValue(current);
        }
    }

    private String modelKeyForProvider(String provider) {
        switch (provider) {
            case AiConstants.PROVIDER_GEMINI: return "ai_model_gemini";
            case AiConstants.PROVIDER_OPENAI: return "ai_model_openai";
            case AiConstants.PROVIDER_ANTHROPIC: return "ai_model_anthropic";
            case AiConstants.PROVIDER_GROQ: return "ai_model_groq";
            case AiConstants.PROVIDER_OLLAMA: return "ai_model_ollama";
            default: return null;
        }
    }

    private void showHelpDialog() {
        if (getContext() == null) return;
        new AlertDialog.Builder(getContext())
            .setTitle(R.string.ai_help_title)
            .setMessage(R.string.ai_help_message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }
}
