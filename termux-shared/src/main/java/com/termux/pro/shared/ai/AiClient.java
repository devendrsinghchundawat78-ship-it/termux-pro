package com.termux.pro.shared.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to the supported AI providers (Google Gemini, OpenAI, Anthropic Claude, Groq,
 * Ollama) using their chat-completion style HTTP APIs.
 *
 * Every error is converted into a {@link AiClient.AiException} whose message is written in
 * simple language and tells the user what to do (fix the key, change the model, check
 * the internet, wait for the rate limit, ...) instead of a raw stack trace.
 */
public final class AiClient {

    private AiClient() {}

    /** One chat message exchanged with a model. */
    public static final class AiMessage {
        public final String role;   // "user", "assistant" or "system"
        public final String content;

        public AiMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    /** Error carrying a friendly message + optional HTTP status. */
    public static final class AiException extends Exception {
        public final int httpStatus;

        public AiException(String message) {
            super(message);
            this.httpStatus = 0;
        }

        public AiException(String message, int httpStatus) {
            super(message);
            this.httpStatus = httpStatus;
        }
    }

    /** Result of "Test Connection". */
    public static final class AiTestResult {
        public final boolean ok;
        public final String message;
        public final List<String> models; // may be empty

        public AiTestResult(boolean ok, String message, List<String> models) {
            this.ok = ok;
            this.message = message;
            this.models = models == null ? new ArrayList<String>() : models;
        }
    }

    // ----------------------------------------------------------------------------------
    // Chat
    // ----------------------------------------------------------------------------------

    /**
     * Send a chat request to the configured provider.
     *
     * @param systemPrompt optional system prompt prepended for providers that support it
     * @param history the conversation (user/assistant messages, newest last)
     * @return the reply text of the model
     */
    public static String chat(@NonNull com.termux.pro.shared.ai.AiSettings settings,
                              @Nullable String systemPrompt,
                              @NonNull List<AiMessage> history) throws AiException {

        String provider = settings.getProvider();
        String model = settings.getModel();
        String apiKey = settings.getApiKey();

        switch (provider) {
            case AiConstants.PROVIDER_GEMINI: return chatGemini(model, apiKey, systemPrompt, history);
            case AiConstants.PROVIDER_OPENAI: return chatOpenAiStyle(
                AiConstants.BASE_URL_OPENAI + "/chat/completions", model, apiKey, systemPrompt, history, "openai");
            case AiConstants.PROVIDER_GROQ: return chatOpenAiStyle(
                AiConstants.BASE_URL_GROQ + "/chat/completions", model, apiKey, systemPrompt, history, "groq");
            case AiConstants.PROVIDER_ANTHROPIC: return chatAnthropic(model, apiKey, systemPrompt, history);
            case AiConstants.PROVIDER_OLLAMA: return chatOllama(settings, model, systemPrompt, history);
            default:
                throw new AiException("Unknown AI provider configured in Settings.");
        }
    }

    private static String chatGemini(String model, String apiKey, String systemPrompt,
                                     List<AiMessage> history) throws AiException {
        try {
            JSONObject body = new JSONObject();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                body.put("system_instruction", new JSONObject()
                    .put("parts", new JSONArray().put(new JSONObject().put("text", systemPrompt))));
            }
            JSONArray contents = new JSONArray();
            for (AiMessage message : history) {
                if ("system".equals(message.role)) continue;
                String geminiRole = "assistant".equals(message.role) ? "model" : "user";
                contents.put(new JSONObject()
                    .put("role", geminiRole)
                    .put("parts", new JSONArray().put(new JSONObject().put("text", message.content))));
            }
            if (contents.length() == 0)
                contents.put(new JSONObject()
                    .put("role", "user")
                    .put("parts", new JSONArray().put(new JSONObject().put("text", "Hello"))));
            body.put("contents", contents);
            body.put("generationConfig", new JSONObject()
                .put("maxOutputTokens", AiConstants.DEFAULT_MAX_TOKENS)
                .put("temperature", 0.3));

            String url = AiConstants.BASE_URL_GEMINI + "/models/" + model + ":generateContent?key="
                + urlEncode(apiKey);
            AiHttp.Response response = AiHttp.request("POST", url, null, body.toString());
            if (!response.isOk())
                throw buildError(response.statusCode, response.body, "gemini");

            JSONObject json = new JSONObject(response.body);
            JSONArray candidates = json.optJSONArray("candidates");
            if (candidates != null && candidates.length() > 0) {
                StringBuilder text = new StringBuilder();
                JSONArray parts = candidates.getJSONObject(0).optJSONObject("content") != null
                    ? candidates.getJSONObject(0).getJSONObject("content").optJSONArray("parts")
                    : null;
                if (parts != null) {
                    for (int i = 0; i < parts.length(); i++) {
                        String partText = parts.getJSONObject(i).optString("text", null);
                        if (partText != null) text.append(partText);
                    }
                }
                if (text.length() > 0) return text.toString().trim();
            }
            throw new AiException("The model returned an empty response. Try again or pick a different model.");
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Failed to talk to Gemini: " + e.getMessage());
        }
    }

    private static String chatOpenAiStyle(String url, String model, String apiKey, String systemPrompt,
                                          List<AiMessage> history, String providerName) throws AiException {
        try {
            JSONArray messages = new JSONArray();
            if (systemPrompt != null && !systemPrompt.isEmpty())
                messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
            for (AiMessage message : history) {
                if ("system".equals(message.role)) continue;
                messages.put(new JSONObject().put("role", message.role).put("content", message.content));
            }

            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", messages);
            body.put("max_tokens", AiConstants.DEFAULT_MAX_TOKENS);
            body.put("temperature", 0.3);

            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + apiKey);
            AiHttp.Response response = AiHttp.request("POST", url, headers, body.toString());
            if (!response.isOk())
                throw buildError(response.statusCode, response.body, providerName);

            JSONObject json = new JSONObject(response.body);
            JSONArray choices = json.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                String content = choices.getJSONObject(0).optJSONObject("message") != null
                    ? choices.getJSONObject(0).getJSONObject("message").optString("content", null)
                    : null;
                if (content != null && !content.trim().isEmpty())
                    return content.trim();
            }
            throw new AiException("The model returned an empty response. Try again or pick a different model.");
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Failed to talk to the provider: " + e.getMessage());
        }
    }

    private static String chatAnthropic(String model, String apiKey, String systemPrompt,
                                        List<AiMessage> history) throws AiException {
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("max_tokens", AiConstants.DEFAULT_MAX_TOKENS);
            body.put("temperature", 0.3);
            if (systemPrompt != null && !systemPrompt.isEmpty())
                body.put("system", systemPrompt);

            JSONArray messages = new JSONArray();
            for (AiMessage message : history) {
                if ("system".equals(message.role)) continue;
                messages.put(new JSONObject().put("role", message.role).put("content", message.content));
            }
            body.put("messages", messages);

            Map<String, String> headers = new HashMap<>();
            headers.put("x-api-key", apiKey);
            headers.put("anthropic-version", "2023-06-01");
            AiHttp.Response response = AiHttp.request("POST", AiConstants.BASE_URL_ANTHROPIC + "/messages",
                headers, body.toString());
            if (!response.isOk())
                throw buildError(response.statusCode, response.body, "anthropic");

            JSONObject json = new JSONObject(response.body);
            JSONArray content = json.optJSONArray("content");
            if (content != null) {
                StringBuilder text = new StringBuilder();
                for (int i = 0; i < content.length(); i++) {
                    JSONObject part = content.getJSONObject(i);
                    if ("text".equals(part.optString("type")) && part.has("text"))
                        text.append(part.optString("text"));
                }
                if (text.length() > 0) return text.toString().trim();
            }
            throw new AiException("The model returned an empty response. Try again or pick a different model.");
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Failed to talk to Claude: " + e.getMessage());
        }
    }

    private static String chatOllama(AiSettings settings, String model, String systemPrompt,
                                     List<AiMessage> history) throws AiException {
        try {
            String baseUrl = settings.getOllamaUrl();
            while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);

            JSONArray messages = new JSONArray();
            if (systemPrompt != null && !systemPrompt.isEmpty())
                messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
            for (AiMessage message : history) {
                if ("system".equals(message.role)) continue;
                messages.put(new JSONObject().put("role", message.role).put("content", message.content));
            }

            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", messages);
            body.put("stream", false);
            body.put("options", new JSONObject().put("num_predict", AiConstants.DEFAULT_MAX_TOKENS));

            AiHttp.Response response = AiHttp.request("POST", baseUrl + "/api/chat", null, body.toString());
            if (!response.isOk())
                throw buildError(response.statusCode, response.body, "ollama");

            JSONObject json = new JSONObject(response.body);
            String content = json.optJSONObject("message") != null
                ? json.getJSONObject("message").optString("content", null)
                : null;
            if (content != null && !content.trim().isEmpty())
                return content.trim();
            throw new AiException("Ollama returned an empty response. Is the model pulled? Try `ollama pull <model>` in Termux.");
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Failed to talk to Ollama: " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------------------------
    // Test connection
    // ----------------------------------------------------------------------------------

    /**
     * Verifies the stored key/connection and fetches the list of models the account can use.
     * Never throws - problems are reported through the returned {@link AiTestResult}.
     */
    public static AiTestResult testConnection(AiSettings settings) {
        String provider = settings.getProvider();
        try {
            List<String> models = fetchModels(settings);
            String detail = models.isEmpty() ? "Connection OK."
                : "Connection OK. " + models.size() + " model(s) available - pick one below.";
            return new AiTestResult(true, detail, models);
        } catch (AiException e) {
            return new AiTestResult(false, e.getMessage(), new ArrayList<String>());
        }
    }

    private static List<String> fetchModels(AiSettings settings) throws AiException {
        String provider = settings.getProvider();
        switch (provider) {
            case AiConstants.PROVIDER_GEMINI: return fetchModelsGemini(settings.getApiKey());
            case AiConstants.PROVIDER_OPENAI: return fetchModelsBearer(
                AiConstants.BASE_URL_OPENAI + "/models", settings.getApiKey(), "data", "id");
            case AiConstants.PROVIDER_GROQ: return fetchModelsBearer(
                AiConstants.BASE_URL_GROQ + "/models", settings.getApiKey(), "data", "id");
            case AiConstants.PROVIDER_ANTHROPIC: return fetchModelsAnthropic(settings.getApiKey());
            case AiConstants.PROVIDER_OLLAMA: return fetchModelsOllama(settings.getOllamaUrl());
            default: return new ArrayList<>();
        }
    }

    private static String fetchModelsGemini(String apiKey) throws AiException {
        String url = AiConstants.BASE_URL_GEMINI + "/models?key=" + urlEncode(apiKey);
        AiHttp.Response response = AiHttp.request("GET", url, null, null);
        if (!response.isOk()) throw buildError(response.statusCode, response.body, "gemini");
        List<String> models = new ArrayList<>();
        try {
            JSONArray arr = new JSONObject(response.body).optJSONArray("models");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String name = arr.getJSONObject(i).optString("name", "");
                    if (name.startsWith("models/")) name = name.substring("models/".length());
                    if (name.contains("gemini") && !name.contains("embedding"))
                        models.add(name);
                }
            }
        } catch (Exception ignored) {}
        return models;
    }

    private static List<String> fetchModelsBearer(String url, String apiKey, String arrKey, String idKey) throws AiException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        AiHttp.Response response = AiHttp.request("GET", url, headers, null);
        if (!response.isOk()) throw buildError(response.statusCode, response.body, "bearer");
        return parseIdList(response.body, arrKey, idKey);
    }

    private static List<String> fetchModelsAnthropic(String apiKey) throws AiException {
        Map<String, String> headers = new HashMap<>();
        headers.put("x-api-key", apiKey);
        headers.put("anthropic-version", "2023-06-01");
        AiHttp.Response response = AiHttp.request("GET", AiConstants.BASE_URL_ANTHROPIC + "/models", headers, null);
        if (!response.isOk()) throw buildError(response.statusCode, response.body, "anthropic");
        return parseIdList(response.body, "data", "id");
    }

    private static List<String> fetchModelsOllama(String baseUrl) throws AiException {
        while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        AiHttp.Response response = AiHttp.request("GET", baseUrl + "/api/tags", null, null);
        if (!response.isOk()) throw buildError(response.statusCode, response.body, "ollama");
        List<String> models = new ArrayList<>();
        try {
            JSONArray arr = new JSONObject(response.body).optJSONArray("models");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String name = arr.getJSONObject(i).optString("name", "");
                    if (!name.isEmpty()) models.add(name);
                }
            }
        } catch (Exception ignored) {}
        return models;
    }

    private static List<String> parseIdList(String body, String arrKey, String idKey) {
        List<String> models = new ArrayList<>();
        try {
            JSONArray arr = new JSONObject(body).optJSONArray(arrKey);
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String id = arr.getJSONObject(i).optString(idKey, "");
                    if (!id.isEmpty()) models.add(id);
                }
            }
        } catch (Exception ignored) {}
        return models;
    }

    // ----------------------------------------------------------------------------------
    // Friendly error building
    // ----------------------------------------------------------------------------------

    /** Turns a non-2xx provider response into a message the user can act on. */
    private static AiException buildError(int statusCode, String body, String provider) {
        String detail = extractErrorMessage(body);
        String message;

        switch (statusCode) {
            case 400:
                if (detail != null && detail.toLowerCase().contains("api key"))
                    message = "Your API key looks wrong. " + niceDetail(detail);
                else if (detail != null && detail.toLowerCase().contains("model"))
                    message = "The model \"" + detail + "\" is not available. Pick a model from the updated list.";
                else
                    message = "The provider rejected the request (HTTP 400). " + niceDetail(detail);
                break;
            case 401:
            case 403:
                message = "Your API key is invalid, expired, or not allowed for this request (HTTP " + statusCode
                    + "). Please check your key in Settings -> AI Assistant.";
                break;
            case 404:
                message = "The model was not found (HTTP 404). Pick a model from the updated list.";
                break;
            case 429:
                message = "Too many requests (rate limit reached). Wait a little while or check your plan quota.";
                break;
            default:
                if (statusCode >= 500)
                    message = "The provider server had a problem (HTTP " + statusCode + "). Please try again.";
                else
                    message = "Provider error (HTTP " + statusCode + "). " + niceDetail(detail);
                break;
        }

        return new AiException(message, statusCode);
    }

    private static String niceDetail(String detail) {
        return detail == null || detail.isEmpty() ? "Try again." : "Details: " + detail;
    }

    /** URL-encode a value for query parameters (never fails). */
    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    /** Try to read the human readable error text from common provider error payloads. */
    private static String extractErrorMessage(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            JSONObject json = new JSONObject(body);
            // OpenAI / Groq / Anthropic / Gemini style: { "error": { "message": ... } }
            JSONObject error = json.optJSONObject("error");
            if (error != null) {
                String m = error.optString("message", "");
                if (!m.isEmpty()) return m;
                String t = error.optString("type", "");
                if (!t.isEmpty()) return t;
            }
            // Ollama style: { "error": "..." }
            String direct = json.optString("error", "");
            if (!direct.isEmpty()) return direct;
            // Gemini sometimes puts it in error.message nested deeper - already covered above.
        } catch (Exception ignored) {}
        String cleaned = body.replaceAll("\\s+", " ").trim();
        return cleaned.length() > 200 ? cleaned.substring(0, 200) + "..." : cleaned;
    }
}
