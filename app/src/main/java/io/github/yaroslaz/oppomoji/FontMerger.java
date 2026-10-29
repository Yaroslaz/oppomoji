package io.github.yaroslaz.oppomoji;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * One font for a ColorOS custom-font slot: letters, digits, spaces, punctuation and metrics from a
 * static TrueType text font, colour emoji (CBDT/CBLC with their GSUB sequences) from an emoji font.
 * The slot makes its font primary, so an emoji-only font there replaced digits and spaces as well.
 */
final class FontMerger {
    static final int VS15 = 0xFE0E, VS16 = 0xFE0F;

    /** Codepoints that the emoji font must own even when the text font has a glyph for them. */
    interface EmojiFirst { boolean test(int codePoint); }

    /** Structural emoji characters and default-emoji blocks; Emoji_Presentation from ICU adds the rest on Android. */
    static boolean structural(int cp) {
        return cp == 0x200D || cp == 0x20E3 || cp == VS16 || (cp >= 0xE0020 && cp <= 0xE007F)
                || (cp >= 0x1F1E6 && cp <= 0x1F1FF) || (cp >= 0x1F3FB && cp <= 0x1F3FF) || cp >= 0x1F000 && cp <= 0x1FAFF;
    }

    private FontMerger() { }

    static byte[] merge(SfntFont text, SfntFont emoji, EmojiFirst emojiFirst) throws IOException {
        Map<String, byte[]> tables = text.copies(), colour = emoji.copies();
        for (String tag : new String[]{"glyf", "loca", "head", "hhea", "hmtx", "maxp", "cmap"}) {
            if (!tables.containsKey(tag)) throw new IOException("Текстовый шрифт должен быть TrueType: нет таблицы " + tag);
        }
        if (tables.containsKey("fvar") || tables.containsKey("gvar")) throw new IOException("Сначала зафиксируй вес вариативного шрифта");
        for (String tag : new String[]{"CBDT", "CBLC", "cmap", "hmtx", "hhea", "maxp", "head"}) {
            if (!colour.containsKey(tag)) throw new IOException("Нужен emoji-шрифт CBDT/CBLC: нет таблицы " + tag);
        }
        ByteBuffer head = ByteBuffer.wrap(tables.get("head")), hhea = ByteBuffer.wrap(tables.get("hhea"));
        int textGlyphs = ByteBuffer.wrap(tables.get("maxp")).getShort(4) & 0xffff;
        int emojiGlyphs = ByteBuffer.wrap(colour.get("maxp")).getShort(4) & 0xffff;
        int total = textGlyphs + emojiGlyphs;
        if (total > 65535) throw new IOException("Слишком много глифов для одного шрифта: " + total);
        int textUpem = head.getShort(18) & 0xffff, emojiUpem = ByteBuffer.wrap(colour.get("head")).getShort(18) & 0xffff;

        // glyf stays as is: emoji glyphs are empty outlines whose pictures live in CBDT.
        int[] loca = VariableInstancer.readLoca(tables.get("loca"), textGlyphs, head.getShort(50) != 0);
        ByteBuffer newLoca = ByteBuffer.allocate((total + 1) * 4);
        for (int g = 0; g <= textGlyphs; g++) newLoca.putInt(loca[g]);
        for (int g = 0; g < emojiGlyphs; g++) newLoca.putInt(loca[textGlyphs]);
        tables.put("loca", newLoca.array());
        head.putShort(50, (short) 1);

        int[] advance = new int[textGlyphs], lsb = new int[textGlyphs];
        VariableInstancer.readMetrics(tables.get("hmtx"), hhea.getShort(34) & 0xffff, advance, lsb);
        int[] emojiAdvance = new int[emojiGlyphs], emojiLsb = new int[emojiGlyphs];
        VariableInstancer.readMetrics(colour.get("hmtx"), ByteBuffer.wrap(colour.get("hhea")).getShort(34) & 0xffff, emojiAdvance, emojiLsb);
        ByteBuffer hmtx = ByteBuffer.allocate(total * 4);
        int maxAdvance = hhea.getShort(10) & 0xffff;
        for (int g = 0; g < textGlyphs; g++) hmtx.putShort((short) advance[g]).putShort((short) lsb[g]);
        for (int g = 0; g < emojiGlyphs; g++) {
            int width = VariableInstancer.round(emojiAdvance[g] * (double) textUpem / emojiUpem);
            hmtx.putShort((short) width).putShort((short) VariableInstancer.round(emojiLsb[g] * (double) textUpem / emojiUpem));
            maxAdvance = Math.max(maxAdvance, width);
        }
        tables.put("hmtx", hmtx.array());
        hhea.putShort(10, (short) maxAdvance).putShort(34, (short) total);
        ByteBuffer.wrap(tables.get("maxp")).putShort(4, (short) total);
        byte[] post = tables.get("post");
        if (post != null && post.length >= 32) { // Glyph names would no longer match the glyph count.
            byte[] names = java.util.Arrays.copyOf(post, 32); ByteBuffer.wrap(names).putInt(0, 0x00030000); tables.put("post", names);
        }
        for (String tag : new String[]{"hdmx", "LTSH", "VDMX", "vhea", "vmtx", "DSIG"}) tables.remove(tag);

        tables.put("cmap", cmap(tables.get("cmap"), colour.get("cmap"), textGlyphs, emojiFirst));
        tables.put("GSUB", gsub(tables.get("GSUB"), colour.get("GSUB"), textGlyphs));
        tables.put("CBDT", colour.get("CBDT"));
        tables.put("CBLC", shiftCblc(colour.get("CBLC"), textGlyphs));
        return SfntFont.pack(text.signature(), tables);
    }

    // ---------------------------------------------------------------- cmap

    /** Best Unicode mapping of a cmap: format 12 if present, else format 4. */
    static TreeMap<Integer, Integer> mapping(byte[] cmap) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(cmap);
        int count = b.getShort(2) & 0xffff, best = -1, bestScore = -1;
        if (4L + count * 8L > cmap.length) throw new IOException("Повреждена таблица cmap");
        for (int i = 0; i < count; i++) {
            int platform = b.getShort(4 + i * 8) & 0xffff, encoding = b.getShort(6 + i * 8) & 0xffff;
            long offset = b.getInt(8 + i * 8) & 0xffffffffL;
            if (offset + 2 > cmap.length) continue;
            int format = b.getShort((int) offset) & 0xffff;
            boolean unicode = platform == 0 && encoding != 5 && encoding != 14 || platform == 3 && (encoding == 1 || encoding == 10);
            int score = !unicode ? -1 : format == 12 ? 2 : format == 4 ? 1 : -1;
            if (score > bestScore) { bestScore = score; best = (int) offset; }
        }
        if (best < 0) throw new IOException("В шрифте нет Unicode-таблицы cmap");
        TreeMap<Integer, Integer> result = new TreeMap<>();
        int format = b.getShort(best) & 0xffff;
        if (format == 12) {
            long groups = b.getInt(best + 12) & 0xffffffffL;
            if (best + 16 + groups * 12 > cmap.length) throw new IOException("Повреждена таблица cmap");
            for (int gIndex = 0; gIndex < groups; gIndex++) {
                int g = best + 16 + gIndex * 12;
                long start = b.getInt(g) & 0xffffffffL, end = b.getInt(g + 4) & 0xffffffffL, glyph = b.getInt(g + 8) & 0xffffffffL;
                if (end < start || end > 0x10FFFF) continue;
                for (long cp = start; cp <= end; cp++) if (glyph + cp - start != 0) result.put((int) cp, (int) (glyph + cp - start));
            }
        } else {
            int length = b.getShort(best + 2) & 0xffff, segments = (b.getShort(best + 6) & 0xffff) / 2;
            if (best + length > cmap.length || 16 + segments * 8 > length) throw new IOException("Повреждена таблица cmap");
            int ends = best + 14, starts = ends + segments * 2 + 2, deltas = starts + segments * 2, ranges = deltas + segments * 2;
            for (int s = 0; s < segments; s++) {
                int start = b.getShort(starts + s * 2) & 0xffff, end = b.getShort(ends + s * 2) & 0xffff;
                int delta = b.getShort(deltas + s * 2), range = b.getShort(ranges + s * 2) & 0xffff;
                for (int cp = start; cp <= end && cp != 0xFFFF; cp++) {
                    int glyph;
                    if (range == 0) glyph = (cp + delta) & 0xffff;
                    else {
                        int at = ranges + s * 2 + range + 2 * (cp - start);
                        if (at + 2 > best + length) continue;
                        glyph = b.getShort(at) & 0xffff;
                        if (glyph != 0) glyph = (glyph + delta) & 0xffff;
                    }
                    if (glyph != 0) result.put(cp, glyph);
                }
            }
        }
        return result;
    }

    /** Variation selector -> (default UVS code points, non-default code point -> glyph). */
    static final class Sequences {
        final TreeMap<Integer, TreeSet<Integer>> defaults = new TreeMap<>();
        final TreeMap<Integer, TreeMap<Integer, Integer>> mapped = new TreeMap<>();
        TreeSet<Integer> defaults(int selector) { return defaults.computeIfAbsent(selector, k -> new TreeSet<>()); }
        TreeMap<Integer, Integer> mapped(int selector) { return mapped.computeIfAbsent(selector, k -> new TreeMap<>()); }
    }

    static Sequences sequences(byte[] cmap) {
        Sequences result = new Sequences();
        ByteBuffer b = ByteBuffer.wrap(cmap);
        int count = b.getShort(2) & 0xffff;
        for (int i = 0; i < count && 4 + i * 8 + 8 <= cmap.length; i++) {
            long offset = b.getInt(8 + i * 8) & 0xffffffffL;
            if (offset + 10 > cmap.length || (b.getShort((int) offset) & 0xffff) != 14) continue;
            int sub = (int) offset;
            long length = b.getInt(sub + 2) & 0xffffffffL, records = b.getInt(sub + 6) & 0xffffffffL;
            if (sub + length > cmap.length || 10 + records * 11 > length) continue;
            for (int r = 0; r < records; r++) {
                int record = sub + 10 + r * 11;
                int selector = u24(b, record);
                long defaults = b.getInt(record + 3) & 0xffffffffL, mapped = b.getInt(record + 7) & 0xffffffffL;
                if (defaults != 0 && defaults + 4 <= length) {
                    int table = (int) (sub + defaults); long ranges = b.getInt(table) & 0xffffffffL;
                    for (int k = 0; k < ranges && table + 8L + k * 4L <= sub + length; k++) {
                        int start = u24(b, table + 4 + k * 4), extra = b.get(table + 7 + k * 4) & 0xff;
                        for (int cp = start; cp <= start + extra; cp++) result.defaults(selector).add(cp);
                    }
                }
                if (mapped != 0 && mapped + 4 <= length) {
                    int table = (int) (sub + mapped); long mappings = b.getInt(table) & 0xffffffffL;
                    for (int k = 0; k < mappings && table + 9L + k * 5L <= sub + length; k++) {
                        result.mapped(selector).put(u24(b, table + 4 + k * 5), b.getShort(table + 7 + k * 5) & 0xffff);
                    }
                }
            }
        }
        return result;
    }

    private static byte[] cmap(byte[] textCmap, byte[] emojiCmap, int offset, EmojiFirst emojiFirst) throws IOException {
        TreeMap<Integer, Integer> text = mapping(textCmap), emoji = mapping(emojiCmap), merged = new TreeMap<>(text);
        for (Map.Entry<Integer, Integer> e : emoji.entrySet()) {
            int cp = e.getKey();
            if (!text.containsKey(cp) || emojiFirst.test(cp)) merged.put(cp, e.getValue() + offset);
        }
        Sequences fromText = sequences(textCmap), fromEmoji = sequences(emojiCmap), out = new Sequences();
        // Text font sequences other than U+FE0F keep their glyphs, as default when the base still maps there.
        for (Map.Entry<Integer, TreeSet<Integer>> e : fromText.defaults.entrySet()) {
            if (e.getKey() == VS16) continue;
            for (int cp : e.getValue()) add(out, e.getKey(), cp, text.get(cp), merged);
        }
        for (Map.Entry<Integer, TreeMap<Integer, Integer>> e : fromText.mapped.entrySet()) {
            if (e.getKey() == VS16) continue;
            for (Map.Entry<Integer, Integer> m : e.getValue().entrySet()) add(out, e.getKey(), m.getKey(), m.getValue(), merged);
        }
        // Emoji presentation always reaches the colour picture: "©️" and "1️⃣" while "©" and "1" stay text.
        for (Map.Entry<Integer, TreeSet<Integer>> e : fromEmoji.defaults.entrySet()) {
            for (int cp : e.getValue()) {
                Integer glyph = emoji.get(cp);
                if (glyph != null && (e.getKey() == VS16 || !out.mapped(e.getKey()).containsKey(cp))) add(out, e.getKey(), cp, glyph + offset, merged);
            }
        }
        for (Map.Entry<Integer, TreeMap<Integer, Integer>> e : fromEmoji.mapped.entrySet()) {
            for (Map.Entry<Integer, Integer> m : e.getValue().entrySet()) add(out, e.getKey(), m.getKey(), m.getValue() + offset, merged);
        }
        // An emoji-first base the text font can draw keeps a text presentation through U+FE0E.
        for (Map.Entry<Integer, Integer> e : text.entrySet()) {
            int cp = e.getKey();
            Integer now = merged.get(cp);
            if (now != null && now >= offset && !out.defaults(VS15).contains(cp)) out.mapped(VS15).putIfAbsent(cp, e.getValue());
        }
        return writeCmap(merged, out);
    }

    private static void add(Sequences out, int selector, int cp, Integer glyph, Map<Integer, Integer> merged) {
        if (glyph == null || glyph == 0) return;
        Integer base = merged.get(cp);
        out.defaults(selector).remove(cp); out.mapped(selector).remove(cp);
        if (base != null && base.equals(glyph)) out.defaults(selector).add(cp); else out.mapped(selector).put(cp, glyph);
    }

    static byte[] writeCmap(TreeMap<Integer, Integer> map, Sequences sequences) throws IOException {
        byte[] format4 = format4(map), format12 = format12(map), format14 = format14(sequences);
        boolean hasSequences = format14 != null;
        int records = hasSequences ? 3 : 2, header = 4 + records * 8;
        ByteBuffer out = ByteBuffer.allocate(header + format4.length + format12.length + (hasSequences ? format14.length : 0));
        out.putShort((short) 0).putShort((short) records);
        int position = header;
        if (hasSequences) { out.putShort((short) 0).putShort((short) 5).putInt(position + format4.length + format12.length); }
        out.putShort((short) 3).putShort((short) 1).putInt(position);
        out.putShort((short) 3).putShort((short) 10).putInt(position + format4.length);
        out.position(header); out.put(format4).put(format12);
        if (hasSequences) out.put(format14);
        return out.array();
    }

    private static byte[] format4(TreeMap<Integer, Integer> map) throws IOException {
        List<int[]> segments = new ArrayList<>(); // start, end, delta
        int[] current = null;
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            int cp = e.getKey(), glyph = e.getValue();
            if (cp >= 0xFFFF) break;
            if (current != null && cp == current[1] + 1 && ((glyph - cp) & 0xffff) == current[2]) current[1] = cp;
            else { current = new int[]{cp, cp, (glyph - cp) & 0xffff}; segments.add(current); }
        }
        segments.add(new int[]{0xFFFF, 0xFFFF, 1});
        int count = segments.size(), length = 16 + count * 8;
        if (length > 65535) throw new IOException("Слишком большая таблица cmap формата 4");
        ByteBuffer b = ByteBuffer.allocate(length);
        int power = Integer.highestOneBit(count);
        b.putShort((short) 4).putShort((short) length).putShort((short) 0).putShort((short) (count * 2))
                .putShort((short) (power * 2)).putShort((short) Integer.numberOfTrailingZeros(power)).putShort((short) ((count - power) * 2));
        for (int[] s : segments) b.putShort((short) s[1]);
        b.putShort((short) 0);
        for (int[] s : segments) b.putShort((short) s[0]);
        for (int[] s : segments) b.putShort((short) s[2]);
        for (int i = 0; i < count; i++) b.putShort((short) 0);
        return b.array();
    }

    private static byte[] format12(TreeMap<Integer, Integer> map) {
        List<int[]> groups = new ArrayList<>(); // start, end, first glyph
        int[] current = null;
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            int cp = e.getKey(), glyph = e.getValue();
            if (current != null && cp == current[1] + 1 && glyph == current[2] + cp - current[0]) current[1] = cp;
            else { current = new int[]{cp, cp, glyph}; groups.add(current); }
        }
        ByteBuffer b = ByteBuffer.allocate(16 + groups.size() * 12);
        b.putShort((short) 12).putShort((short) 0).putInt(16 + groups.size() * 12).putInt(0).putInt(groups.size());
        for (int[] g : groups) b.putInt(g[0]).putInt(g[1]).putInt(g[2]);
        return b.array();
    }

    private static byte[] format14(Sequences sequences) {
        TreeSet<Integer> selectors = new TreeSet<>();
        for (Map.Entry<Integer, TreeSet<Integer>> e : sequences.defaults.entrySet()) if (!e.getValue().isEmpty()) selectors.add(e.getKey());
        for (Map.Entry<Integer, TreeMap<Integer, Integer>> e : sequences.mapped.entrySet()) if (!e.getValue().isEmpty()) selectors.add(e.getKey());
        if (selectors.isEmpty()) return null;
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int headerLength = 10 + selectors.size() * 11;
        ByteBuffer header = ByteBuffer.allocate(headerLength);
        header.putShort((short) 14).putInt(0).putInt(selectors.size());
        for (int selector : selectors) {
            TreeSet<Integer> defaults = sequences.defaults.getOrDefault(selector, new TreeSet<>());
            TreeMap<Integer, Integer> mapped = sequences.mapped.getOrDefault(selector, new TreeMap<>());
            int defaultOffset = 0, mappedOffset = 0;
            if (!defaults.isEmpty()) {
                defaultOffset = headerLength + data.size();
                List<int[]> ranges = new ArrayList<>();
                for (int cp : defaults) {
                    int[] last = ranges.isEmpty() ? null : ranges.get(ranges.size() - 1);
                    if (last != null && cp == last[0] + last[1] + 1 && last[1] < 255) last[1]++; else ranges.add(new int[]{cp, 0});
                }
                write32(data, ranges.size());
                for (int[] r : ranges) { write24(data, r[0]); data.write(r[1]); }
            }
            if (!mapped.isEmpty()) {
                mappedOffset = headerLength + data.size();
                write32(data, mapped.size());
                for (Map.Entry<Integer, Integer> m : mapped.entrySet()) { write24(data, m.getKey()); data.write((m.getValue() >> 8) & 0xff); data.write(m.getValue() & 0xff); }
            }
            header.put((byte) (selector >> 16)).put((byte) (selector >> 8)).put((byte) selector).putInt(defaultOffset).putInt(mappedOffset);
        }
        byte[] body = data.toByteArray();
        ByteBuffer out = ByteBuffer.allocate(headerLength + body.length);
        out.put(header.array()).put(body);
        out.putInt(2, headerLength + body.length);
        return out.array();
    }

    private static int u24(ByteBuffer b, int at) { return (b.get(at) & 0xff) << 16 | (b.get(at + 1) & 0xff) << 8 | b.get(at + 2) & 0xff; }
    private static void write24(ByteArrayOutputStream out, int v) { out.write((v >> 16) & 0xff); out.write((v >> 8) & 0xff); out.write(v & 0xff); }
    private static void write32(ByteArrayOutputStream out, int v) { out.write(v >>> 24); out.write((v >> 16) & 0xff); out.write((v >> 8) & 0xff); out.write(v & 0xff); }

    // ---------------------------------------------------------------- CBLC

    static byte[] shiftCblc(byte[] original, int offset) throws IOException {
        byte[] cblc = original.clone();
        ByteBuffer b = ByteBuffer.wrap(cblc);
        long sizes = b.getInt(4) & 0xffffffffL;
        if (8 + sizes * 48 > cblc.length) throw new IOException("Повреждена таблица CBLC");
        for (int s = 0; s < sizes; s++) {
            int record = 8 + s * 48;
            int arrayOffset = b.getInt(record), tables = b.getInt(record + 8);
            b.putShort(record + 40, (short) ((b.getShort(record + 40) & 0xffff) + offset));
            b.putShort(record + 42, (short) ((b.getShort(record + 42) & 0xffff) + offset));
            if (arrayOffset + (long) tables * 8 > cblc.length) throw new IOException("Повреждена таблица CBLC");
            for (int t = 0; t < tables; t++) {
                int entry = arrayOffset + t * 8;
                int first = b.getShort(entry) & 0xffff, last = b.getShort(entry + 2) & 0xffff;
                b.putShort(entry, (short) (first + offset)).putShort(entry + 2, (short) (last + offset));
                int sub = arrayOffset + b.getInt(entry + 4);
                if (sub + 8 > cblc.length) throw new IOException("Повреждена таблица CBLC");
                int format = b.getShort(sub) & 0xffff;
                if (format == 4) {
                    long glyphs = b.getInt(sub + 8) & 0xffffffffL;
                    for (int k = 0; k <= glyphs; k++) {
                        int at = sub + 12 + k * 4;
                        if (k < glyphs) b.putShort(at, (short) ((b.getShort(at) & 0xffff) + offset));
                    }
                } else if (format == 5) {
                    long glyphs = b.getInt(sub + 8 + 4 + 8) & 0xffffffffL;
                    for (int k = 0; k < glyphs; k++) {
                        int at = sub + 24 + k * 2;
                        b.putShort(at, (short) ((b.getShort(at) & 0xffff) + offset));
                    }
                } else if (format < 1 || format > 5) throw new IOException("Неизвестный формат индекса CBLC " + format);
            }
        }
        return cblc;
    }

    // ---------------------------------------------------------------- GSUB

    private static final class Layout {
        final byte[] data; final ByteBuffer b; final int scripts, features, lookups, variations;
        Layout(byte[] data) {
            this.data = data; b = ByteBuffer.wrap(data);
            scripts = b.getShort(4) & 0xffff; features = b.getShort(6) & 0xffff; lookups = b.getShort(8) & 0xffff;
            variations = b.getInt(0) == 0x00010001 && data.length >= 14 ? b.getInt(10) : 0;
        }
        int u16(int at) { return b.getShort(at) & 0xffff; }
        int lookupCount() { return lookups == 0 ? 0 : u16(lookups); }
        int lookup(int index) { return lookups + u16(lookups + 2 + index * 2); }
        int featureCount() { return features == 0 ? 0 : u16(features); }
        String featureTag(int index) { return new String(data, features + 2 + index * 6, 4, StandardCharsets.ISO_8859_1); }
        int[] featureLookups(int index) {
            int table = features + u16(features + 2 + index * 6 + 4), count = u16(table + 2);
            int[] result = new int[count];
            for (int i = 0; i < count; i++) result[i] = u16(table + 4 + i * 2);
            return result;
        }
    }

    private static final class LangSys {
        final String tag; int required; final List<Integer> features = new ArrayList<>();
        LangSys(String tag) { this.tag = tag; }
    }

    private static final class Script {
        final String tag; LangSys fallback; final List<LangSys> languages = new ArrayList<>();
        Script(String tag) { this.tag = tag; }
    }

    static byte[] gsub(byte[] textGsub, byte[] emojiGsub, int glyphOffset) throws IOException {
        if (emojiGsub == null) return textGsub;
        return layout(textGsub, 7, null, emojiGsub, glyphOffset);
    }

    /**
     * Rebuilds a GSUB (extension type 7) or GPOS (type 9) with every lookup behind an Extension
     * subtable, so the original tables can be copied verbatim whatever their size. Optional parts:
     * alternate lookup lists per feature index (a baked FeatureVariations record, which then drops the
     * variations) and a second GSUB whose glyphs are shifted by glyphOffset.
     */
    static byte[] layout(byte[] textGsub, int extension, Map<Integer, int[]> alternates, byte[] emojiGsub, int glyphOffset) throws IOException {
        Layout text = textGsub == null ? null : new Layout(textGsub);
        int textLookups = text == null ? 0 : text.lookupCount();
        byte[] patched = emojiGsub == null ? new byte[0] : emojiGsub.clone();
        Layout emoji = emojiGsub == null ? null : new Layout(patched);
        if (emoji != null) new Renumber(patched, glyphOffset, textLookups).lookups(emoji);

        // Features: text features (indices unchanged), emoji lookups joined into text features of the same tag,
        // and a standalone emoji feature per tag for language systems without that tag.
        List<String> tags = new ArrayList<>();
        List<List<Integer>> lookups = new ArrayList<>();
        if (text != null) for (int i = 0; i < text.featureCount(); i++) {
            tags.add(text.featureTag(i));
            int[] chosen = alternates != null && alternates.containsKey(i) ? alternates.get(i) : text.featureLookups(i);
            List<Integer> list = new ArrayList<>(); for (int l : chosen) list.add(l); lookups.add(list);
        }
        TreeMap<String, TreeSet<Integer>> emojiFeatures = new TreeMap<>();
        for (int i = 0; emoji != null && i < emoji.featureCount(); i++) {
            TreeSet<Integer> set = emojiFeatures.computeIfAbsent(emoji.featureTag(i), k -> new TreeSet<>());
            for (int l : emoji.featureLookups(i)) set.add(l + textLookups);
        }
        Map<String, Integer> standalone = new TreeMap<>();
        for (Map.Entry<String, TreeSet<Integer>> e : emojiFeatures.entrySet()) {
            for (int i = 0; i < tags.size(); i++) if (tags.get(i).equals(e.getKey())) lookups.get(i).addAll(e.getValue());
            standalone.put(e.getKey(), tags.size());
            tags.add(e.getKey()); lookups.add(new ArrayList<>(e.getValue()));
        }

        boolean variations = text != null && text.variations != 0 && alternates == null;
        List<Script> scripts = text == null ? new ArrayList<>() : readScripts(text);
        Script dflt = null, latn = null;
        for (Script s : scripts) { if (s.tag.equals("DFLT")) dflt = s; if (s.tag.equals("latn")) latn = s; }
        if (dflt == null && emoji != null) { // Emoji runs use the default script.
            dflt = new Script("DFLT"); dflt.fallback = new LangSys("dflt");
            dflt.fallback.required = 0xFFFF;
            if (latn != null && latn.fallback != null) dflt.fallback.features.addAll(latn.fallback.features);
            scripts.add(0, dflt);
        }
        for (Script s : scripts) {
            List<LangSys> all = new ArrayList<>(s.languages);
            if (s.fallback == null && emoji != null) { s.fallback = new LangSys("dflt"); s.fallback.required = 0xFFFF; }
            if (s.fallback != null) all.add(s.fallback);
            for (LangSys l : all) {
                Set<String> present = new HashSet<>();
                for (int f : l.features) present.add(tags.get(f));
                if (l.required != 0xFFFF) present.add(tags.get(l.required));
                for (Map.Entry<String, Integer> e : standalone.entrySet()) if (!present.contains(e.getKey())) l.features.add(e.getValue());
            }
        }

        // Keep FeatureList alphabetical unless FeatureVariations refer to the original feature indices.
        if (!variations) {
            Integer[] order = new Integer[tags.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            java.util.Arrays.sort(order, (x, y) -> tags.get(x).compareTo(tags.get(y)));
            int[] moved = new int[order.length];
            List<String> sortedTags = new ArrayList<>(); List<List<Integer>> sortedLookups = new ArrayList<>();
            for (int i = 0; i < order.length; i++) { moved[order[i]] = i; sortedTags.add(tags.get(order[i])); sortedLookups.add(lookups.get(order[i])); }
            tags.clear(); tags.addAll(sortedTags); lookups.clear(); lookups.addAll(sortedLookups);
            for (Script s : scripts) {
                List<LangSys> all = new ArrayList<>(s.languages);
                if (s.fallback != null) all.add(s.fallback);
                for (LangSys l : all) {
                    if (l.required != 0xFFFF) l.required = moved[l.required];
                    for (int k = 0; k < l.features.size(); k++) l.features.set(k, moved[l.features.get(k)]);
                    java.util.Collections.sort(l.features);
                }
            }
        }

        // Every lookup becomes an Extension lookup pointing into verbatim copies of both original tables.
        List<int[]> lookupRefs = new ArrayList<>(); // table (0 text, 1 emoji), type, flag, filtering set, then target offsets
        if (text != null) for (int i = 0; i < textLookups; i++) lookupRefs.add(lookupRef(text, 0, i, extension));
        for (int i = 0; emoji != null && i < emoji.lookupCount(); i++) lookupRefs.add(lookupRef(emoji, 1, i, 7));

        ByteArrayOutputStream scriptList = scriptList(scripts), featureList = featureList(tags, lookups);
        int headerLength = variations ? 14 : 10;
        int scriptAt = headerLength, featureAt = scriptAt + scriptList.size(), lookupAt = featureAt + featureList.size();
        ByteArrayOutputStream lookupList = new ByteArrayOutputStream();
        int lookupHeader = 2 + lookupRefs.size() * 2;
        ByteArrayOutputStream bodies = new ByteArrayOutputStream();
        List<int[]> extensionFixups = new ArrayList<>(); // position in bodies, table, absolute target
        int[] lookupOffsets = new int[lookupRefs.size()];
        for (int i = 0; i < lookupRefs.size(); i++) {
            int[] ref = lookupRefs.get(i);
            int subtables = ref.length - 4;
            lookupOffsets[i] = lookupHeader + bodies.size();
            int tableLength = 6 + subtables * 2 + ((ref[2] & 0x10) != 0 ? 2 : 0);
            write16(bodies, extension); write16(bodies, ref[2]); write16(bodies, subtables);
            for (int k = 0; k < subtables; k++) write16(bodies, tableLength + k * 8);
            if ((ref[2] & 0x10) != 0) write16(bodies, ref[3]);
            for (int k = 0; k < subtables; k++) {
                int at = bodies.size();
                write16(bodies, 1); write16(bodies, ref[1]); write32(bodies, 0);
                extensionFixups.add(new int[]{at, ref[0], ref[4 + k]});
            }
        }
        if (lookupHeader + bodies.size() > 65535 || lookupAt > 65535) throw new IOException("Слишком много правил GSUB для объединения");
        write16(lookupList, lookupRefs.size());
        for (int offset : lookupOffsets) write16(lookupList, offset);
        int bodiesAt = lookupAt + lookupList.size();
        int textAt = bodiesAt + bodies.size(), emojiAt = textAt + (textGsub == null ? 0 : textGsub.length);
        int length = emojiAt + patched.length;
        ByteBuffer out = ByteBuffer.allocate(length);
        out.putInt(variations ? 0x00010001 : 0x00010000).putShort((short) scriptAt).putShort((short) featureAt).putShort((short) lookupAt);
        if (variations) out.putInt(textAt + text.variations);
        out.position(scriptAt); out.put(scriptList.toByteArray()); out.put(featureList.toByteArray()); out.put(lookupList.toByteArray());
        byte[] body = bodies.toByteArray();
        ByteBuffer bodyBuffer = ByteBuffer.wrap(body);
        for (int[] fix : extensionFixups) {
            int record = bodiesAt + fix[0];
            int target = (fix[1] == 0 ? textAt : emojiAt) + fix[2];
            bodyBuffer.putInt(fix[0] + 4, target - record);
        }
        out.put(body);
        if (textGsub != null) out.put(textGsub);
        out.put(patched);
        return out.array();
    }

    /** {table, type, flag, markFilteringSet, subtable targets...}; Extension lookups are unwrapped. */
    private static int[] lookupRef(Layout layout, int table, int index, int extension) throws IOException {
        int lookup = layout.lookup(index);
        int type = layout.u16(lookup), flag = layout.u16(lookup + 2), count = layout.u16(lookup + 4);
        int[] ref = new int[4 + count];
        ref[0] = table; ref[2] = flag; ref[3] = (flag & 0x10) != 0 ? layout.u16(lookup + 6 + count * 2) : 0;
        int realType = type;
        for (int k = 0; k < count; k++) {
            int sub = lookup + layout.u16(lookup + 6 + k * 2);
            if (type == extension) {
                realType = layout.u16(sub + 2);
                ref[4 + k] = sub + layout.b.getInt(sub + 4);
            } else ref[4 + k] = sub;
            if (ref[4 + k] < 0 || ref[4 + k] >= layout.data.length) throw new IOException("Повреждена таблица GSUB");
        }
        ref[1] = realType;
        return ref;
    }

    private static List<Script> readScripts(Layout layout) {
        List<Script> result = new ArrayList<>();
        if (layout.scripts == 0) return result;
        int list = layout.scripts, count = layout.u16(list);
        for (int i = 0; i < count; i++) {
            Script script = new Script(new String(layout.data, list + 2 + i * 6, 4, StandardCharsets.ISO_8859_1));
            int table = list + layout.u16(list + 2 + i * 6 + 4);
            int fallback = layout.u16(table);
            if (fallback != 0) script.fallback = readLangSys(layout, "dflt", table + fallback);
            int languages = layout.u16(table + 2);
            for (int k = 0; k < languages; k++) {
                String tag = new String(layout.data, table + 4 + k * 6, 4, StandardCharsets.ISO_8859_1);
                script.languages.add(readLangSys(layout, tag, table + layout.u16(table + 4 + k * 6 + 4)));
            }
            result.add(script);
        }
        return result;
    }

    private static LangSys readLangSys(Layout layout, String tag, int at) {
        LangSys result = new LangSys(tag);
        result.required = layout.u16(at + 2);
        int count = layout.u16(at + 4);
        for (int i = 0; i < count; i++) result.features.add(layout.u16(at + 6 + i * 2));
        return result;
    }

    private static ByteArrayOutputStream scriptList(List<Script> scripts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<byte[]> tables = new ArrayList<>();
        for (Script s : scripts) {
            ByteArrayOutputStream t = new ByteArrayOutputStream();
            int headerLength = 4 + s.languages.size() * 6;
            List<byte[]> langs = new ArrayList<>();
            for (LangSys l : s.languages) langs.add(langSys(l));
            byte[] fallback = s.fallback == null ? null : langSys(s.fallback);
            int position = headerLength;
            write16(t, fallback == null ? 0 : position);
            if (fallback != null) position += fallback.length;
            write16(t, s.languages.size());
            for (int i = 0; i < langs.size(); i++) {
                t.write(s.languages.get(i).tag.getBytes(StandardCharsets.ISO_8859_1), 0, 4);
                write16(t, position); position += langs.get(i).length;
            }
            if (fallback != null) t.write(fallback, 0, fallback.length);
            for (byte[] l : langs) t.write(l, 0, l.length);
            tables.add(t.toByteArray());
        }
        write16(out, scripts.size());
        int position = 2 + scripts.size() * 6;
        for (int i = 0; i < scripts.size(); i++) {
            out.write(scripts.get(i).tag.getBytes(StandardCharsets.ISO_8859_1), 0, 4);
            write16(out, position); position += tables.get(i).length;
        }
        for (byte[] t : tables) out.write(t, 0, t.length);
        return out;
    }

    private static byte[] langSys(LangSys l) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write16(out, 0); write16(out, l.required); write16(out, l.features.size());
        for (int f : l.features) write16(out, f);
        return out.toByteArray();
    }

    private static ByteArrayOutputStream featureList(List<String> tags, List<List<Integer>> lookups) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write16(out, tags.size());
        int position = 2 + tags.size() * 6;
        List<byte[]> tables = new ArrayList<>();
        for (int i = 0; i < tags.size(); i++) {
            ByteArrayOutputStream t = new ByteArrayOutputStream();
            TreeSet<Integer> sorted = new TreeSet<>(lookups.get(i));
            write16(t, 0); write16(t, sorted.size());
            for (int l : sorted) write16(t, l);
            tables.add(t.toByteArray());
            out.write(tags.get(i).getBytes(StandardCharsets.ISO_8859_1), 0, 4);
            write16(out, position); position += tables.get(i).length;
        }
        for (byte[] t : tables) out.write(t, 0, t.length);
        return out;
    }

    private static void write16(ByteArrayOutputStream out, int v) { out.write((v >> 8) & 0xff); out.write(v & 0xff); }

    /** Shifts every glyph ID and lookup index inside a copied GSUB, visiting shared subtables once. */
    private static final class Renumber {
        final ByteBuffer b; final int glyphs, lookups; final Set<Long> visited = new HashSet<>();
        Renumber(byte[] data, int glyphs, int lookups) { b = ByteBuffer.wrap(data); this.glyphs = glyphs; this.lookups = lookups; }

        int u16(int at) { return b.getShort(at) & 0xffff; }
        boolean once(int kind, int at) { return visited.add(((long) kind << 32) | at); }
        void glyph(int at) { b.putShort(at, (short) (u16(at) + glyphs)); }
        void glyphArray(int at, int count) { for (int i = 0; i < count; i++) glyph(at + i * 2); }

        void lookups(Layout layout) throws IOException {
            for (int i = 0; i < layout.lookupCount(); i++) {
                int lookup = layout.lookup(i), type = u16(lookup), count = u16(lookup + 4);
                for (int k = 0; k < count; k++) {
                    int sub = lookup + u16(lookup + 6 + k * 2), realType = type;
                    if (type == 7) { realType = u16(sub + 2); sub += b.getInt(sub + 4); }
                    subtable(realType, sub);
                }
            }
        }

        void coverage(int at) {
            if (!once(1, at)) return;
            int format = u16(at), count = u16(at + 2);
            if (format == 1) glyphArray(at + 4, count);
            else for (int i = 0; i < count; i++) { glyph(at + 4 + i * 6); glyph(at + 6 + i * 6); }
        }

        void classDef(int at) {
            if (!once(2, at)) return;
            int format = u16(at);
            if (format == 1) glyph(at + 2);
            else { int count = u16(at + 2); for (int i = 0; i < count; i++) { glyph(at + 4 + i * 6); glyph(at + 6 + i * 6); } }
        }

        void records(int at, int count) { for (int i = 0; i < count; i++) b.putShort(at + i * 4 + 2, (short) (u16(at + i * 4 + 2) + lookups)); }

        void subtable(int type, int at) throws IOException {
            if (!once(3, at)) return;
            int format = u16(at);
            switch (type) {
                case 1:
                    coverage(at + u16(at + 2));
                    if (format == 2) glyphArray(at + 6, u16(at + 4));
                    break;
                case 2: case 3: {
                    coverage(at + u16(at + 2));
                    int count = u16(at + 4);
                    for (int i = 0; i < count; i++) { int seq = at + u16(at + 6 + i * 2); if (once(4, seq)) glyphArray(seq + 2, u16(seq)); }
                    break;
                }
                case 4: {
                    coverage(at + u16(at + 2));
                    int sets = u16(at + 4);
                    for (int i = 0; i < sets; i++) {
                        int set = at + u16(at + 6 + i * 2), ligatures = u16(set);
                        for (int k = 0; k < ligatures; k++) {
                            int lig = set + u16(set + 2 + k * 2);
                            if (!once(4, lig)) continue;
                            glyph(lig); glyphArray(lig + 4, u16(lig + 2) - 1);
                        }
                    }
                    break;
                }
                case 5:
                    if (format == 1) {
                        coverage(at + u16(at + 2));
                        int sets = u16(at + 4);
                        for (int i = 0; i < sets; i++) {
                            int set = at + u16(at + 6 + i * 2), rules = u16(set);
                            for (int k = 0; k < rules; k++) {
                                int rule = set + u16(set + 2 + k * 2);
                                if (!once(4, rule)) continue;
                                int glyphCount = u16(rule), substitutions = u16(rule + 2);
                                glyphArray(rule + 4, glyphCount - 1); records(rule + 4 + (glyphCount - 1) * 2, substitutions);
                            }
                        }
                    } else if (format == 2) {
                        coverage(at + u16(at + 2)); classDef(at + u16(at + 4));
                        int sets = u16(at + 6);
                        for (int i = 0; i < sets; i++) {
                            int offset = u16(at + 8 + i * 2);
                            if (offset == 0) continue;
                            int set = at + offset, rules = u16(set);
                            for (int k = 0; k < rules; k++) {
                                int rule = set + u16(set + 2 + k * 2);
                                if (once(4, rule)) records(rule + 4 + (u16(rule) - 1) * 2, u16(rule + 2));
                            }
                        }
                    } else if (format == 3) {
                        int glyphCount = u16(at + 2), substitutions = u16(at + 4);
                        for (int i = 0; i < glyphCount; i++) coverage(at + u16(at + 6 + i * 2));
                        records(at + 6 + glyphCount * 2, substitutions);
                    }
                    break;
                case 6:
                    if (format == 1) {
                        coverage(at + u16(at + 2));
                        int sets = u16(at + 4);
                        for (int i = 0; i < sets; i++) {
                            int set = at + u16(at + 6 + i * 2), rules = u16(set);
                            for (int k = 0; k < rules; k++) {
                                int rule = set + u16(set + 2 + k * 2);
                                if (!once(4, rule)) continue;
                                int p = rule, back = u16(p); glyphArray(p + 2, back); p += 2 + back * 2;
                                int input = u16(p); glyphArray(p + 2, input - 1); p += 2 + (input - 1) * 2;
                                int ahead = u16(p); glyphArray(p + 2, ahead); p += 2 + ahead * 2;
                                records(p + 2, u16(p));
                            }
                        }
                    } else if (format == 2) {
                        coverage(at + u16(at + 2));
                        for (int c = 0; c < 3; c++) { int offset = u16(at + 4 + c * 2); if (offset != 0) classDef(at + offset); }
                        int sets = u16(at + 10);
                        for (int i = 0; i < sets; i++) {
                            int offset = u16(at + 12 + i * 2);
                            if (offset == 0) continue;
                            int set = at + offset, rules = u16(set);
                            for (int k = 0; k < rules; k++) {
                                int rule = set + u16(set + 2 + k * 2);
                                if (!once(4, rule)) continue;
                                int p = rule; p += 2 + u16(p) * 2; p += 2 + (u16(p) - 1) * 2; p += 2 + u16(p) * 2;
                                records(p + 2, u16(p));
                            }
                        }
                    } else if (format == 3) {
                        int p = at + 2;
                        for (int part = 0; part < 3; part++) {
                            int count = u16(p);
                            for (int i = 0; i < count; i++) coverage(at + u16(p + 2 + i * 2));
                            p += 2 + count * 2;
                        }
                        records(p + 2, u16(p));
                    }
                    break;
                case 8: {
                    coverage(at + u16(at + 2));
                    int p = at + 4;
                    for (int part = 0; part < 2; part++) {
                        int count = u16(p);
                        for (int i = 0; i < count; i++) coverage(at + u16(p + 2 + i * 2));
                        p += 2 + count * 2;
                    }
                    glyphArray(p + 2, u16(p));
                    break;
                }
                default:
                    throw new IOException("Неизвестный тип правила GSUB " + type);
            }
        }
    }
}
