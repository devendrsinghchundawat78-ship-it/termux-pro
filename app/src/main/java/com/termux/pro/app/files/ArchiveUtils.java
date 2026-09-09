package com.termux.pro.app.files;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Archive support for the file manager, implemented with only Android built-ins:
 *  - .zip   : create + extract (java.util.zip)
 *  - .tar, .tar.gz, .tgz, .tar.bz2? : extract .tar/.tar.gz/.tgz with an internal
 *            ustar parser + GZIPInputStream (bzip2 needs a Termux package, see below)
 *
 * Safety: entries that try to escape the target directory ("..", absolute paths)
 * are skipped, so extracting a malicious archive can not overwrite files elsewhere.
 */
public final class ArchiveUtils {

    private ArchiveUtils() {}

    /** Human readable result of an operation. */
    public static final class Result {
        public final boolean ok;
        public final String message;

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    private static Result ok(String message) {
        return new Result(true, message);
    }

    private static Result fail(String message) {
        return new Result(false, message);
    }

    // ==================================================================================
    // ZIP
    // ==================================================================================

    public static boolean isZip(File file) {
        // Cheap signature check: PK\x03\x04
        try (InputStream in = new FileInputStream(file)) {
            byte[] magic = new byte[4];
            return in.read(magic) == 4 && magic[0] == 'P' && magic[1] == 'K'
                && magic[2] == 0x03 && magic[3] == 0x04;
        } catch (IOException e) {
            return false;
        }
    }

    /** Extract a .zip into {@code targetDir}. */
    public static Result extractZip(File zipFile, File targetDir) {
        try {
            if (!targetDir.exists() && !targetDir.mkdirs()) return fail("Could not create destination folder.");
            int count = 0;
            int skipped = 0;
            try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    File out = safeResolve(targetDir, entry.getName());
                    if (out == null) { skipped++; continue; }

                    if (entry.isDirectory()) {
                        if (!out.exists()) out.mkdirs();
                        continue;
                    }

                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (OutputStream os = new FileOutputStream(out)) {
                        copy(zis, os);
                    }
                    count++;
                }
            }
            return ok("Extracted " + count + " file(s)"
                + (skipped > 0 ? " (" + skipped + " unsafe entries skipped)" : ""));
        } catch (IOException e) {
            return fail("Extraction failed: " + friendly(e));
        }
    }

    /** Create a .zip containing {@code source} (file or folder, recursive). */
    public static Result compressZip(File source, File zipFile) {
        if (source == null || !FileOps.exists(source)) return fail("Source does not exist.");
        try {
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
                addToZip(zos, source, source.isFile() ? "" : source.getName());
            }
            return ok("Created " + zipFile.getName());
        } catch (IOException e) {
            return fail("Compression failed: " + friendly(e));
        }
    }

    private static void addToZip(ZipOutputStream zos, File file, String relativePath) throws IOException {
        if (file.isDirectory()) {
            if (!relativePath.isEmpty()) {
                zos.putNextEntry(new ZipEntry(relativePath.endsWith("/") ? relativePath : relativePath + "/"));
                zos.closeEntry();
            }
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    addToZip(zos, child, relativePath.isEmpty() ? child.getName() : relativePath + "/" + child.getName());
                }
            }
        } else {
            zos.putNextEntry(new ZipEntry(relativePath));
            try (InputStream in = new FileInputStream(file)) {
                copy(in, zos);
            }
            zos.closeEntry();
        }
    }

    // ==================================================================================
    // TAR / TAR.GZ
    // ==================================================================================

    public static boolean isTar(File file) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] header = new byte[512];
            if (in.read(header) != 512) return false;
            String magic = new String(header, 257, 6, "ISO-8859-1");
            return magic.equals("ustar ") || magic.equals("ustar\0");
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean isTarGz(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] magic = new byte[2];
            return in.read(magic) == 2 && magic[0] == (byte) 0x1f && magic[1] == (byte) 0x8b;
        } catch (IOException e) {
            return false;
        }
    }

    /** Extract .tar, .tar.gz or .tgz into {@code targetDir}. */
    public static Result extractTar(File tarFile, File targetDir) {
        try (InputStream raw = new FileInputStream(tarFile)) {
            InputStream in = raw;
            if (isTarGz(tarFile)) {
                in = new GZIPInputStream(new BufferedInputStream(raw), 64 * 1024);
            } else {
                in = new BufferedInputStream(raw, 64 * 1024);
            }
            return extractTarStream(in, targetDir);
        } catch (IOException e) {
            return fail("Extraction failed: " + friendly(e));
        }
    }

    private static Result extractTarStream(InputStream in, File targetDir) throws IOException {
        if (!targetDir.exists() && !targetDir.mkdirs()) return fail("Could not create destination folder.");

        int count = 0;
        int skipped = 0;
        String pendingLongName = null;

        byte[] header = new byte[512];
        while (true) {
            int read = readFully(in, header);
            if (read == -1) break;          // clean end
            if (read < 512) return fail("Archive appears truncated.");

            // All-zero block = end of archive (two blocks; one is enough for us)
            boolean zeroBlock = true;
            for (byte b : header) {
                if (b != 0) { zeroBlock = false; break; }
            }
            if (zeroBlock) break;

            String name = parseOctalString(header, 0, 100);
            long size = parseOctal(header, 124, 12);
            byte typeFlag = header[156];

            // GNU / POSIX long name handling
            if (typeFlag == 'L' || typeFlag == 'x' || typeFlag == 'g') {
                byte[] data = new byte[(int) Math.min(size, 1 << 20)];
                readFully(in, data);
                skipPadding(in, size);
                if (typeFlag == 'L') {
                    pendingLongName = new String(data, 0, (int) Math.min(size, data.length), "UTF-8").trim();
                }
                continue;
            }

            String entryName = pendingLongName != null ? pendingLongName : name;
            pendingLongName = null;

            // ustar prefix field (names longer than 100 chars)
            if (entryName == null || entryName.isEmpty()) {
                String prefix = parseOctalString(header, 345, 155);
                if (!prefix.isEmpty()) entryName = prefix + "/" + entryName;
            }

            File out = safeResolve(targetDir, entryName == null ? "" : entryName);
            if (out == null) { skipPadding(in, size); skipped++; continue; }

            if (typeFlag == '5' || (out.getName().endsWith("/") && typeFlag == 0)) {
                if (!out.exists()) out.mkdirs();
            } else if (typeFlag == '2' || typeFlag == '1') {
                // symlink / hardlink: skip writing, but still consume the data
                skipPadding(in, size);
                skipped++;
            } else {
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                try (OutputStream os = new FileOutputStream(out)) {
                    long remaining = size;
                    byte[] buffer = new byte[64 * 1024];
                    while (remaining > 0) {
                        int want = (int) Math.min(buffer.length, remaining);
                        int got = in.read(buffer, 0, want);
                        if (got < 0) return fail("Archive appears truncated.");
                        os.write(buffer, 0, got);
                        remaining -= got;
                    }
                }
                count++;
            }
            skipPadding(in, size);
        }

        return ok("Extracted " + count + " item(s)"
            + (skipped > 0 ? " (" + skipped + " links/unsafe entries skipped)" : ""));
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long padding = (512 - (size % 512)) % 512;
        skipFully(in, padding);
    }

    // ==================================================================================
    // Shared helpers
    // ==================================================================================

    /**
     * Resolve an archive entry name safely inside {@code baseDir}.
     * Returns null when the entry is unsafe (absolute path or ".." traversal).
     */
    private static File safeResolve(File baseDir, String entryName) {
        if (entryName == null || entryName.isEmpty()) return null;
        String cleaned = entryName.replace('\\', '/');
        if (cleaned.startsWith("/")) return null;

        List<String> parts = new ArrayList<>();
        for (String part : cleaned.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) return null; // never allow going up
            parts.add(part);
        }
        if (parts.isEmpty()) return null;

        File target = baseDir;
        for (String part : parts) {
            target = new File(target, part);
        }
        // Final double check
        String basePath = baseDir.getAbsolutePath();
        String targetPath = target.getAbsolutePath();
        if (!targetPath.equals(basePath) && !targetPath.startsWith(basePath + File.separator)) return null;
        return target;
    }

    private static String parseOctalString(byte[] header, int offset, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = offset; i < offset + length && i < header.length; i++) {
            if (header[i] == 0) break;
            sb.append((char) (header[i] & 0xFF));
        }
        return sb.toString().trim();
    }

    private static long parseOctal(byte[] header, int offset, int length) {
        long value = 0;
        for (int i = offset; i < offset + length && i < header.length; i++) {
            byte b = header[i];
            if (b == 0 || b == ' ' || b == '\0') continue;
            if (b < '0' || b > '7') break;
            value = (value << 3) | (b - '0');
        }
        return value;
    }

    /** Reads up to {@code buffer.length} bytes; returns -1 at EOF or the bytes read. */
    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = in.read(buffer, total, buffer.length - total);
            if (read == -1) return total == 0 ? -1 : total;
            total += read;
        }
        return total;
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) return; // EOF
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private static String friendly(IOException e) {
        String msg = e.getMessage();
        return msg == null ? e.toString() : msg;
    }
}
