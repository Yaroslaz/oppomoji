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
 * The space stays, narrowed to a text width: under Dai the next font with a space is Noto Sans Myanmar,
 * whose tall line metrics stretch every line that contains one.
 */
final class EmojiSlot {
    /** Width of the Roboto space, the text font next in the chain under Dai. */
    static final float SPACE_EM = 507 / 2048f;

    private EmojiSlot() { }

    /** Characters the text font draws: ASCII, Latin-1, general punctuation, ™, ℹ and the simple arrows. */
    static boolean textLike(int cp) {
        if (cp == 0x200D || cp == 0x20E3) return false; // Zero-width joiner and the keycap are emoji structure.
        if (cp == 0x20 || cp == 0xA0) return false; // Spaces have no bitmap: they draw from hmtx alone.
        return cp < 0x2100 || cp == 0x2122 || cp == 0x2139 || cp >= 0x2194 && cp <= 0x2199 || cp == 0x21A9 || cp == 0x21AA;
    }

    /** Copy of the emoji font with a reduced cmap and the given underline (font units). */
    static byte[] build(SfntFont emoji, int underlinePosition, int underlineThickness) throws IOException {
        if (!emoji.hasTable("CBDT") && !emoji.hasTable("COLR")) throw new IOException("Нужен цветной emoji-шрифт");
        Map<String, byte[]> tables = emoji.copies();
        Integer space = FontTables.mapping(tables.get("cmap")).get(0x20);
        boolean narrowed = space != null && setAdvance(tables.get("hhea"), tables.get("hmtx"), space, Math.round(SPACE_EM * emoji.unitsPerEm()));
        tables.put("cmap", cmap(tables.get("cmap"), narrowed));
        byte[] post = tables.get("post");
        if (post != null && post.length >= 12) ByteBuffer.wrap(post).putShort(8, (short) underlinePosition).putShort(10, (short) underlineThickness);
        byte[] mvar = tables.get("MVAR");
        if (mvar != null) tables.remove("MVAR"); // Its underline deltas would override the chosen line.
        return SfntFont.pack(emoji.signature(), tables);
    }

    /** Sets one glyph's advance in place; false when hmtx shares the last advance and cannot hold it. */
    static boolean setAdvance(byte[] hhea, byte[] hmtx, int glyph, int advance) {
        if (hhea == null || hmtx == null || hhea.length < 36) return false;
        int metrics = ByteBuffer.wrap(hhea).getShort(34) & 0xFFFF;
        if (glyph >= metrics || hmtx.length < glyph * 4 + 4) return false;
        ByteBuffer.wrap(hmtx).putShort(glyph * 4, (short) advance);
        return true;
    }

    /** The reduced cmap; with {@code spaces} the space glyph also serves U+0020 and U+00A0. */
    static byte[] cmap(byte[] original, boolean spaces) throws IOException {
        TreeMap<Integer, Integer> mapping = FontTables.mapping(original), kept = new TreeMap<>();
        TreeMap<Integer, Integer> removed = new TreeMap<>();
        for (Map.Entry<Integer, Integer> e : mapping.entrySet()) {
            boolean text = textLike(e.getKey()) || !spaces && (e.getKey() == 0x20 || e.getKey() == 0xA0);
            (text ? removed : kept).put(e.getKey(), e.getValue());
        }
        if (spaces && kept.containsKey(0x20)) kept.putIfAbsent(0xA0, kept.get(0x20));
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
