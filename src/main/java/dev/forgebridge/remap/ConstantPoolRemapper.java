package dev.forgebridge.remap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Dependency-free class file rewriter.
 *
 * <p>Parses the constant pool, substitutes package prefixes inside
 * {@code CONSTANT_Utf8} entries and re-emits the class. Because every name in a class
 * file (this class, super class, interfaces, descriptors, signatures, annotation values,
 * string constants) is ultimately a UTF-8 constant, a byte-level prefix substitution is
 * enough to relocate a class from {@code net/minecraftforge} to
 * {@code net.neoforged} without touching a single instruction.</p>
 *
 * <p>What it deliberately does <em>not</em> do is member renaming
 * ({@code ITEMS -> ITEM}) or call-site redirection; those need the ASM pipeline. The
 * engine reports the level it used so the caller can warn about the difference.</p>
 */
public final class ConstantPoolRemapper {

    private static final int MAGIC = 0xCAFEBABE;

    /** A byte sequence replacement, applied to UTF-8 constants only. */
    public static final class Replacement {
        private final byte[] from;
        private final byte[] to;

        public Replacement(String from, String to) {
            this.from = from.getBytes(java.nio.charset.Charset.forName("UTF-8"));
            this.to = to.getBytes(java.nio.charset.Charset.forName("UTF-8"));
        }

        public byte[] getFrom() {
            return from;
        }

        public byte[] getTo() {
            return to;
        }
    }

    private ConstantPoolRemapper() {
    }

    /**
     * Builds the replacement list from a mapping table: every prefix rule plus every
     * exact class rule, longest source first so that specific rules win.
     */
    public static List<Replacement> replacementsFor(MappingTable table) {
        List<Replacement> list = new ArrayList<Replacement>();
        for (int i = 0; i < table.getPrefixes().size(); i++) {
            MappingTable.PrefixRule rule = (MappingTable.PrefixRule) table.getPrefixes().get(i);
            list.add(new Replacement(rule.getFrom(), rule.getTo()));
        }
        for (Object element : table.getExactClasses().entrySet()) {
            java.util.Map.Entry entry = (java.util.Map.Entry) element;
            list.add(new Replacement(String.valueOf(entry.getKey()), String.valueOf(entry.getValue())));
        }
        // Sort by descending source length (simple insertion sort; lists are tiny).
        for (int i = 1; i < list.size(); i++) {
            Replacement current = (Replacement) list.get(i);
            int j = i - 1;
            while (j >= 0 && ((Replacement) list.get(j)).getFrom().length < current.getFrom().length) {
                list.set(j + 1, list.get(j));
                j--;
            }
            list.set(j + 1, current);
        }
        return list;
    }

    public static byte[] remap(byte[] input, List<Replacement> replacements) throws IOException {
        if (input == null || input.length < 10) {
            throw new IOException("Not a class file (too short)");
        }
        int magic = readInt(input, 0);
        if (magic != MAGIC) {
            throw new IOException("Not a class file (bad magic 0x" + Integer.toHexString(magic) + ")");
        }
        int minorMajor = 8;
        int poolCount = readUnsignedShort(input, 8);
        ByteArrayOutputStream pool = new ByteArrayOutputStream(input.length + 256);
        int offset = 10;
        boolean changed = false;

        for (int index = 1; index < poolCount; index++) {
            int tag = input[offset] & 0xFF;
            switch (tag) {
                case 1: { // CONSTANT_Utf8
                    int length = readUnsignedShort(input, offset + 1);
                    byte[] original = new byte[length];
                    System.arraycopy(input, offset + 3, original, 0, length);
                    byte[] rewritten = substitute(original, replacements);
                    if (rewritten != original) {
                        changed = true;
                    }
                    pool.write(1);
                    writeUnsignedShort(pool, rewritten.length);
                    pool.write(rewritten, 0, rewritten.length);
                    offset += 3 + length;
                    break;
                }
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: {
                    copy(input, pool, offset, 5);
                    offset += 5;
                    break;
                }
                case 5: case 6: {
                    copy(input, pool, offset, 9);
                    offset += 9;
                    index++; // long/double occupy two slots
                    break;
                }
                case 7: case 8: case 16: case 19: case 20: {
                    copy(input, pool, offset, 3);
                    offset += 3;
                    break;
                }
                case 15: {
                    copy(input, pool, offset, 4);
                    offset += 4;
                    break;
                }
                default:
                    throw new IOException("Unknown constant pool tag " + tag + " at " + offset);
            }
        }

        byte[] rewrittenPool = pool.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length + 256);
        out.write(input, 0, 8); // magic + minor + major
        writeUnsignedShort(out, poolCount);
        out.write(rewrittenPool, 0, rewrittenPool.length);
        out.write(input, offset, input.length - offset);

        if (!changed) {
            return input;
        }
        return out.toByteArray();
    }

    private static byte[] substitute(byte[] value, List<Replacement> replacements) {
        byte[] current = value;
        for (int i = 0; i < replacements.size(); i++) {
            Replacement replacement = (Replacement) replacements.get(i);
            byte[] replaced = substitute(current, replacement.getFrom(), replacement.getTo());
            if (replaced != null) {
                current = replaced;
            }
        }
        return current == value ? value : current;
    }

    private static byte[] substitute(byte[] value, byte[] from, byte[] to) {
        int index = indexOf(value, from, 0);
        if (index < 0) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length + to.length);
        int cursor = 0;
        while (index >= 0) {
            out.write(value, cursor, index - cursor);
            out.write(to, 0, to.length);
            cursor = index + from.length;
            index = indexOf(value, from, cursor);
        }
        out.write(value, cursor, value.length - cursor);
        return out.toByteArray();
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        if (needle.length == 0) {
            return -1;
        }
        int limit = haystack.length - needle.length;
        outer:
        for (int i = Math.max(from, 0); i <= limit; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static void copy(byte[] input, ByteArrayOutputStream out, int offset, int length) {
        out.write(input, offset, length);
    }

    static int readUnsignedShort(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    private static void writeUnsignedShort(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}
