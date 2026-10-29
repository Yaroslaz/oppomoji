package io.github.yaroslaz.oppomoji;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * An emoji font for the ColorOS Dai slot, which makes its file the primary system font. A colour emoji
 * font maps its own space, digits, "#", "*", "©" and similar characters; there they replaced the text.
 * This copy hands those characters to the next font in the chain and keeps their emoji presentation
 * ("©️", "1️⃣", "#️⃣") through explicit U+FE0F sequences in cmap format 14.
 */
final class EmojiSlot {
    private EmojiSlot() { }

    /** Characters the text font draws: ASCII, Latin-1, general punctuation, ™, ℹ and the simple arrows. */
    static boolean textLike(int cp) {
        if (cp == 0x200D || cp == 0x20E3) return false; // Zero-width joiner and the keycap are emoji structure.
        return cp < 0x2100 || cp == 0x2122 || cp == 0x2139 || cp >= 0x2194 && cp <= 0x2199 || cp == 0x21A9 || cp == 0x21AA;
    }

    /** Copy of the emoji font with a reduced cmap and the given underline (font units). */
    static byte[] build(SfntFont emoji, int underlinePosition, int underlineThickness) throws IOException {
        if (!emoji.hasTable("CBDT") && !emoji.hasTable("COLR")) throw new IOException("Нужен цветной emoji-шрифт");
        Map<String, byte[]> tables = emoji.copies();
        tables.put("cmap", cmap(tables.get("cmap")));
        byte[] post = tables.get("post");
        if (post != null && post.length >= 12) ByteBuffer.wrap(post).putShort(8, (short) underlinePosition).putShort(10, (short) underlineThickness);
        byte[] mvar = tables.get("MVAR");
        if (mvar != null) tables.remove("MVAR"); // Its underline deltas would override the chosen line.
        return SfntFont.pack(emoji.signature(), tables);
    }

    static byte[] cmap(byte[] original) throws IOException {
        TreeMap<Integer, Integer> mapping = FontTables.mapping(original), kept = new TreeMap<>();
        TreeMap<Integer, Integer> removed = new TreeMap<>();
        for (Map.Entry<Integer, Integer> e : mapping.entrySet()) (textLike(e.getKey()) ? removed : kept).put(e.getKey(), e.getValue());
        FontTables.Sequences in = FontTables.sequences(original), out = new FontTables.Sequences();
        for (Map.Entry<Integer, TreeSet<Integer>> e : in.defaults.entrySet()) {
            for (int cp : e.getValue()) {
                if (kept.containsKey(cp)) out.defaults(e.getKey()).add(cp);
                else if (removed.containsKey(cp) && e.getKey() == FontTables.VS16) out.mapped(e.getKey()).put(cp, removed.get(cp));
            }
        }
        for (Map.Entry<Integer, TreeMap<Integer, Integer>> e : in.mapped.entrySet()) out.mapped(e.getKey()).putAll(e.getValue());
        return FontTables.writeCmap(kept, out);
    }
}
