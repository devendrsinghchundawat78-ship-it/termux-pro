package com.termux.pro.shared.ai;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Constants for the in-built AI assistant of the app.
 *
 * The AI assistant lets the user chat in natural language and optionally lets the AI
 * run real Termux shell commands (with user approval before every command).
 */
public final class AiConstants {

    private AiConstants() {}

    public static final String LOG_TAG = "AiAgent";

    // ------------------------------------------------------------------------------------------
    // Shared preference keys (stored in the default shared preferences file of the app,
    // same file that the settings screens use).
    // ------------------------------------------------------------------------------------------

    public static final String KEY_PROVIDER = "ai_provider";
    public static final String KEY_API_KEY = "ai_api_key";
    public static final String KEY_OLLAMA_URL = "ai_ollama_url";
    public static final String KEY_ASK_CONFIRMATION = "ai_ask_confirmation";
    public static final String KEY_MAX_RETRIES = "ai_max_retries";
    public static final String KEY_COMMAND_TIMEOUT = "ai_command_timeout";

    public static final String KEY_MODEL_GEMINI = "ai_model_gemini";
    public static final String KEY_MODEL_OPENAI = "ai_model_openai";
    public static final String KEY_MODEL_ANTHROPIC = "ai_model_anthropic";
    public static final String KEY_MODEL_GROQ = "ai_model_groq";
    public static final String KEY_MODEL_OLLAMA = "ai_model_ollama";

    // ------------------------------------------------------------------------------------------
    // Providers
    // ------------------------------------------------------------------------------------------

    public static final String PROVIDER_GEMINI = "gemini";
    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_ANTHROPIC = "anthropic";
    public static final String PROVIDER_GROQ = "groq";
    public static final String PROVIDER_OLLAMA = "ollama";

    /** Provider ids in the order they are shown in the settings screen. */
    public static final String[] PROVIDER_IDS = {
        PROVIDER_GEMINI, PROVIDER_OPENAI, PROVIDER_ANTHROPIC, PROVIDER_GROQ, PROVIDER_OLLAMA
    };

    /** Human readable provider names, parallel to {@link #PROVIDER_IDS}. */
    public static final String[] PROVIDER_LABELS = {
        "Google Gemini", "OpenAI", "Anthropic Claude", "Groq", "Ollama (local, no key)"
    };

    /** Default (first) model per provider. */
    public static final Map<String, String> DEFAULT_MODELS;

    /** Selectable models per provider (also refreshed from the provider after a test connection). */
    public static final Map<String, List<String>> MODEL_OPTIONS;

    /** Short note on what a key of this provider looks like (shown to the user). */
    public static final Map<String, String> KEY_HINTS;

    static {
        Map<String, String> defaultModels = new HashMap<>();
        defaultModels.put(PROVIDER_GEMINI, "gemini-2.5-flash");
        defaultModels.put(PROVIDER_OPENAI, "gpt-4o-mini");
        defaultModels.put(PROVIDER_ANTHROPIC, "claude-3-5-haiku-latest");
        defaultModels.put(PROVIDER_GROQ, "llama-3.3-70b-versatile");
        defaultModels.put(PROVIDER_OLLAMA, "llama3.2");
        DEFAULT_MODELS = Collections.unmodifiableMap(defaultModels);

        Map<String, List<String>> modelOptions = new HashMap<>();
        modelOptions.put(PROVIDER_GEMINI, Arrays.asList(
            "gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash"));
        modelOptions.put(PROVIDER_OPENAI, Arrays.asList(
            "gpt-4o-mini", "gpt-4o", "gpt-4.1-mini"));
        modelOptions.put(PROVIDER_ANTHROPIC, Arrays.asList(
            "claude-3-5-haiku-latest", "claude-3-5-sonnet-latest", "claude-haiku-4-5"));
        modelOptions.put(PROVIDER_GROQ, Arrays.asList(
            "llama-3.3-70b-versatile", "llama-3.1-8b-instant", "gemma2-9b-it"));
        modelOptions.put(PROVIDER_OLLAMA, Arrays.asList(
            "llama3.2", "llama3.1", "qwen2.5", "mistral"));
        MODEL_OPTIONS = Collections.unmodifiableMap(modelOptions);

        Map<String, String> keyHints = new HashMap<>();
        keyHints.put(PROVIDER_GEMINI, "Starts with AIza... (free from Google AI Studio)");
        keyHints.put(PROVIDER_OPENAI, "Starts with sk-... (platform.openai.com)");
        keyHints.put(PROVIDER_ANTHROPIC, "Starts with sk-ant-... (console.anthropic.com)");
        keyHints.put(PROVIDER_GROQ, "Starts with gsk_... (console.groq.com)");
        keyHints.put(PROVIDER_OLLAMA, "No key needed. Run Ollama on this device/network.");
        KEY_HINTS = Collections.unmodifiableMap(keyHints);
    }

    /** Base urls used to build provider requests. */
    public static final String BASE_URL_GEMINI = "https://generativelanguage.googleapis.com/v1beta";
    public static final String BASE_URL_OPENAI = "https://api.openai.com/v1";
    public static final String BASE_URL_ANTHROPIC = "https://api.anthropic.com/v1";
    public static final String BASE_URL_GROQ = "https://api.groq.com/openai/v1";
    public static final String DEFAULT_OLLAMA_URL = "http://127.0.0.1:11434";

    // ------------------------------------------------------------------------------------------
    // Request / behaviour defaults
    // ------------------------------------------------------------------------------------------

    /** Default timeout (seconds) after which a command run by the AI is killed. */
    public static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 240;

    /** Default maximum number of times the AI is asked to fix one failed command. */
    public static final int DEFAULT_MAX_RETRIES = 3;

    /** Maximum number of steps the AI may plan for one task. */
    public static final int MAX_PLAN_STEPS = 8;

    /** Maximum characters of command output that are sent back to the model. */
    public static final int MAX_OUTPUT_CHARS_FOR_MODEL = 12000;

    /** Maximum characters of command output shown in the session view. */
    public static final int MAX_OUTPUT_CHARS_FOR_VIEW = 6000;

    /** Tokens requested from models for one reply. */
    public static final int DEFAULT_MAX_TOKENS = 4096;

    /** Connect timeout for HTTP calls. */
    public static final int HTTP_CONNECT_TIMEOUT_MS = 20000;

    /** Read timeout for HTTP calls (model replies can be slow). */
    public static final int HTTP_READ_TIMEOUT_MS = 180000;

    /**
     * Patterns that force an approval dialog even when auto-run is enabled.
     * A command is considered dangerous if it contains any of these substrings.
     */
    public static final List<String> DANGEROUS_PATTERNS = Arrays.asList(
        "rm -rf", "rm -fr",
        "mkfs.", "fdisk", "dd if=", "dd of=",
        "/dev/sd", "/dev/block",
        ":(){", ":() {",
        "shutdown", "reboot", "halt", "poweroff",
        "chmod -R 777 /", "chown -R", "mv /", "> /dev/",
        "sudo ", "curl -s http", "wget http", "bash <(", "curl | sh", "| sh", "| bash"
    );

    /** Whether a command should always trigger a confirmation. */
    public static boolean isPotentiallyDangerous(String command) {
        if (command == null) return false;
        String c = command.trim().toLowerCase();
        for (String pattern : DANGEROUS_PATTERNS) {
            if (c.contains(pattern)) return true;
        }
        return false;
    }
}
