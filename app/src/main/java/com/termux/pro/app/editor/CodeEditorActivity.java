package com.termux.pro.app.editor;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.Layout;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.pro.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A lightweight, dependency-free code editor ("VS Code lite"):
 *  - multiple open files with tabs
 *  - monospace editor with line numbers
 *  - syntax highlighting for many languages (own scanner, no internet needed)
 *  - autocomplete (language keywords + identifiers from the open file)
 *  - find / replace (plain or regex)
 *  - git integration through the real git binary of Termux (status + commit)
 *  - files come from the built-in File Manager or the system file picker
 */
public class CodeEditorActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "com.termux.pro.editor.extra_path";
    public static final String EXTRA_PATHS = "com.termux.pro.editor.extra_paths";

    private static final int REQUEST_OPEN = 1001;
    private static final int MAX_EDIT_SIZE = 2 * 1024 * 1024;      // refuse larger files
    private static final int MAX_HIGHLIGHT_SIZE = 120 * 1024;      // highlight cap

    // ----------------------------------------------------------------------------------

    private static final class Tab {
        String displayName;
        String filePath;   // null when opened from the system picker (uri based)
        Uri uri;           // null when file based
        String text;
        boolean dirty;
        Set<String> identifiers = new HashSet<>();
    }

    private final List<Tab> mTabs = new ArrayList<>();
    private int mCurrent = -1;

    private CodeEditText mEditor;
    private LinearLayout mTabStrip;
    private TextView mStatusView;
    private TextView mPathView;
    private boolean mSuppressWatchers;

    private Highlighter.LanguageSpec mCurrentLanguage;
    private boolean mTooLargeWarned;

    private PopupWindow mCompletePopup;
    private List<String> mSuggestions = new ArrayList<>();
    private int mWordStart = -1;

    // find/replace state
    private String mFindQuery = "";
    private int mMatchStart = -1;
    private int mMatchEnd = -1;
    private int mScanFrom = -1;
    private boolean mScanWrapped;

    // git state
    private String mGitLabel = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_code_editor);

        mEditor = findViewById(R.id.ed_editor);
        mTabStrip = findViewById(R.id.ed_tabs);
        mStatusView = findViewById(R.id.ed_status);
        mPathView = findViewById(R.id.ed_path);

        findViewById(R.id.ed_back).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.ed_save).setOnClickListener(v -> saveCurrentTab());
        findViewById(R.id.ed_open).setOnClickListener(v -> openSystemPicker());
        findViewById(R.id.ed_search).setOnClickListener(v -> showFindReplaceDialog());
        findViewById(R.id.ed_git).setOnClickListener(v -> showGitDialog());

        mStatusView.setOnClickListener(v -> showGitDialog());

        mEditor.setOnSelectionChanged(this::updateLineColumn);

        mEditor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (mSuppressWatchers) return;
                markCurrentDirty();
                scheduleHighlight();
                maybeShowAutocomplete();
            }
        });

        // initial files
        ArrayList<String> paths = new ArrayList<>();
        String single = getIntent().getStringExtra(EXTRA_PATH);
        if (single != null) paths.add(single);
        ArrayList<String> extras = getIntent().getStringArrayListExtra(EXTRA_PATHS);
        if (extras != null) paths.addAll(extras);

        if (paths.isEmpty()) {
            // Nothing was passed: show a hint and the picker
            setStatusText("Open a file with the Open button, or from the File Manager.");
            return;
        }
        for (String path : paths) {
            openFileTab(new File(path));
        }
        if (!mTabs.isEmpty()) switchTo(0);
    }

    @Override
    protected void onDestroy() {
        dismissAutocomplete();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mCurrent >= 0 && mTabs.get(mCurrent).dirty) {
            confirmSaveOrDiscard(() -> finish());
        } else {
            finish();
        }
    }

    // ----------------------------------------------------------------------------------
    // Tabs
    // ----------------------------------------------------------------------------------

    private Tab openFileTab(File file) {
        // already open?
        for (Tab tab : mTabs) {
            if (tab.filePath != null && tab.filePath.equals(file.getAbsolutePath())) {
                switchTo(mTabs.indexOf(tab));
                return tab;
            }
        }
        if (!file.isFile()) {
            toast("Not a file: " + file.getAbsolutePath());
            return null;
        }
        if (file.length() > MAX_EDIT_SIZE) {
            toast("File too large to edit (" + file.length() + " bytes).");
            return null;
        }

        String content;
        try (InputStream in = new FileInputStream(file)) {
            content = readAll(in);
        } catch (IOException e) {
            toast("Could not read file: " + e.getMessage());
            return null;
        }

        Tab tab = new Tab();
        tab.displayName = file.getName();
        tab.filePath = file.getAbsolutePath();
        tab.text = content;
        collectIdentifiers(tab);
        mTabs.add(tab);
        rebuildTabs();
        return tab;
    }

    private Tab openUriTab(Uri uri) {
        for (Tab tab : mTabs) {
            if (uri.equals(tab.uri)) {
                switchTo(mTabs.indexOf(tab));
                return tab;
            }
        }
        String content;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("openInputStream returned null");
            content = readAll(in);
        } catch (Exception e) {
            toast("Could not open the file: " + e.getMessage());
            return null;
        }

        String name = lastPathSegment(uri);
        Tab tab = new Tab();
        tab.displayName = name == null ? "document" : name;
        tab.uri = uri;
        tab.text = content;
        collectIdentifiers(tab);
        mTabs.add(tab);
        rebuildTabs();
        return tab;
    }

    private void switchTo(int index) {
        if (index < 0 || index >= mTabs.size()) return;
        if (mCurrent == index) return;

        // save current buffer content (memory only)
        if (mCurrent >= 0 && mCurrent < mTabs.size()) {
            mTabs.get(mCurrent).text = mEditor.getText().toString();
        }

        mCurrent = index;
        Tab tab = mTabs.get(mCurrent);
        mSuppressWatchers = true;
        mEditor.setText(tab.text);
        mEditor.setSelection(0);
        mSuppressWatchers = false;

        mPathView.setText(tab.filePath != null ? tab.filePath : String.valueOf(tab.uri));
        mCurrentLanguage = Highlighter.detect(tab.displayName);
        mTooLargeWarned = false;
        doHighlight();
        rebuildTabs();
        refreshGitState();
        updateLineColumn();
    }

    private void markCurrentDirty() {
        if (mCurrent < 0 || mCurrent >= mTabs.size()) return;
        Tab tab = mTabs.get(mCurrent);
        if (!tab.dirty) {
            tab.dirty = true;
            refreshTabLabel(mCurrent);
        }
    }

    private void rebuildTabs() {
        mTabStrip.removeAllViews();
        for (int i = 0; i < mTabs.size(); i++) {
            mTabStrip.addView(buildChip(i));
        }
        if (mTabs.isEmpty()) {
            mTabStrip.setVisibility(View.GONE);
        } else {
            mTabStrip.setVisibility(View.VISIBLE);
        }
    }

    private View buildChip(final int index) {
        Tab tab = mTabs.get(index);
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(10), dp(4), dp(6), dp(4));
        chip.setBackgroundResource(index == mCurrent
            ? R.drawable.bg_tab_active : R.drawable.bg_tab_inactive);

        TextView name = new TextView(this);
        name.setTag(index);
        name.setPadding(0, 0, dp(4), 0);
        name.setTextColor(index == mCurrent ? Color.WHITE : 0xFFB9B9B9);
        name.setTextSize(13);
        name.setText(tab.displayName + (tab.dirty ? " *" : ""));
        name.setOnClickListener(v -> {
            final int target = (Integer) name.getTag();
            if (target != mCurrent && mCurrent >= 0 && mTabs.get(mCurrent).dirty) {
                confirmSaveOrDiscard(() -> switchToIfAlive(target));
            } else {
                switchToIfAlive(target);
            }
        });
        chip.addView(name);

        TextView close = new TextView(this);
        close.setText("×");
        close.setTextColor(0xFF888888);
        close.setTextSize(16);
        close.setPadding(dp(4), 0, dp(2), 0);
        close.setOnClickListener(v -> closeTab((Integer) name.getTag()));
        chip.addView(close);

        chip.setTag(name); // keep the label accessible for refreshTabLabel()
        return chip;
    }

    /** switchTo with an index guard (tabs may have been removed while a dialog was open). */
    private void switchToIfAlive(int index) {
        if (index >= 0 && index < mTabs.size()) switchTo(index);
    }

    private void refreshTabLabel(int index) {
        for (int i = 0; i < mTabStrip.getChildCount(); i++) {
            View child = mTabStrip.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof TextView) {
                TextView label = (TextView) tag;
                Object labelTag = label.getTag();
                if (labelTag instanceof Integer && (Integer) labelTag == index) {
                    Tab tab = mTabs.get(index);
                    label.setText(tab.displayName + (tab.dirty ? " *" : ""));
                    return;
                }
            }
        }
    }

    private void removeTab(int index) {
        mTabs.remove(index);
        if (mTabs.isEmpty()) {
            mCurrent = -1;
            mSuppressWatchers = true;
            mEditor.setText("");
            mSuppressWatchers = false;
            mPathView.setText("");
            mCurrentLanguage = null;
            rebuildTabs();
            setStatusText("Open a file with the Open button, or from the File Manager.");
            return;
        }
        int newIndex = Math.min(index, mTabs.size() - 1);
        mCurrent = -1; // force reload in switchTo
        switchTo(newIndex);
    }

    /** Ask to save the current dirty tab before running {@code proceed}. */
    private void confirmSaveOrDiscard(Runnable proceed) {
        if (mCurrent < 0 || mCurrent >= mTabs.size() || !mTabs.get(mCurrent).dirty) {
            proceed.run();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Unsaved changes")
            .setMessage("Save the changes in \"" + mTabs.get(mCurrent).displayName + "\" first?")
            .setPositiveButton("Save", (d, w) -> {
                if (saveCurrentTab()) proceed.run();
            })
            .setNegativeButton("Discard", (d, w) -> proceed.run())
            .setNeutralButton("Cancel", null)
            .show();
    }

    private void closeTab(int index) {
        if (index < 0 || index >= mTabs.size()) return;
        Tab tab = mTabs.get(index);
        if (tab.dirty) {
            String display = tab.displayName;
            new AlertDialog.Builder(this)
                .setTitle("Close " + display + "?")
                .setMessage("This file has unsaved changes.")
                .setPositiveButton("Save & close", (d, w) -> {
                    if (saveTabAt(index)) removeTab(index);
                })
                .setNegativeButton("Discard", (d, w) -> removeTab(index))
                .setNeutralButton("Cancel", null)
                .show();
        } else {
            removeTab(index);
        }
    }

    /** Save the given tab (uses the editor text when it is the active tab). */
    private boolean saveTabAt(int index) {
        if (index < 0 || index >= mTabs.size()) return true;
        Tab tab = mTabs.get(index);
        String content = index == mCurrent ? mEditor.getText().toString() : tab.text;
        boolean ok = writeTab(tab, content);
        if (ok) {
            tab.text = content;
            tab.dirty = false;
            refreshTabLabel(index);
        }
        return ok;
    }

    private boolean saveCurrentTab() {
        if (mCurrent < 0 || mCurrent >= mTabs.size()) return true;
        Tab tab = mTabs.get(mCurrent);
        String content = mEditor.getText().toString();
        boolean ok = writeTab(tab, content);
        if (ok) {
            tab.text = content;
            tab.dirty = false;
            refreshTabLabel(mCurrent);
            setStatusText("Saved " + tab.displayName);
        }
        return ok;
    }

    private boolean writeTab(Tab tab, String content) {
        try {
            if (tab.filePath != null) {
                try (OutputStream out = new FileOutputStream(tab.filePath)) {
                    out.write(content.getBytes(StandardCharsets.UTF_8));
                }
            } else if (tab.uri != null) {
                try (OutputStream out = getContentResolver().openOutputStream(tab.uri, "wt")) {
                    if (out == null) throw new IOException("cannot open output stream");
                    out.write(content.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                toast("Nothing to save.");
                return false;
            }
            return true;
        } catch (IOException e) {
            toast("Save failed: " + e.getMessage());
            return false;
        }
    }

    // ----------------------------------------------------------------------------------
    // System file picker (for files outside the Termux home)
    // ----------------------------------------------------------------------------------

    private void openSystemPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQUEST_OPEN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OPEN || resultCode != RESULT_OK || data == null) return;
        if (data.getData() != null) {
            takePersistablePermission(data.getData());
            openUriTab(data.getData());
        }
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                if (uri != null) {
                    takePersistablePermission(uri);
                    openUriTab(uri);
                }
            }
        }
        if (!mTabs.isEmpty()) switchTo(mTabs.size() - 1);
    }

    private void takePersistablePermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException ignored) {}
    }

    // ----------------------------------------------------------------------------------
    // Syntax highlighting
    // ----------------------------------------------------------------------------------

    private final Runnable mHighlightRunnable = new Runnable() {
        @Override
        public void run() {
            doHighlight();
        }
    };

    private void scheduleHighlight() {
        mEditor.removeCallbacks(mHighlightRunnable);
        mEditor.postDelayed(mHighlightRunnable, 250);
    }

    private void doHighlight() {
        Editable text = mEditor.getText();
        if (text == null || mCurrent < 0) return;
        int length = text.length();
        if (length > MAX_HIGHLIGHT_SIZE) {
            Highlighter.clear(text);
            if (!mTooLargeWarned) {
                mTooLargeWarned = true;
                toast("File is large - syntax highlighting is off for this file.");
            }
            return;
        }
        Highlighter.apply(text, mCurrentLanguage);
        mEditor.invalidate();
    }

    /** Collect identifier names from the file for smarter completions. */
    private void collectIdentifiers(Tab tab) {
        String text = tab.text;
        int limit = Math.min(text.length(), 150_000);
        tab.identifiers.clear();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') {
                word.append(c);
            } else {
                if (word.length() >= 3 && tab.identifiers.size() < 200) {
                    tab.identifiers.add(word.toString());
                }
                word.setLength(0);
            }
        }
    }

    // ----------------------------------------------------------------------------------
    // Autocomplete
    // ----------------------------------------------------------------------------------

    private void maybeShowAutocomplete() {
        if (mCurrent < 0) return;
        Editable text = mEditor.getText();
        int pos = mEditor.getSelectionStart();
        if (pos <= 0 || pos > text.length()) return;

        char previous = text.charAt(pos - 1);
        if (!Character.isLetter(previous) && previous != '_') {
            dismissAutocomplete();
            return;
        }

        int start = pos;
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (Character.isLetterOrDigit(c) || c == '_') start--;
            else break;
        }
        String prefix = text.subSequence(start, pos).toString();
        if (prefix.length() < 2) {
            dismissAutocomplete();
            return;
        }

        Tab tab = mTabs.get(mCurrent);
        List<String> suggestions = new ArrayList<>();
        if (mCurrentLanguage != null && mCurrentLanguage.keywords != null) {
            for (String keyword : mCurrentLanguage.keywords) {
                if (keyword.length() > prefix.length()
                    && keyword.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    suggestions.add(keyword);
                }
            }
        }
        for (String identifier : tab.identifiers) {
            if (suggestions.size() >= 24) break;
            if (identifier.length() > prefix.length()
                && identifier.regionMatches(true, 0, prefix, 0, prefix.length())
                && !suggestions.contains(identifier)) {
                suggestions.add(identifier);
            }
        }
        if (suggestions.isEmpty()) {
            dismissAutocomplete();
            return;
        }
        mWordStart = start;
        showAutocomplete(suggestions, pos);
    }

    private void showAutocomplete(List<String> suggestions, int caretOffset) {
        if (mCompletePopup == null) {
            ListView list = new ListView(this);
            list.setDivider(new ColorDrawable(0xFF3A3A3A));
            list.setDividerHeight(1);
            mCompletePopup = new PopupWindow(list,
                dp(280), ViewGroup.LayoutParams.WRAP_CONTENT);
            mCompletePopup.setBackgroundDrawable(new ColorDrawable(0xFF2D2D30));
            mCompletePopup.setOutsideTouchable(true);
            mCompletePopup.setFocusable(false);
        }

        ListView listView = (ListView) mCompletePopup.getContentView();
        mSuggestions = suggestions;
        listView.setAdapter(new ArrayAdapter<>(this, R.layout.item_autocomplete, R.id.ac_item, suggestions));
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (mCurrent < 0 || mWordStart < 0) return;
            Editable text = mEditor.getText();
            int caret = mEditor.getSelectionStart();
            String word = mSuggestions.get(position);
            int end = Math.max(caret, mWordStart);
            mSuppressWatchers = true;
            text.replace(mWordStart, end, word);
            mSuppressWatchers = false;
            mEditor.setSelection(mWordStart + word.length());
            dismissAutocomplete();
            markCurrentDirty();
        });

        // position popup below the caret line
        Layout layout = mEditor.getLayout();
        int line = layout.getLineForOffset(caretOffset);
        int x = (int) layout.getPrimaryHorizontal(caretOffset) + mEditor.getTotalPaddingLeft();
        int y = layout.getLineBottom(line) - mEditor.getScrollY() + mEditor.getTotalPaddingTop() + dp(2);
        dismissAutocomplete();
        mCompletePopup.showAsDropDown(mEditor, x, y);
    }

    private void dismissAutocomplete() {
        if (mCompletePopup != null && mCompletePopup.isShowing()) {
            mCompletePopup.dismiss();
        }
    }

    // ----------------------------------------------------------------------------------
    // Find & replace
    // ----------------------------------------------------------------------------------

    private void showFindReplaceDialog() {
        dismissAutocomplete();
        View view = getLayoutInflater().inflate(R.layout.dialog_find_replace, null);
        final EditText findField = view.findViewById(R.id.fr_find);
        final EditText replaceField = view.findViewById(R.id.fr_replace);
        final CheckBox regexBox = view.findViewById(R.id.fr_regex);
        final CheckBox caseBox = view.findViewById(R.id.fr_case);

        if (mFindQuery != null && !mFindQuery.isEmpty()) findField.setText(mFindQuery);

        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Find & Replace")
            .setView(view)
            .setNegativeButton("Close", null)
            .create();

        view.findViewById(R.id.fr_next).setOnClickListener(v -> {
            if (find(true, findField.getText().toString(), regexBox.isChecked(), caseBox.isChecked()))
                scrollToMatch();
        });
        view.findViewById(R.id.fr_prev).setOnClickListener(v -> {
            if (find(false, findField.getText().toString(), regexBox.isChecked(), caseBox.isChecked()))
                scrollToMatch();
        });
        view.findViewById(R.id.fr_replace_btn).setOnClickListener(v -> {
            if (!replaceCurrent(replaceField.getText().toString())) {
                toast("No match selected - press Next first.");
            }
        });
        view.findViewById(R.id.fr_replace_all).setOnClickListener(v -> {
            int count = replaceAll(findField.getText().toString(), replaceField.getText().toString(),
                regexBox.isChecked(), caseBox.isChecked());
            toast(count + " replaced.");
        });

        dialog.show();
    }

    private boolean find(boolean forward, String query, boolean regex, boolean matchCase) {
        if (query == null || query.isEmpty()) return false;
        String source = mEditor.getText().toString();
        int flags = matchCase ? 0 : Pattern.CASE_INSENSITIVE;

        if (!mFindQuery.equals(query)) {
            mFindQuery = query;
            mScanFrom = -1;
            mScanWrapped = false;
            mMatchStart = -1;
            mMatchEnd = -1;
        }

        int startFrom;
        if (mScanFrom < 0) {
            startFrom = forward ? mEditor.getSelectionEnd() : mEditor.getSelectionStart();
        } else {
            startFrom = mScanFrom;
        }

        try {
            if (forward) {
                int idx = indexOfFrom(source, query, regex, flags, startFrom);
                if (idx < 0) {
                    idx = indexOfFrom(source, query, regex, flags, 0);
                    if (idx >= 0 && !mScanWrapped) {
                        mScanWrapped = true;
                        toast("Reached the end - wrapped to the top.");
                    }
                }
                if (idx < 0) {
                    toast("No matches found.");
                    return false;
                }
                int end = idx + matchLength(source, query, regex, flags, idx);
                mMatchStart = idx;
                mMatchEnd = end;
                mScanFrom = end;
                selectMatch(idx, end);
                return true;
            } else {
                int idx = lastIndexBefore(source, query, regex, flags, startFrom);
                if (idx < 0) {
                    idx = lastIndexBefore(source, query, regex, flags, source.length());
                    if (idx >= 0 && !mScanWrapped) {
                        mScanWrapped = true;
                        toast("Reached the start - wrapped to the bottom.");
                    }
                }
                if (idx < 0) {
                    toast("No matches found.");
                    return false;
                }
                int end = idx + matchLength(source, query, regex, flags, idx);
                mMatchStart = idx;
                mMatchEnd = end;
                mScanFrom = idx;
                selectMatch(idx, end);
                return true;
            }
        } catch (Exception e) {
            toast("Invalid regular expression: " + e.getMessage());
            return false;
        }
    }

    private int indexOfFrom(String source, String query, boolean regex, int flags, int from) {
        if (from < 0 || from > source.length()) from = 0;
        if (regex) {
            Matcher matcher = Pattern.compile(query, flags).matcher(source);
            if (matcher.find(from)) return matcher.start();
            return -1;
        }
        if (flags != 0) {
            // flags == Pattern.CASE_INSENSITIVE -> insensitive search
            return source.toLowerCase(Locale.US).indexOf(query.toLowerCase(Locale.US), from);
        }
        return source.indexOf(query, from);
    }

    private int lastIndexBefore(String source, String query, boolean regex, int flags, int before) {
        if (regex) {
            Matcher matcher = Pattern.compile(query, flags).matcher(source);
            int last = -1;
            while (matcher.find() && matcher.start() < before) {
                last = matcher.start();
            }
            return last;
        }
        String haystack = (flags != 0) ? source.toLowerCase(Locale.US) : source;
        String needle = (flags != 0) ? query.toLowerCase(Locale.US) : query;
        return haystack.lastIndexOf(needle, before - 1);
    }

    private int matchLength(String source, String query, boolean regex, int flags, int at) {
        if (!regex) return query.length();
        Matcher matcher = Pattern.compile(query, flags).matcher(source);
        if (matcher.find(at) && matcher.start() == at) {
            return matcher.group().length();
        }
        return query.length();
    }

    private void selectMatch(int start, int end) {
        mEditor.setSelection(start, end);
        mEditor.requestFocus();
    }

    private void scrollToMatch() {
        int selection = mEditor.getSelectionStart();
        Layout layout = mEditor.getLayout();
        if (layout == null) return;
        int line = layout.getLineForOffset(selection);
        int top = layout.getLineTop(line);
        int bottom = layout.getLineBottom(line);
        mEditor.scrollTo(0, Math.max(0, top - (mEditor.getHeight() / 3)));
        mEditor.scrollBy(0, 0);
    }

    private boolean replaceCurrent(String replacement) {
        Editable text = mEditor.getText();
        if (mMatchStart < 0 || mMatchEnd > text.length() || mMatchStart >= mMatchEnd) return false;
        mSuppressWatchers = true;
        text.replace(mMatchStart, mMatchEnd, replacement == null ? "" : replacement);
        mSuppressWatchers = false;
        mEditor.setSelection(mMatchStart);
        markCurrentDirty();
        int length = replacement == null ? 0 : replacement.length();
        mMatchEnd = mMatchStart + length;
        return true;
    }

    private int replaceAll(String query, String replacement, boolean regex, boolean matchCase) {
        if (query == null || query.isEmpty()) return 0;
        String source = mEditor.getText().toString();
        int flags = matchCase ? 0 : Pattern.CASE_INSENSITIVE;
        replacement = replacement == null ? "" : replacement;
        int count = 0;
        try {
            if (regex) {
                count = countOfMatches(source, query, regex, flags);
                if (count > 0) {
                    Matcher matcher = Pattern.compile(query, flags).matcher(source);
                    String result = matcher.replaceAll(Matcher.quoteReplacement(replacement));
                    applyReplacement(result);
                }
            } else {
                count = countOfMatches(source, query, regex, flags);
                if (count > 0) {
                    applyReplacement(literalReplaceAll(source, query, replacement, flags));
                }
            }
        } catch (Exception e) {
            toast("Invalid regular expression: " + e.getMessage());
            return 0;
        }
        if (count > 0) markCurrentDirty();
        return count;
    }

    private int countOfMatches(String source, String query, boolean regex, int flags) {
        int count = 0;
        try {
            if (regex) {
                Matcher matcher = Pattern.compile(query, flags).matcher(source);
                while (matcher.find()) count++;
            } else {
                String haystack = (flags != 0) ? source.toLowerCase(Locale.US) : source;
                String needle = (flags != 0) ? query.toLowerCase(Locale.US) : query;
                int idx = 0;
                while ((idx = haystack.indexOf(needle, idx)) != -1) {
                    count++;
                    idx += needle.length();
                }
            }
        } catch (Exception ignored) {}
        return count;
    }

    private String literalReplaceAll(String source, String query, String replacement, int flags) {
        StringBuilder builder = new StringBuilder();
        String haystack = (flags != 0) ? source.toLowerCase(Locale.US) : source;
        String needle = (flags != 0) ? query.toLowerCase(Locale.US) : query;
        int last = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            builder.append(source, last, idx).append(replacement);
            idx += needle.length();
            last = idx;
        }
        builder.append(source, last, source.length());
        return builder.toString();
    }

    private void applyReplacement(String result) {
        mSuppressWatchers = true;
        mEditor.setText(result);
        mSuppressWatchers = false;
        mMatchStart = -1;
        mMatchEnd = -1;
        mFindQuery = "";
        mScanFrom = -1;
    }

    // ----------------------------------------------------------------------------------
    // Git integration
    // ----------------------------------------------------------------------------------

    private void refreshGitState() {
        mGitLabel = "";
        if (mCurrent < 0) {
            updateLineColumn();
            return;
        }
        Tab tab = mTabs.get(mCurrent);
        if (tab.filePath == null) {
            updateLineColumn();
            return;
        }
        final File dir = new File(tab.filePath).getParentFile();
        if (dir == null) {
            updateLineColumn();
            return;
        }
        GitRunner.status(this, dir, (ok, code, stdout, stderr) -> {
            if (ok && stdout.contains("##")) {
                String[] lines = stdout.split("\n");
                String branch = lines[0].replace("## ", "").trim();
                int space = branch.indexOf(" ");
                if (space > 0) branch = branch.substring(0, space);
                if (branch.startsWith("No commits yet on ")) branch = branch.substring("No commits yet on ".length());
                int changed = lines.length - 1;
                mGitLabel = branch + (changed > 0 ? " · " + changed + " changed" : "");
            } else {
                mGitLabel = ""; // not a git repository (or git missing) - show nothing
            }
            updateLineColumn();
        });
    }

    private void showGitDialog() {
        if (mCurrent < 0 || mTabs.get(mCurrent).filePath == null) {
            toast("Git works on files inside a git repository (use the File Manager to open one).");
            return;
        }
        String[] options = {
            "Refresh git state",
            "Show changed files",
            "Commit all changes…",
            "Copy status to clipboard"
        };
        new AlertDialog.Builder(this)
            .setTitle("Git " + (mGitLabel.isEmpty() ? "" : "· " + mGitLabel))
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0: refreshGitState(); break;
                    case 1: showChangedFilesDialog(); break;
                    case 2: showCommitDialog(); break;
                    case 3: copyGitStatus(); break;
                }
            })
            .show();
    }

    private File currentGitDir() {
        Tab tab = mTabs.get(mCurrent);
        if (tab == null || tab.filePath == null) return null;
        File parent = new File(tab.filePath).getParentFile();
        return parent;
    }

    private void showChangedFilesDialog() {
        File dir = currentGitDir();
        if (dir == null) return;
        GitRunner.status(this, dir, (ok, code, stdout, stderr) -> {
            if (!ok) {
                toast(stderr.isEmpty() ? "Git failed." : stderr);
                return;
            }
            String body = stdout;
            if (body.trim().isEmpty()) body = "Working tree is clean.";
            new AlertDialog.Builder(this)
                .setTitle("Changed files")
                .setMessage(body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        });
    }

    private void showCommitDialog() {
        final EditText message = new EditText(this);
        message.setHint("Commit message");
        message.setSingleLine(false);
        message.setMinLines(3);
        new AlertDialog.Builder(this)
            .setTitle("Commit all changes")
            .setMessage("Stages everything (git add -A) and commits in this repository.")
            .setView(message)
            .setPositiveButton("Commit", (d, w) -> {
                String msg = message.getText().toString().trim();
                if (msg.isEmpty()) {
                    toast("Commit message cannot be empty.");
                    return;
                }
                File dir = currentGitDir();
                if (dir == null) return;
                toast("Committing...");
                GitRunner.commit(this, dir, msg, (ok, code, out, err) -> {
                    if (ok) {
                        toast("Commit completed.");
                        refreshGitState();
                    } else {
                        toast(err.isEmpty() ? "Commit failed." : err);
                    }
                });
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void copyGitStatus() {
        File dir = currentGitDir();
        if (dir == null) return;
        GitRunner.status(this, dir, (ok, code, stdout, stderr) -> {
            if (ok) {
                android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("git status", stdout));
                toast("Git status copied.");
            } else {
                toast(stderr.isEmpty() ? "Git failed." : stderr);
            }
        });
    }

    // ----------------------------------------------------------------------------------
    // Status helpers
    // ----------------------------------------------------------------------------------

    private void updateLineColumn() {
        if (mCurrent < 0 || mEditor.getLayout() == null) {
            setStatusText(mGitLabel);
            return;
        }
        int pos = Math.max(0, mEditor.getSelectionStart());
        Layout layout = mEditor.getLayout();
        int line = layout.getLineForOffset(Math.min(pos, mEditor.getText().length()));
        int column = pos - layout.getLineStart(line) + 1;
        String gitPart = mGitLabel.isEmpty() ? "git: —" : "git: " + mGitLabel;
        setStatusText(gitPart + "    Ln " + (line + 1) + ", Col " + column);
    }

    private void setStatusText(String text) {
        mStatusView.setText(text);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
            if (buffer.size() > MAX_EDIT_SIZE) throw new IOException("file too large");
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private String lastPathSegment(Uri uri) {
        String last = uri.getLastPathSegment();
        if (last == null) return null;
        int slash = last.lastIndexOf('/');
        return slash >= 0 ? last.substring(slash + 1) : last;
    }
}
