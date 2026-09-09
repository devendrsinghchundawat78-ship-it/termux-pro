package com.termux.pro.app.files;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.pro.R;
import com.termux.pro.app.editor.CodeEditorActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A proper GUI file manager for Termux, completely separate from the terminal:
 *  - full file browser with path breadcrumb, Up/Home navigation
 *  - unix permissions shown and editable (rwx / chmod via real mode bits)
 *  - hidden files toggle, sorting by name/size/date
 *  - create / rename / delete files & folders
 *  - archive support: extract .zip / .tar / .tar.gz / .tgz, compress to .zip
 *  - open text/code files directly in the built-in Code Editor
 */
public class FileManagerActivity extends AppCompatActivity {

    private static final String PREF_HIDDEN = "file_manager_show_hidden";
    private static final String PREF_SORT = "file_manager_sort";
    private static final String PREF_SORT_ASC = "file_manager_sort_asc";

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private ListView mListView;
    private TextView mPathView;
    private Button mHiddenButton;
    private File mCurrentDir;
    private boolean mShowHidden;
    private String mSortBy = "name";
    private boolean mSortAsc = true;

    private final List<File> mEntries = new ArrayList<>();

    // Extensions that open directly in the code editor
    private static final List<String> TEXT_EXTS = Arrays.asList(
        "txt", "md", "py", "sh", "bash", "java", "kt", "c", "h", "cpp", "hpp", "cc", "js", "mjs",
        "ts", "json", "xml", "html", "htm", "css", "yml", "yaml", "toml", "ini", "cfg", "conf",
        "properties", "gradle", "log", "csv", "tsv", "sql", "rs", "go", "rb", "php", "lua", "r", "pl",
        "gitignore", "env", "mk", "cmake", "lock", "diff", "patch");

    private static final List<String> IMAGE_EXTS = Arrays.asList(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg");
    private static final List<String> AUDIO_EXTS = Arrays.asList("mp3", "ogg", "wav", "flac", "m4a", "opus", "aac");
    private static final List<String> VIDEO_EXTS = Arrays.asList("mp4", "mkv", "webm", "avi", "mov", "3gp");
    private static final List<String> ARCHIVE_EXTS = Arrays.asList("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_manager);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.action_file_manager);
        }

        mListView = findViewById(R.id.fm_list);
        mPathView = findViewById(R.id.fm_path);

        ((Button) findViewById(R.id.fm_up)).setOnClickListener(v -> navigateTo(mCurrentDir.getParentFile()));
        ((Button) findViewById(R.id.fm_home)).setOnClickListener(v -> navigateTo(homeDir()));
        ((Button) findViewById(R.id.fm_new_file)).setOnClickListener(v -> promptNewFile());
        ((Button) findViewById(R.id.fm_new_folder)).setOnClickListener(v -> promptNewFolder());
        ((Button) findViewById(R.id.fm_sort)).setOnClickListener(v -> showSortDialog());
        mHiddenButton = findViewById(R.id.fm_hidden);
        mHiddenButton.setOnClickListener(v -> toggleHidden());

        mShowHidden = getSharedPreferences("termux_file_manager", MODE_PRIVATE).getBoolean(PREF_HIDDEN, false);
        mSortBy = getSharedPreferences("termux_file_manager", MODE_PRIVATE).getString(PREF_SORT, "name");
        mSortAsc = getSharedPreferences("termux_file_manager", MODE_PRIVATE).getBoolean(PREF_SORT_ASC, true);
        updateHiddenButton();

        mListView.setAdapter(mFileAdapter);
        mListView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= mEntries.size()) return;
            File file = mEntries.get(position);
            if (FileOps.isDir(file) && !FileOps.isSymlink(file)) {
                navigateTo(file);
            } else {
                openFile(file);
            }
        });
        mListView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < mEntries.size()) showFileActions(mEntries.get(position));
            return true;
        });

        navigateTo(homeDir());
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    // ----------------------------------------------------------------------------------
    // Navigation
    // ----------------------------------------------------------------------------------

    private File homeDir() {
        try {
            java.util.HashMap<String, String> env =
                new com.termux.pro.shared.termux.shell.command.environment.TermuxShellEnvironment()
                    .getEnvironment(this, false);
            String home = env.get("HOME");
            if (home != null && !home.isEmpty()) return new File(home);
        } catch (Exception ignored) {}
        return new File(getApplicationInfo().dataDir, "files/home");
    }

    private void navigateTo(File dir) {
        if (dir == null) return;
        if (!FileOps.exists(dir)) {
            toast("Folder does not exist: " + dir.getAbsolutePath());
            return;
        }
        if (!FileOps.isDir(dir)) {
            toast("Not a folder: " + dir.getName());
            return;
        }
        mCurrentDir = dir;
        mPathView.setText(dir.getAbsolutePath());
        reload();
    }

    private void reload() {
        if (mCurrentDir == null) return;
        File[] children = mCurrentDir.listFiles();
        if (children == null) {
            mEntries.clear();
            toast("Cannot read this folder (no permission).");
            mFileAdapter.notifyDataSetChanged();
            return;
        }
        mEntries.clear();
        for (File child : children) {
            if (!mShowHidden && child.getName().startsWith(".")) continue;
            mEntries.add(child);
        }
        sortEntries();
        mFileAdapter.notifyDataSetChanged();
    }

    private void sortEntries() {
        final String sortBy = mSortBy;
        final boolean asc = mSortAsc;
        mEntries.sort((a, b) -> {
            boolean aDir = FileOps.isDir(a);
            boolean bDir = FileOps.isDir(b);
            if (aDir != bDir) return aDir ? -1 : 1; // folders first
            int result;
            if ("size".equals(sortBy)) {
                result = Long.compare(a.length(), b.length());
            } else if ("date".equals(sortBy)) {
                result = Long.compare(a.lastModified(), b.lastModified());
            } else {
                result = a.getName().compareToIgnoreCase(b.getName());
            }
            return asc ? result : -result;
        });
    }

    private void savePrefs() {
        getSharedPreferences("termux_file_manager", MODE_PRIVATE).edit()
            .putBoolean(PREF_HIDDEN, mShowHidden)
            .putString(PREF_SORT, mSortBy)
            .putBoolean(PREF_SORT_ASC, mSortAsc)
            .apply();
    }

    private void updateHiddenButton() {
        mHiddenButton.setText(mShowHidden ? getString(R.string.file_manager_hidden_on) : getString(R.string.file_manager_hidden_off));
    }

    private void toggleHidden() {
        mShowHidden = !mShowHidden;
        updateHiddenButton();
        savePrefs();
        reload();
    }

    private void showSortDialog() {
        String[] options = {"Name", "Size", "Date modified", "Toggle direction (now: " + (mSortAsc ? "ascending" : "descending") + ")"};
        new AlertDialog.Builder(this)
            .setTitle("Sort by")
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0: mSortBy = "name"; break;
                    case 1: mSortBy = "size"; break;
                    case 2: mSortBy = "date"; break;
                    case 3: mSortAsc = !mSortAsc; break;
                }
                savePrefs();
                reload();
            })
            .show();
    }

    // ----------------------------------------------------------------------------------
    // Opening files
    // ----------------------------------------------------------------------------------

    private void openFile(File file) {
        if (FileOps.isDir(file)) {
            if (FileOps.isSymlink(file)) toast("Symlink to a folder - use a file manager action instead.");
            else navigateTo(file);
            return;
        }
        if (isTextFile(file)) {
            openInEditor(file);
            return;
        }
        String ext = FileOps.extension(file);
        if ("zip".equals(ext) || "tar".equals(ext) || "tgz".equals(ext)
            || "gz".equals(ext) && ArchiveUtils.isTar(file) || "gz".equals(ext) && ArchiveUtils.isTarGz(file)) {
            showExtractDialog(file);
            return;
        }
        toast("No built-in viewer for " + (ext.isEmpty() ? "this file" : "." + ext)
            + ". Long-press for options.");
    }

    private boolean isTextFile(File file) {
        String ext = FileOps.extension(file);
        if (TEXT_EXTS.contains(ext)) return true;
        if (ext.isEmpty()) {
            // no extension: check it looks like text by reading the first bytes
            try {
                byte[] head = new byte[512];
                int n;
                try (java.io.InputStream in = new java.io.FileInputStream(file)) {
                    n = in.read(head);
                }
                for (int i = 0; i < n; i++) {
                    if (head[i] == 0) return false;
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private void openInEditor(File file) {
        Intent intent = new Intent(this, CodeEditorActivity.class);
        intent.putExtra(CodeEditorActivity.EXTRA_PATH, file.getAbsolutePath());
        startActivity(intent);
    }

    // ----------------------------------------------------------------------------------
    // Actions dialog (long press)
    // ----------------------------------------------------------------------------------

    private void showFileActions(final File file) {
        List<String> actions = new ArrayList<>();
        actions.add("Open in editor");
        actions.add("Rename");
        actions.add("Delete");
        actions.add("Copy path");
        if (FileOps.isDir(file)) {
            actions.add("Compress to .zip");
        } else {
            actions.add("Compress to .zip");
        }
        actions.add("Permissions…");
        actions.add("Properties");

        String ext = FileOps.extension(file);
        boolean extractable = "zip".equals(ext) || "tar".equals(ext) || "tgz".equals(ext)
            || ("gz".equals(ext) && (ArchiveUtils.isTar(file) || ArchiveUtils.isTarGz(file)));
        if (extractable) actions.add(0, "Extract archive…");

        new AlertDialog.Builder(this)
            .setTitle(file.getName())
            .setItems(actions.toArray(new String[0]), (dialog, which) -> {
                String action = actions.get(which);
                switch (action) {
                    case "Open in editor":
                        openInEditor(file);
                        break;
                    case "Extract archive…":
                        showExtractDialog(file);
                        break;
                    case "Rename":
                        promptRename(file);
                        break;
                    case "Delete":
                        confirmDelete(file);
                        break;
                    case "Copy path":
                        copyPath(file);
                        break;
                    case "Compress to .zip":
                        promptCompress(file);
                        break;
                    case "Permissions…":
                        showPermissionsDialog(file);
                        break;
                    case "Properties":
                        showProperties(file);
                        break;
                }
            })
            .show();
    }

    private void promptRename(final File file) {
        final EditText input = new EditText(this);
        input.setText(file.getName());
        input.setSelection(file.getName().length());
        new AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Rename", (d, w) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty() || name.contains("/")) {
                    toast("Invalid name.");
                    return;
                }
                File target = new File(file.getParentFile(), name);
                if (target.exists()) {
                    toast("Already exists: " + name);
                    return;
                }
                if (file.renameTo(target)) reload();
                else toast("Rename failed (permission denied?).");
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void confirmDelete(final File file) {
        new AlertDialog.Builder(this)
            .setTitle("Delete")
            .setMessage("Delete \"" + file.getName() + "\" permanently?")
            .setPositiveButton("Delete", (d, w) -> mExecutor.execute(() -> {
                final boolean ok = deleteRecursively(file);
                mMainHandler.post(() -> {
                    toast(ok ? "Deleted." : "Could not delete (permission denied?).");
                    reload();
                });
            }))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private boolean deleteRecursively(File file) {
        if (FileOps.isDir(file) && !FileOps.isSymlink(file)) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) return false;
                }
            }
        }
        return file.delete();
    }

    private void copyPath(File file) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("path", file.getAbsolutePath()));
            toast("Path copied.");
        }
    }

    private void showProperties(File file) {
        StringBuilder sb = new StringBuilder();
        sb.append("Name: ").append(file.getName()).append('\n');
        sb.append("Path: ").append(file.getAbsolutePath()).append('\n');
        sb.append("Type: ").append(FileOps.isDir(file) ? "folder" : "file")
            .append(FileOps.isSymlink(file) ? " (symlink)" : "").append('\n');
        if (!FileOps.isDir(file)) {
            sb.append("Size: ").append(FileOps.humanSize(file.length())).append('\n');
        } else {
            File[] children = file.listFiles();
            sb.append("Items: ").append(children == null ? "?" : children.length).append('\n');
        }
        sb.append("Permissions: ").append(FileOps.modeLine(file)).append('\n');
        sb.append("Modified: ").append(FileOps.formatDate(file.lastModified())).append('\n');
        sb.append("Readable: ").append(file.canRead()).append("  Writable: ").append(file.canWrite());

        new AlertDialog.Builder(this)
            .setTitle("Properties")
            .setMessage(sb.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void showPermissionsDialog(final File file) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(FileOps.permissionsString(file));
        input.setHint("rwxr-xr-x  or  755");
        new AlertDialog.Builder(this)
            .setTitle("Permissions - " + file.getName())
            .setMessage("Set unix permissions. Example: rwxr-xr-x (owner, group, others).")
            .setView(input)
            .setPositiveButton("Apply", (d, w) -> {
                String error = FileOps.chmod(file, input.getText().toString());
                if (error == null) {
                    toast("Permissions updated.");
                    reload();
                } else {
                    toast(error);
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showExtractDialog(final File archive) {
        String hint = archive.getName();
        if (hint.endsWith(".zip")) hint = hint.substring(0, hint.length() - 4);
        else if (hint.endsWith(".tar.gz")) hint = hint.substring(0, hint.length() - 7);
        else if (hint.endsWith(".tgz")) hint = hint.substring(0, hint.length() - 4);
        else if (hint.endsWith(".tar")) hint = hint.substring(0, hint.length() - 4);

        final String defaultName = hint.isEmpty() ? "extracted" : hint;
        new AlertDialog.Builder(this)
            .setTitle("Extract")
            .setMessage("Extract \"" + archive.getName() + "\" into a new folder?")
            .setPositiveButton("Extract", (d, w) -> mExecutor.execute(() -> {
                File target = new File(mCurrentDir, defaultName);
                final ArchiveUtils.Result result;
                String ext = FileOps.extension(archive);
                if ("zip".equals(ext)) {
                    result = ArchiveUtils.extractZip(archive, target);
                } else {
                    result = ArchiveUtils.extractTar(archive, target);
                }
                mMainHandler.post(() -> {
                    toast(result.message);
                    reload();
                });
            }))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void promptCompress(final File source) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(source.getName() + ".zip");
        new AlertDialog.Builder(this)
            .setTitle("Compress to zip")
            .setView(input)
            .setPositiveButton("Create", (d, w) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) return;
                if (!name.endsWith(".zip")) name = name + ".zip";
                final File zipFile = new File(mCurrentDir, name);
                if (zipFile.exists()) {
                    toast("Already exists: " + name);
                    return;
                }
                mExecutor.execute(() -> {
                    final ArchiveUtils.Result result = ArchiveUtils.compressZip(source, zipFile);
                    mMainHandler.post(() -> {
                        toast(result.message);
                        reload();
                    });
                });
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void promptNewFile() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("filename.py");
        new AlertDialog.Builder(this)
            .setTitle("New file")
            .setView(input)
            .setPositiveButton("Create", (d, w) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty() || name.contains("/")) {
                    toast("Invalid name.");
                    return;
                }
                File file = new File(mCurrentDir, name);
                if (file.exists()) {
                    toast("Already exists.");
                    return;
                }
                try {
                    if (file.createNewFile()) {
                        toast("Created.");
                        reload();
                    }
                } catch (Exception e) {
                    toast("Could not create file: " + e.getMessage());
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void promptNewFolder() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("folder name");
        new AlertDialog.Builder(this)
            .setTitle("New folder")
            .setView(input)
            .setPositiveButton("Create", (d, w) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty() || name.contains("/")) {
                    toast("Invalid name.");
                    return;
                }
                File folder = new File(mCurrentDir, name);
                if (folder.mkdirs()) {
                    toast("Created.");
                    reload();
                } else {
                    toast("Could not create folder (permission denied?).");
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // ----------------------------------------------------------------------------------
    // List adapter
    // ----------------------------------------------------------------------------------

    private final BaseAdapter mFileAdapter = new BaseAdapter() {

        @Override
        public int getCount() {
            return mEntries.size();
        }

        @Override
        public Object getItem(int position) {
            return mEntries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(FileManagerActivity.this)
                    .inflate(R.layout.item_file_row, parent, false);
                holder = new ViewHolder();
                holder.icon = convertView.findViewById(R.id.fm_icon);
                holder.name = convertView.findViewById(R.id.fm_name);
                holder.meta = convertView.findViewById(R.id.fm_meta);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            File file = mEntries.get(position);
            boolean dir = FileOps.isDir(file);
            boolean link = FileOps.isSymlink(file);

            // Vector icon with a category colour.
            int[] icon = iconFor(file, dir);
            holder.icon.setImageResource(icon[0]);
            holder.icon.setColorFilter(icon[1]);
            holder.icon.setContentDescription(dir ? "folder" : "file");

            String name = file.getName();
            holder.name.setText(name + (link ? " (link)" : ""));

            String meta;
            if (dir) {
                File[] children = file.listFiles();
                meta = FileOps.permissionsString(file) + "  ·  "
                    + (children == null ? "?" : children.length) + " items  ·  "
                    + FileOps.formatDate(file.lastModified());
            } else {
                meta = FileOps.permissionsString(file) + "  ·  " + FileOps.humanSize(file.length())
                    + "  ·  " + FileOps.formatDate(file.lastModified());
            }
            holder.meta.setText(meta);
            return convertView;
        }

        /** Returns {drawableRes, tintColor} for the file. */
        private int[] iconFor(File file, boolean dir) {
            if (dir) return new int[]{R.drawable.ic_folder, 0xFF8D6E63};
            String ext = FileOps.extension(file);
            if (ext.isEmpty()) return new int[]{R.drawable.ic_file, 0xFF90A4AE};
            if (ext.equals("pdf")) return new int[]{R.drawable.ic_pdf, 0xFFEF5350};
            if (IMAGE_EXTS.contains(ext)) return new int[]{R.drawable.ic_image, 0xFF66BB6A};
            if (AUDIO_EXTS.contains(ext)) return new int[]{R.drawable.ic_audio, 0xFFAB47BC};
            if (VIDEO_EXTS.contains(ext)) return new int[]{R.drawable.ic_video, 0xFFEC407A};
            if (ARCHIVE_EXTS.contains(ext)) return new int[]{R.drawable.ic_archive, 0xFFFFA726};
            if (TEXT_EXTS.contains(ext)) return new int[]{R.drawable.ic_code, 0xFF42A5F5};
            return new int[]{R.drawable.ic_file, 0xFF90A4AE};
        }
    };

    static class ViewHolder {
        ImageView icon;
        TextView name;
        TextView meta;
    }
}
