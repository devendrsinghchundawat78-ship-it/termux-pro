package com.termux.pro.app.editor;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.termux.pro.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runs "git" commands for the editor by calling the real git binary installed inside
 * Termux ($PREFIX/bin/git). That keeps the app small and gives full git compatibility.
 * If git is not installed yet, the user gets a friendly hint instead of a crash.
 */
public final class GitRunner {

    public interface Callback {
        /** @param ok true when the process exited with 0 */
        void onResult(boolean ok, int exitCode, String stdout, String stderr);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private GitRunner() {}

    /** Run {@code argv} in {@code workDir}. The callback runs on the main thread. */
    public static void run(final Context context, final File workDir, final String[] argv,
                           final Callback callback) {
        EXECUTOR.execute(() -> {
            Process process = null;
            String stdout = "";
            String stderr = "";
            int exit = -1;
            boolean missingGit = false;
            try {
                ProcessBuilder builder = new ProcessBuilder(argv);
                builder.directory(workDir);

                // Give the git binary the real Termux environment (PATH, HOME, ...)
                try {
                    Map<String, String> env = new TermuxShellEnvironment()
                        .getEnvironment(context.getApplicationContext(), false);
                    if (env != null) builder.environment().putAll(env);
                } catch (Exception ignored) {}

                process = builder.start();
                // drain both streams so the process can not block on a full pipe
                StreamDrainer out = new StreamDrainer(process.getInputStream());
                StreamDrainer err = new StreamDrainer(process.getErrorStream());
                out.start();
                err.start();
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroy();
                    stderr = "git command timed out.";
                    exit = -1;
                } else {
                    exit = process.exitValue();
                }
                out.join(2000);
                err.join(2000);
                stdout = out.getOutput();
                stderr = err.getOutput();
            } catch (IOException e) {
                missingGit = true;
                stderr = "exec failed: " + e.getMessage();
            } catch (InterruptedException e) {
                stderr = "git command interrupted.";
            } finally {
                if (process != null) process.destroy();
            }

            final boolean finalMissing = missingGit;
            final String finalStdout = stdout.trim();
            final String finalStderr = stderr.trim();
            final int finalExit = exit;
            MAIN.post(() -> {
                if (finalMissing) {
                    callback.onResult(false, -1, "",
                        "Git is not installed in Termux yet. Run:  pkg install git");
                } else {
                    callback.onResult(finalExit == 0, finalExit, finalStdout, finalStderr);
                }
            });
        });
    }

    /** Check if {@code dir} (or a parent) is inside a git work tree. */
    public static void isRepository(Context context, File dir, Callback callback) {
        run(context, dir, new String[]{"git", "rev-parse", "--is-inside-work-tree"}, callback);
    }

    /** Read-only info: current branch + porcelain status lines. */
    public static void status(Context context, File dir, Callback callback) {
        run(context, dir, new String[]{"git", "status", "--porcelain", "--branch"}, callback);
    }

    /** Stage everything and commit. */
    public static void commit(Context context, File dir, String message, Callback callback) {
        run(context, dir, new String[]{"git", "add", "-A"}, (ok, code, out, err) -> {
            if (!ok) {
                callback.onResult(false, code, out, err);
                return;
            }
            run(context, dir, new String[]{"git", "commit", "-m", message}, callback);
        });
    }

    /** Small thread that reads an InputStream into a string. */
    private static final class StreamDrainer extends Thread {
        private final InputStream mStream;
        private final ByteArrayOutputStream mBuffer = new ByteArrayOutputStream();

        StreamDrainer(InputStream stream) {
            mStream = stream;
        }

        @Override
        public void run() {
            try {
                byte[] chunk = new byte[4096];
                int read;
                while ((read = mStream.read(chunk)) != -1) {
                    mBuffer.write(chunk, 0, read);
                }
            } catch (IOException ignored) {
            } finally {
                try {
                    mStream.close();
                } catch (IOException ignored) {}
            }
        }

        String getOutput() {
            try {
                return new String(mBuffer.toByteArray(), "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }
    }
}
