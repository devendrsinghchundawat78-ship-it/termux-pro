package com.termux.pro.app.files;

import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Small helpers for the file manager that use real unix semantics (permissions, stat)
 * through libcore's {@link Os}, the same way the rest of Termux does.
 */
public final class FileOps {

    private FileOps() {}

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US);

    /** Returns the unix permission string (e.g. "rwxr-xr-x") or "---------" on error. */
    public static String permissionsString(File file) {
        try {
            StructStat stat = stat(file);
            return modeToString(stat.st_mode);
        } catch (ErrnoException e) {
            return "?????????";
        }
    }

    /** Full mode line like "-rw-r--r--" including the file type char. */
    public static String modeLine(File file) {
        try {
            StructStat stat = stat(file);
            char type;
            int m = stat.st_mode;
            if (isDirMode(m)) type = 'd';
            else if ((m & 0x1000) != 0) type = 'p';
            else if ((m & 0x2000) != 0) type = 'c';
            else if ((m & 0x6000) == 0x6000) type = 'b';
            else if ((m & 0xA000) == 0xA000) type = 'l';
            else if ((m & 0xC000) == 0xC000) type = 's';
            else type = '-';
            return type + modeToString(m);
        } catch (ErrnoException e) {
            return "?---------";
        }
    }

    private static String modeToString(int mode) {
        char[] chars = new char[9];
        int[] bits = {0x100, 0x80, 0x40, 0x20, 0x10, 0x8, 0x4, 0x2, 0x1};
        char[] letters = {'r', 'w', 'x', 'r', 'w', 'x', 'r', 'w', 'x'};
        for (int i = 0; i < 9; i++) {
            chars[i] = (mode & bits[i]) != 0 ? letters[i] : '-';
        }
        return new String(chars);
    }

    public static boolean isDir(File file) {
        try {
            StructStat stat = stat(file);
            return isDirMode(stat.st_mode);
        } catch (ErrnoException e) {
            return file.isDirectory();
        }
    }

    private static boolean isDirMode(int mode) {
        return (mode & 0x4000) == 0x4000;
    }

    public static boolean isSymlink(File file) {
        try {
            StructStat stat = Os.lstat(file.getAbsolutePath());
            return (stat.st_mode & 0xA000) == 0xA000;
        } catch (ErrnoException e) {
            return false;
        }
    }

    private static StructStat stat(File file) throws ErrnoException {
        return Os.stat(file.getAbsolutePath());
    }

    /** True when we could stat the file (i.e. it exists / is readable at metadata level). */
    public static boolean exists(File file) {
        try {
            stat(file);
            return true;
        } catch (ErrnoException e) {
            return false;
        }
    }

    /** Parse "rwxr-xr-x" or octal ("755" / "0755") into a unix mode, or -1 on error. */
    public static int parseMode(String input) {
        if (input == null) return -1;
        String text = input.trim();

        // Octal
        if (text.matches("0?[0-7]{3}")) {
            try {
                return Integer.parseInt(text, 8);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        // Symbolic 9 characters (optionally prefixed with the type char)
        if (text.length() == 10 && (text.charAt(0) == 'd' || text.charAt(0) == '-'
            || text.charAt(0) == 'l' || text.charAt(0) == 'b' || text.charAt(0) == 'c')) {
            text = text.substring(1);
        }
        if (text.length() != 9) return -1;

        int mode = 0;
        int[] bits = {0x100, 0x80, 0x40, 0x20, 0x10, 0x8, 0x4, 0x2, 0x1};
        for (int i = 0; i < 9; i++) {
            char c = text.charAt(i);
            if (c == 'r' || c == 'w' || c == 'x') {
                mode |= bits[i];
            } else if (c != '-') {
                return -1;
            }
        }
        return mode;
    }

    /** Change permissions on a file. Returns null on success or a friendly message. */
    public static String chmod(File file, String modeInput) {
        int mode = parseMode(modeInput);
        if (mode < 0) return "Invalid permissions. Use something like \"rwxr-xr-x\" or \"755\".";
        try {
            Os.chmod(file.getAbsolutePath(), mode);
            return null;
        } catch (ErrnoException e) {
            return "Could not change permissions: " + friendlyErrno(e);
        }
    }

    public static String formatDate(long millis) {
        return DATE_FORMAT.format(new Date(millis));
    }

    /** Human readable size: 1.5 KB, 3.2 MB ... */
    public static String humanSize(long bytes) {
        if (bytes < 0) return "-";
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }

    public static String friendlyErrno(ErrnoException e) {
        String msg = e.getMessage();
        return msg == null ? "operation failed" : msg;
    }

    /** Extension without dot, lower case, or "" for none. */
    public static String extension(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }
}
