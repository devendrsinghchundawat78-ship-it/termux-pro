package com.termux.pro.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.pro.R;
import com.termux.pro.shared.ai.AiAgentEngine;
import com.termux.pro.shared.ai.AiAgentEngine.ApprovalCallback;
import com.termux.pro.shared.ai.AiSettings;

import java.util.Locale;

/**
 * The AI agent screen. The user writes natural language tasks and the agent can run real
 * Termux commands after asking for approval each time. Everything the agent does is
 * printed into the on-screen log so the user stays in full control.
 *
 * The screen is intentionally kept inside the app (no home-screen clutter): it can be
 * opened from the terminal context menu ("AI Agent") or from Settings -> AI Assistant.
 */
public class AiAgentActivity extends AppCompatActivity implements AiAgentEngine.Listener {

    private TextView mLogView;
    private TextView mStateLabel;
    private EditText mInputView;
    private Button mSendButton;
    private Button mStopButton;
    private ScrollView mScrollView;

    private AiAgentEngine mEngine;
    private AiSettings mSettings;

    private AlertDialog mApprovalDialog;

    // Log colours
    private static final int COLOR_USER = 0xFF6EC2F7;
    private static final int COLOR_AI = 0xFF8FDC8F;
    private static final int COLOR_COMMAND = 0xFFFFD54F;
    private static final int COLOR_OUTPUT = 0xFFC5CDD3;
    private static final int COLOR_ERROR = 0xFFFF8A80;
    private static final int COLOR_STATUS = 0xFF7E8C99;
    private static final int COLOR_FINISH = 0xFFB9F6CA;
    private static final int COLOR_RISKY = 0xFFE57373;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_agent);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.ai_agent_activity_title);
        }

        mLogView = findViewById(R.id.ai_log);
        mStateLabel = findViewById(R.id.ai_state_label);
        mInputView = findViewById(R.id.ai_input);
        mSendButton = findViewById(R.id.ai_send_button);
        mStopButton = findViewById(R.id.ai_stop_button);
        mScrollView = findViewById(R.id.ai_log_scroll);

        mSettings = new AiSettings(this);
        mEngine = new AiAgentEngine(this);
        mEngine.setListener(this);

        mSendButton.setOnClickListener(v -> sendTask());
        mStopButton.setOnClickListener(v -> {
            appendLine("Stopping...", COLOR_STATUS, false);
            mEngine.requestStop();
        });
        findViewById(R.id.ai_clear_button).setOnClickListener(v -> mLogView.setText(""));

        mInputView.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTask();
                return true;
            }
            return false;
        });

        appendLine("Welcome to the AI Agent.", COLOR_FINISH, true);
        appendLine("Write any task in simple language, e.g. \"install python and run a small test script\".", COLOR_STATUS, false);
        appendLine("The AI plans shell commands and asks you before running each one. Press Stop any time.", COLOR_STATUS, false);
        appendLine("", COLOR_STATUS, false);

        updateBusyUi(false);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        // Stop the agent cleanly so no background process keeps running.
        if (mEngine != null) mEngine.release();
        dismissApprovalDialog();
        super.onDestroy();
    }

    // ----------------------------------------------------------------------------------
    // Sending a task
    // ----------------------------------------------------------------------------------

    private void sendTask() {
        String task = mInputView.getText().toString().trim();
        if (task.isEmpty()) return;

        if (mEngine.isBusy()) {
            Toast.makeText(this, R.string.ai_agent_busy, Toast.LENGTH_SHORT).show();
            return;
        }

        if (!mSettings.isConfigured()) {
            appendLine("No AI provider configured yet.", COLOR_ERROR, true);
            appendLine("Open: Settings > AI Assistant > API Key. Choose a provider, paste your key and press \"Test Connection\".",
                COLOR_ERROR, false);
            appendLine("(Providers like Google Gemini have free keys; Ollama needs no key at all.)", COLOR_STATUS, false);
            return;
        }

        mInputView.setText("");
        appendLine("[You] " + task, COLOR_USER, true);
        updateBusyUi(true);
        mEngine.runTask(task);
    }

    // ----------------------------------------------------------------------------------
    // Engine listener (always on main thread)
    // ----------------------------------------------------------------------------------

    @Override
    public void onAiStatus(String text) {
        appendLine(text, COLOR_STATUS, false);
    }

    @Override
    public void onAiMessage(String text) {
        appendLine("[AI] " + text, COLOR_AI, false);
    }

    @Override
    public void onAiError(String text) {
        appendLine(text, COLOR_ERROR, false);
    }

    @Override
    public void onCommandStart(String command, String description, int stepIndex, int stepTotal) {
        SpannableStringBuilder builder = new SpannableStringBuilder();
        builder.append(String.format(Locale.US, "Step %d/%d", stepIndex, stepTotal));
        builder.setSpan(new ForegroundColorSpan(COLOR_COMMAND), 0, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.append("\n$ ");
        builder.append(command);
        builder.setSpan(new StyleSpan(Typeface.BOLD), builder.length() - command.length(), builder.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!TextUtils.isEmpty(description)) {
            builder.append("\n    - ").append(description);
            builder.setSpan(new ForegroundColorSpan(COLOR_STATUS), builder.length() - description.length() - 3,
                builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        append(builder);
    }

    @Override
    public void onCommandOutput(String output) {
        appendLine(output, COLOR_OUTPUT, false);
    }

    @Override
    public void onApprovalRequested(String command, String description, boolean risky, ApprovalCallback callback) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_ai_approval, null);
        TextView cmdView = dialogView.findViewById(R.id.ai_approval_command);
        TextView descView = dialogView.findViewById(R.id.ai_approval_description);

        cmdView.setText("$ " + command);

        SpannableStringBuilder desc = new SpannableStringBuilder();
        if (!TextUtils.isEmpty(description)) {
            desc.append(description).append("\n\n");
        }
        if (risky) {
            desc.append("This looks like a dangerous command. Only allow it if you are sure.");
            desc.setSpan(new ForegroundColorSpan(COLOR_RISKY), 0, desc.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            desc.append("The AI wants to run this command in Termux. Do you allow it?");
            desc.setSpan(new ForegroundColorSpan(COLOR_STATUS), 0, desc.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        descView.setText(desc);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.ai_approval_title);
        builder.setView(dialogView);
        builder.setPositiveButton(R.string.ai_approval_allow, (d, w) -> {
            dismissApprovalDialog();
            callback.allow();
        });
        builder.setNegativeButton(R.string.ai_approval_deny, (d, w) -> {
            dismissApprovalDialog();
            callback.deny();
        });
        builder.setNeutralButton(R.string.ai_approval_stop_all, (d, w) -> {
            dismissApprovalDialog();
            callback.stopAll();
        });
        builder.setCancelable(true);
        builder.setOnCancelListener(d -> callback.deny());

        mApprovalDialog = builder.show();
    }

    @Override
    public void onFinished(String summary) {
        appendLine("──────────", COLOR_FINISH, false);
        appendLine(summary, COLOR_FINISH, true);
        appendLine("", COLOR_STATUS, false);
        updateBusyUi(false);
    }

    private void dismissApprovalDialog() {
        if (mApprovalDialog != null && mApprovalDialog.isShowing()) {
            mApprovalDialog.dismiss();
        }
        mApprovalDialog = null;
    }

    // ----------------------------------------------------------------------------------
    // Log helpers
    // ----------------------------------------------------------------------------------

    private void appendLine(String text, int color, boolean bold) {
        SpannableStringBuilder builder = new SpannableStringBuilder();
        builder.append(text);
        if (bold) builder.setSpan(new StyleSpan(Typeface.BOLD), 0, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new ForegroundColorSpan(color), 0, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        append(builder);
    }

    private void append(SpannableStringBuilder builder) {
        if (mLogView.getText().length() > 0) {
            mLogView.append("\n");
        }
        mLogView.append(builder);
        mScrollView.post(() -> mScrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void updateBusyUi(boolean busy) {
        mStopButton.setEnabled(busy);
        mSendButton.setEnabled(!busy);
        mStateLabel.setText(busy ? "Working..." : "Idle");
        if (!busy) {
            dismissApprovalDialog();
        }
    }
}
