package com.termux.pro.shared.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.termux.pro.shared.ai.AiClient.AiException;
import com.termux.pro.shared.ai.AiClient.AiMessage;
import com.termux.pro.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.pro.shared.shell.command.ExecutionCommand;
import com.termux.pro.shared.shell.command.ExecutionCommand.Runner;
import com.termux.pro.shared.shell.command.runner.app.AppShell;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The AI agent that powers the AI session.
 *
 * Given a natural language task it:
 *  1. asks the model to split the task into small shell command steps (JSON plan),
 *  2. asks the user to approve every command (always, by default),
 *  3. runs the command inside Termux (through the same {@link AppShell} machinery that
 *     Termux itself uses for background commands),
 *  4. reads the real output/exit code,
 *  5. on failure, asks the model to analyse the error and propose a fixed command,
 *     retrying a configurable number of times,
 *  6. finally asks the model for a short summary of what was done.
 *
 * The user can stop the agent at any time (Stop button), and every command is logged
 * in the session view, so the user always knows exactly what the AI is doing.
 */
public final class AiAgentEngine {

    /** Callback to approve or deny a single command (implemented by the UI on the main thread). */
    public interface ApprovalCallback {
        void allow();
        void deny();
        void stopAll();
    }

    /** Listener for UI events. All callbacks are delivered on the main thread. */
    public interface Listener {
        void onAiStatus(String text);
        void onAiMessage(String text);
        void onAiError(String text);
        void onCommandStart(String command, String description, int stepIndex, int stepTotal);
        void onCommandOutput(String output);
        void onApprovalRequested(String command, String description, boolean risky, ApprovalCallback callback);
        void onFinished(String summary);
    }

    // ------------------------------------------------------------------------------------------

    private static final int APPROVAL_UNSET = 0;
    private static final int APPROVAL_ALLOW = 1;
    private static final int APPROVAL_DENY = 2;
    private static final int APPROVAL_STOP = 3;

    private final Context mContext;
    private final AiSettings mSettings;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private Listener mListener;
    private volatile boolean mStopRequested;
    private volatile boolean mBusy;
    private volatile AppShell mActiveShell;
    private volatile CountDownLatch mActiveLatch;
    private volatile int mApprovalDecision = APPROVAL_UNSET;

    public AiAgentEngine(Context context) {
        mContext = context.getApplicationContext();
        mSettings = new AiSettings(context);
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    public boolean isBusy() {
        return mBusy;
    }

    /** Release the background worker. Call when the UI is destroyed for good. */
    public void release() {
        requestStop();
        mExecutor.shutdownNow();
    }

    /** Ask the agent to stop. Safe to call from any thread / at any moment. */
    public void requestStop() {
        mStopRequested = true;
        CountDownLatch latch = mActiveLatch;
        if (latch != null) {
            mApprovalDecision = APPROVAL_STOP;
            latch.countDown();
        }
        AppShell shell = mActiveShell;
        if (shell != null) shell.kill();
    }

    /**
     * Start working on a task. The whole conversation happens in the background and the
     * UI is updated through the {@link Listener}. If the engine is already busy the task
     * is ignored (the UI should prevent this anyway).
     */
    public void runTask(@NonNull final String userTask) {
        if (mBusy || userTask == null || userTask.trim().isEmpty()) return;
        mBusy = true;
        mStopRequested = false;

        final String task = userTask.trim();
        postStatus("Starting AI agent for: \"" + task + "\"");
        postStatus("Thinking...");

        mExecutor.execute(() -> {
            try {
                runTaskInternal(task);
            } catch (Exception e) {
                postError("Unexpected error: " + e.getMessage());
                postStatus("Agent stopped.");
            } finally {
                mBusy = false;
            }
        });
    }

    // ==================================================================================
    // Core agent loop
    // ==================================================================================

    private void runTaskInternal(String task) throws Exception {
        // 1. Plan
        List<AiMessage> history = new ArrayList<>();
        history.add(new AiMessage("user", task));

        String planReply = chatWithRetry(SYSTEM_AGENT_PROMPT, history);
        Plan plan = parsePlan(planReply);

        if (mStopRequested) { finish("Stopped by user."); return; }

        if (plan.answer != null) {
            // No commands needed - plain answer.
            postMessage(plan.answer);
            finish("Task finished.");
            return;
        }

        history.add(new AiMessage("assistant", trimTo(planReply, 8000)));
        postMessage("Plan: " + plan.steps.size() + " step(s)");

        // 2. Execute steps
        StringBuilder digest = new StringBuilder();
        List<String> completedCommands = new ArrayList<>();
        boolean allSucceeded = true;

        for (int i = 0; i < plan.steps.size() && !mStopRequested; i++) {
            Step step = plan.steps.get(i);
            if (isEmpty(step.cmd)) continue;

            digest.append("- ").append(step.cmd).append("\n");
            postCommandStart(step.cmd, step.desc, i + 1, plan.steps.size());

            boolean success = false;
            boolean dangerous = AiConstants.isPotentiallyDangerous(step.cmd);

            // Ask for approval of the first attempt
            if (mSettings.shouldAskConfirmation() || dangerous) {
                int decision = requestApproval(step.cmd, step.desc, dangerous);
                if (decision == APPROVAL_STOP) { finish("Stopped by user."); return; }
                if (decision == APPROVAL_DENY) {
                    postStatus("Skipped (not approved by user): " + step.cmd);
                    digest.append("  - skipped by user\n");
                    completedCommands.add(step.cmd + "  [skipped]");
                    continue;
                }
            }

            RunResult result = runCommand(step.cmd);
            success = result.exitCode != null && result.exitCode == 0 && !result.timedOut;

            if (success) {
                digest.append("  - ok\n");
                completedCommands.add(step.cmd + "  [ok]");
                postStatus("Step " + (i + 1) + "/" + plan.steps.size() + " succeeded (exit " + result.exitCode + ")");
            } else {
                // 3. Error recovery loop
                digest.append("  - failed (exit ")
                    .append(result.exitCode == null ? "?" : result.exitCode)
                    .append(result.timedOut ? ", timed out" : "").append(")\n");
                completedCommands.add(step.cmd + "  [failed]");

                boolean fixed = false;
                int maxRetries = mSettings.getMaxRetries();
                for (int retry = 1; retry <= maxRetries && !mStopRequested; retry++) {
                    postStatus("Command failed - asking AI to fix it (attempt " + retry + "/" + maxRetries + ")...");

                    String modelInput = "A command I ran failed.\n"
                        + "COMMAND:\n" + step.cmd + "\n\n"
                        + "EXIT CODE: " + (result.exitCode == null ? "n/a" : result.exitCode)
                        + (result.timedOut ? " (command timed out)" : "") + "\n\n"
                        + "OUTPUT (last " + AiConstants.MAX_OUTPUT_CHARS_FOR_MODEL + " chars):\n"
                        + tail(result.combinedForModel(), AiConstants.MAX_OUTPUT_CHARS_FOR_MODEL) + "\n\n"
                        + "Reply with ONE fixed command as JSON: {\"cmd\":\"...\",\"desc\":\"...\"} or a plain text explanation if it cannot be fixed with a command.";

                    List<AiMessage> fixHistory = new ArrayList<>(history);
                    fixHistory.add(new AiMessage("user", modelInput));
                    trimHistory(fixHistory);

                    String fixReply = chatWithRetry(SYSTEM_FIXER_PROMPT, fixHistory);
                    history = fixHistory;
                    history.add(new AiMessage("assistant", trimTo(fixReply, 4000)));

                    Plan fixPlan = parseSingleFix(fixReply);
                    if (fixPlan.answer != null) {
                        postMessage(fixPlan.answer);
                        break;
                    }
                    if (fixPlan.steps.isEmpty()) break;

                    Step fixStep = fixPlan.steps.get(0);
                    if (isEmpty(fixStep.cmd)) continue;

                    postStatus("AI fix proposal: " + fixStep.cmd);

                    if (mSettings.shouldAskConfirmation() || AiConstants.isPotentiallyDangerous(fixStep.cmd)) {
                        int decision = requestApproval(fixStep.cmd, fixStep.desc, AiConstants.isPotentiallyDangerous(fixStep.cmd));
                        if (decision == APPROVAL_STOP) { finish("Stopped by user."); return; }
                        if (decision == APPROVAL_DENY) {
                            postStatus("Fix skipped (not approved).");
                            break;
                        }
                    }

                    RunResult fixResult = runCommand(fixStep.cmd);
                    if (fixResult.exitCode != null && fixResult.exitCode == 0 && !fixResult.timedOut) {
                        postStatus("Fix worked (exit 0).");
                        digest.append("  - fixed with: ").append(fixStep.cmd).append("\n");
                        fixed = true;
                        break;
                    } else {
                        result = fixResult; // continue analysing the new error
                    }
                }

                if (!fixed && !mStopRequested) {
                    postError("Could not fix step after " + maxRetries + " attempt(s). Aborting remaining steps.");
                    allSucceeded = false;
                    break;
                }
            }
        }

        if (mStopRequested) { finish("Stopped by user."); return; }

        // 4. Summary
        postStatus("Summarising result...");
        String summary;
        try {
            List<AiMessage> summaryHistory = new ArrayList<>();
            summaryHistory.add(new AiMessage("user",
                "Task: " + task + "\n\nExecuted steps:\n" + digest.toString()
                    + "\nGive a short summary (max 3 lines) of what happened."));
            summary = chatWithRetry(SYSTEM_SUMMARY_PROMPT, summaryHistory);
        } catch (AiException e) {
            summary = allSucceeded ? "Task finished. Review the output above." : "Task did not fully succeed. Review the output above.";
        }

        postMessage(summary);
        finish(allSucceeded ? "Task finished." : "Task ended with problems - see messages above.");
    }

    // ==================================================================================
    // Model calls
    // ==================================================================================

    private String chat(String systemPrompt, List<AiMessage> history) throws AiException {
        return AiClient.chat(mSettings, systemPrompt, history);
    }

    /** Chat with one automatic retry after a short pause (flaky networks happen). */
    private String chatWithRetry(String systemPrompt, List<AiMessage> history) throws AiException {
        try {
            return chat(systemPrompt, history);
        } catch (AiException e) {
            postStatus("Request problem: " + e.getMessage());
            postStatus("Retrying once...");
            try {
                Thread.sleep(2500);
            } catch (InterruptedException ie) {
                throw e;
            }
            if (mStopRequested) throw e;
            return chat(systemPrompt, history);
        }
    }

    // ==================================================================================
    // Approval
    // ==================================================================================

    /** Blocking call (worker thread): asks the UI to approve a command. */
    private int requestApproval(final String command, final String description, final boolean risky) {
        final CountDownLatch latch = new CountDownLatch(1);
        mActiveLatch = latch;
        mApprovalDecision = APPROVAL_UNSET;

        postToMain(() -> {
            if (mListener != null) {
                mListener.onApprovalRequested(command, description, risky, new ApprovalCallback() {
                    @Override public void allow() { mApprovalDecision = APPROVAL_ALLOW; latch.countDown(); }
                    @Override public void deny() { mApprovalDecision = APPROVAL_DENY; latch.countDown(); }
                    @Override public void stopAll() { mApprovalDecision = APPROVAL_STOP; mStopRequested = true; latch.countDown(); }
                });
            } else {
                mApprovalDecision = APPROVAL_ALLOW; // no UI attached - keep going
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            return APPROVAL_DENY;
        } finally {
            mActiveLatch = null;
        }
        return mApprovalDecision;
    }

    // ==================================================================================
    // Command execution (via the same AppShell used by Termux background commands)
    // ==================================================================================

    private static final class RunResult {
        Integer exitCode;
        boolean timedOut;
        final StringBuilder stdout = new StringBuilder();
        final StringBuilder stderr = new StringBuilder();
        String startError;

        String combinedForModel() {
            if (startError != null) return startError;
            if (stdout.length() == 0 && stderr.length() == 0) return "";
            if (stderr.length() == 0) return stdout.toString();
            return stdout.toString() + "\n[stderr]\n" + stderr.toString();
        }

        String combinedForView() {
            if (startError != null) return startError;
            if (stdout.length() == 0 && stderr.length() == 0) return "(no output)";
            if (stderr.length() == 0) return stdout.toString();
            return stdout.toString() + "\n" + stderr.toString();
        }
    }

    private RunResult runCommand(String command) {
        RunResult result = new RunResult();

        // Resolve the Termux environment (PREFIX, HOME, PATH, ...) the same way Termux does.
        HashMap<String, String> environment = new TermuxShellEnvironment().getEnvironment(mContext, false);
        String prefix = environment.get("PREFIX");
        String home = environment.get("HOME");
        if (isEmpty(prefix))
            prefix = mContext.getFilesDir().getAbsolutePath() + "/usr"; // .../files/usr
        if (isEmpty(home))
            home = "/data/data/" + mContext.getPackageName() + "/files/home";

        String shellPath = prefix + "/bin/bash";

        ExecutionCommand executionCommand = new ExecutionCommand();
        executionCommand.executable = shellPath;
        executionCommand.arguments = new String[]{"-c", command};
        executionCommand.runner = Runner.APP_SHELL.getName();
        executionCommand.workingDirectory = home;
        executionCommand.commandLabel = "ai-command";

        final CountDownLatch exitLatch = new CountDownLatch(1);
        mActiveLatch = exitLatch;

        final AppShell appShell;
        try {
            appShell = AppShell.execute(mContext, executionCommand,
                shell -> exitLatch.countDown(),
                new TermuxShellEnvironment(), null, false);
        } catch (Exception e) {
            result.startError = "Failed to start the command process: " + e.getMessage();
            postCommandOutput("$ " + command + "\n" + result.startError);
            mActiveLatch = null;
            return result;
        }

        if (appShell == null) {
            // Starting failed (e.g. bootstrap not installed yet) - AppShell already recorded state.
            result.startError = "Could not start \"" + command
                + "\". Make sure the Termux base packages are installed (open a normal terminal session once).";
            postCommandOutput("$ " + command + "\n" + result.startError);
            mActiveLatch = null;
            return result;
        }

        mActiveShell = appShell;
        long timeoutMs = mSettings.getCommandTimeoutMillis();
        long start = System.currentTimeMillis();
        boolean finished = false;
        boolean timedOut = false;

        try {
            while (true) {
                if (exitLatch.await(10, TimeUnit.SECONDS)) {
                    finished = true;
                    break;
                }
                long elapsed = System.currentTimeMillis() - start;
                if (elapsed >= timeoutMs) {
                    timedOut = true;
                    appShell.kill();
                    exitLatch.await(4, TimeUnit.SECONDS);
                    break;
                }
                postStatus("Command still running (" + (elapsed / 1000) + "s)...");
            }
        } catch (InterruptedException e) {
            timedOut = true;
            appShell.kill();
        } finally {
            mActiveShell = null;
            mActiveLatch = null;
        }

        // Wait a moment for the output gobblers to finish after a kill.
        if (timedOut || !finished) {
            long waited = 0;
            while (waited < 5000 && executionCommand.isExecuting()) {
                try {
                    Thread.sleep(100);
                    waited += 100;
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        // Copy results (thread safe enough: gobblers are done at this point).
        if (executionCommand.resultData.stdout != null)
            result.stdout.append(executionCommand.resultData.stdout);
        if (executionCommand.resultData.stderr != null)
            result.stderr.append(executionCommand.resultData.stderr);
        result.exitCode = executionCommand.resultData.exitCode;
        if (timedOut) result.exitCode = null; // hide confusing 137 exit code

        result.timedOut = timedOut;

        // Show the output in the session view.
        String viewOutput = tail(result.combinedForView(), AiConstants.MAX_OUTPUT_CHARS_FOR_VIEW);
        if (!isEmpty(viewOutput))
            postCommandOutput("$ " + command + "\n" + viewOutput);
        if (timedOut)
            postStatus("Command timed out after " + (timeoutMs / 1000) + "s and was stopped.");
        return result;
    }

    // ==================================================================================
    // Event posting (always on the main thread)
    // ==================================================================================

    private void postToMain(Runnable runnable) {
        mMainHandler.post(runnable);
    }

    private void postStatus(String text) {
        postToMain(() -> { if (mListener != null) mListener.onAiStatus(text); });
    }

    private void postMessage(String text) {
        postToMain(() -> { if (mListener != null) mListener.onAiMessage(text); });
    }

    private void postError(String text) {
        postToMain(() -> { if (mListener != null) mListener.onAiError(text); });
    }

    private void postCommandStart(String cmd, String desc, int index, int total) {
        postToMain(() -> {
            if (mListener != null) mListener.onCommandStart(cmd, desc, index, total);
        });
    }

    private void postCommandOutput(String output) {
        postToMain(() -> { if (mListener != null) mListener.onCommandOutput(output); });
    }

    private void finish(String summary) {
        postToMain(() -> { if (mListener != null) mListener.onFinished(summary); });
    }

    // ==================================================================================
    // Plan parsing helpers
    // ==================================================================================

    private static final class Step {
        final String cmd;
        final String desc;

        Step(String cmd, String desc) {
            this.cmd = cmd == null ? "" : cmd.trim();
            this.desc = desc == null ? "" : desc;
        }
    }

    private static final class Plan {
        List<Step> steps = new ArrayList<>();
        String answer; // non-null when the model replied with plain text
    }

    /** Parse the "steps" plan. Returns answer-only plans when no JSON was found. */
    private static Plan parsePlan(String reply) {
        Plan plan = new Plan();
        if (isEmpty(reply)) { plan.answer = "The model gave an empty answer."; return plan; }

        String json = extractJson(reply);
        if (json == null) {
            plan.answer = reply.trim();
            return plan;
        }

        try {
            JSONObject object = new JSONObject(json);
            JSONArray steps = object.optJSONArray("steps");
            if (steps != null) {
                for (int i = 0; i < steps.length() && plan.steps.size() < AiConstants.MAX_PLAN_STEPS; i++) {
                    JSONObject step = steps.optJSONObject(i);
                    if (step != null) {
                        String cmd = step.optString("cmd", "");
                        if (!cmd.trim().isEmpty())
                            plan.steps.add(new Step(cmd, step.optString("desc", "")));
                    }
                }
            }
        } catch (Exception ignored) {}

        if (plan.steps.isEmpty())
            plan.answer = reply.trim(); // not a usable plan - show it as text

        return plan;
    }

    /** Parse a fixer reply that should contain exactly one command. */
    private static Plan parseSingleFix(String reply) {
        Plan plan = new Plan();
        if (isEmpty(reply)) { plan.answer = "The model gave an empty fix."; return plan; }

        String json = extractJson(reply);
        if (json == null) {
            plan.answer = reply.trim();
            return plan;
        }

        try {
            JSONObject object = new JSONObject(json);
            JSONArray steps = object.optJSONArray("steps");
            if (steps != null && steps.length() > 0) {
                JSONObject step = steps.optJSONObject(0);
                if (step != null && !step.optString("cmd", "").trim().isEmpty())
                    plan.steps.add(new Step(step.optString("cmd"), step.optString("desc", "")));
            } else if (!object.optString("cmd", "").trim().isEmpty()) {
                plan.steps.add(new Step(object.optString("cmd"), object.optString("desc", "")));
            }
        } catch (Exception ignored) {}

        if (plan.steps.isEmpty())
            plan.answer = reply.trim();
        return plan;
    }

    /** Extract the first balanced {...} JSON object from a reply (skips ```json fences). */
    private static String extractJson(String text) {
        String cleaned = text.replace("```json", "").replace("```", "");
        int firstBrace = cleaned.indexOf('{');
        int lastBrace = cleaned.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace)
            return cleaned.substring(firstBrace, lastBrace + 1);
        return null;
    }

    private static String tail(String text, int max) {
        if (text == null || text.length() <= max) return text == null ? "" : text;
        return "…[truncated]…\n" + text.substring(text.length() - max);
    }

    private static String trimTo(String text, int max) {
        if (text == null || text.length() <= max) return text == null ? "" : text;
        return text.substring(0, max) + "…";
    }

    private static boolean isEmpty(String text) {
        return text == null || text.trim().isEmpty();
    }

    /** Keep the first message (the user task) and the 5 newest messages. */
    private static void trimHistory(List<AiMessage> history) {
        while (history.size() > 6) {
            history.remove(1);
        }
    }

    // ==================================================================================
    // Prompts
    // ==================================================================================

    private static final String SYSTEM_AGENT_PROMPT =
        "You are TermuxAI, an AI assistant that helps the user inside Termux, a Linux terminal app on Android.\n"
            + "The user will give you tasks. When a task needs commands, you plan shell commands.\n\n"
            + "Environment notes:\n"
            + "- Termux has a real Linux userland but NO root by default. Never use 'sudo'.\n"
            + "- Install software with:  pkg install -y <package>   (never apt-get)\n"
            + "- The home directory is ~. Files live under ~.\n"
            + "- Commands run non-interactively. Never start programs that keep running forever or need a keyboard (vim, nano, top, htop, python REPL, ssh, ping without -c, tail -f). Use flags so commands exit, e.g. 'timeout 10 ping -c 3 host'.\n"
            + "- Package downloads may take a while; that is normal.\n"
            + "- If you need to write a small script/file, use printf or heredocs through bash.\n\n"
            + "RULES when the task needs commands:\n"
            + "1. Reply with ONLY a JSON object:\n"
            + "   {\"steps\":[{\"cmd\":\"the shell command\",\"desc\":\"short description\"}]}\n"
            + "2. Break work into small sequential steps. Max 8 steps.\n"
            + "3. Each step is one self-contained command or a short && chain.\n"
            + "4. Prefer checking what is installed first when unsure (e.g. 'command -v python3').\n"
            + "5. Nothing outside the JSON: no explanations, no markdown.\n\n"
            + "If the task does NOT need commands (a question or explanation), reply with a normal, helpful, concise answer in plain text.";

    private static final String SYSTEM_FIXER_PROMPT =
        "You are TermuxAI. A shell command you proposed failed. You will receive the command, its exit code and output.\n"
            + "Analyse the error and reply with ONE corrected command as JSON:\n"
            + "   {\"cmd\":\"...\",\"desc\":\"why this fixes it\"}\n"
            + "Examples: missing package -> pkg install -y <pkg>; missing file -> create it first; "
            + "syntax error -> corrected command; permission denied -> check path in ~.\n"
            + "If the task genuinely cannot be fixed with another command, reply with a short plain-text explanation instead (no JSON).";

    private static final String SYSTEM_SUMMARY_PROMPT =
        "You are TermuxAI. Give a short summary (maximum 3 lines, plain text) of the task "
            + "result for the user. State clearly whether everything succeeded or what failed.";
}
