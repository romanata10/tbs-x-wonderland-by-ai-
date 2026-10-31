package dev.forgebridge.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** File and stream helpers (no third-party dependencies, Java 7 compatible). */
public final class IoUtil {

    public static final Charset UTF8 = Charset.forName("UTF-8");

    private IoUtil() {
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(4096, in.available()));
        copy(in, out);
        return out.toByteArray();
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[16384];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
    }

    public static String readText(InputStream in) throws IOException {
        return new String(readAll(in), UTF8);
    }

    public static byte[] readFile(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            return readAll(in);
        } finally {
            closeQuietly(in);
        }
    }

    public static String readFileText(File file) throws IOException {
        return new String(readFile(file), UTF8);
    }

    public static void writeFile(File file, byte[] data) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(temp);
        try {
            out.write(data);
            out.flush();
        } finally {
            closeQuietly(out);
        }
        if (file.exists() && !file.delete()) {
            throw new IOException("Could not replace " + file);
        }
        if (!temp.renameTo(file)) {
            // Fall back to a plain copy when the rename crosses a mount point.
            FileOutputStream direct = new FileOutputStream(file);
            try {
                direct.write(data);
            } finally {
                closeQuietly(direct);
            }
            temp.delete();
        }
    }

    public static void writeFileText(File file, String text) throws IOException {
        writeFile(file, text.getBytes(UTF8));
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (int i = 0; i < hash.length; i++) {
                int value = hash[i] & 0xFF;
                if (value < 16) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(value));
            }
            return sb.toString();
        } catch (Exception error) {
            return "unknown";
        }
    }

    public static List<File> listFiles(File dir, String[] suffixes) {
        List<File> result = new ArrayList<File>();
        File[] children = dir == null ? null : dir.listFiles();
        if (children == null) {
            return result;
        }
        Arrays.sort(children);
        for (int i = 0; i < children.length; i++) {
            File child = children[i];
            if (!child.isFile()) {
                continue;
            }
            if (suffixes == null) {
                result.add(child);
                continue;
            }
            String name = child.getName().toLowerCase(java.util.Locale.ROOT);
            for (int s = 0; s < suffixes.length; s++) {
                if (name.endsWith(suffixes[s])) {
                    result.add(child);
                    break;
                }
            }
        }
        return result;
    }

    public static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (int i = 0; i < children.length; i++) {
                deleteRecursively(children[i]);
            }
        }
        if (!file.delete()) {
            file.deleteOnExit();
        }
    }

    public static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    public static String baseName(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
