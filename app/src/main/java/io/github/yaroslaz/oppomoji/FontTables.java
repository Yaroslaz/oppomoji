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
 * OpenType table helpers: Unicode cmap (formats 4, 12, 14) and a GSUB/GPOS rebuild behind Extension
 * subtables. Android cannot draw glyf outlines from a font that also has CBDT/CBLC (FreeType treats it
 * as bitmap-only), so text and colour emoji stay in separate files.
 */
final class FontTables {
    static final int VS15 = 0xFE0E, VS16 = 0xFE0F;

    private FontTables() { }

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

    // ---------------------------------------------------------------- GSUB

    private static final class Layout {
        final byte[] data; final ByteBuffer b; final int scripts, features, lookups;
        Layout(byte[] data) {
            this.data = data; b = ByteBuffer.wrap(data);
            scripts = b.getShort(4) & 0xffff; features = b.getShort(6) & 0xffff; lookups = b.getShort(8) & 0xffff;
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

    /**
     * Rebuilds a GSUB (extension lookup type 7) or GPOS (type 9) with every lookup behind an Extension
     * subtable, so the original table is copied verbatim whatever its size, and the features listed in
     * {@code alternates} use those lookups. This bakes a FeatureVariations record and drops the variations.
     */
    static byte[] layout(byte[] table, int extension, Map<Integer, int[]> alternates) throws IOException {
        Layout text = new Layout(table);
        int lookupCount = text.lookupCount();
        List<String> tags = new ArrayList<>();
        List<List<Integer>> lookups = new ArrayList<>();
        for (int i = 0; i < text.featureCount(); i++) {
            tags.add(text.featureTag(i));
            int[] chosen = alternates.containsKey(i) ? alternates.get(i) : text.featureLookups(i);
            List<Integer> list = new ArrayList<>(); for (int l : chosen) list.add(l); lookups.add(list);
        }
        List<Script> scripts = readScripts(text);

        // Every lookup becomes an Extension lookup pointing into a verbatim copy of the original table.
        List<int[]> lookupRefs = new ArrayList<>(); // type, flag, filtering set, then target offsets
        for (int i = 0; i < lookupCount; i++) lookupRefs.add(lookupRef(text, i, extension));

        ByteArrayOutputStream scriptList = scriptList(scripts), featureList = featureList(tags, lookups);
        int scriptAt = 10, featureAt = scriptAt + scriptList.size(), lookupAt = featureAt + featureList.size();
        ByteArrayOutputStream lookupList = new ByteArrayOutputStream();
        int lookupHeader = 2 + lookupRefs.size() * 2;
        ByteArrayOutputStream bodies = new ByteArrayOutputStream();
        List<int[]> extensionFixups = new ArrayList<>(); // position in bodies, absolute target in the copy
        int[] lookupOffsets = new int[lookupRefs.size()];
        for (int i = 0; i < lookupRefs.size(); i++) {
            int[] ref = lookupRefs.get(i);
            int subtables = ref.length - 3;
            lookupOffsets[i] = lookupHeader + bodies.size();
            int tableLength = 6 + subtables * 2 + ((ref[1] & 0x10) != 0 ? 2 : 0);
            write16(bodies, extension); write16(bodies, ref[1]); write16(bodies, subtables);
            for (int k = 0; k < subtables; k++) write16(bodies, tableLength + k * 8);
            if ((ref[1] & 0x10) != 0) write16(bodies, ref[2]);
            for (int k = 0; k < subtables; k++) {
                int at = bodies.size();
                write16(bodies, 1); write16(bodies, ref[0]); write32(bodies, 0);
                extensionFixups.add(new int[]{at, ref[3 + k]});
            }
        }
        if (lookupHeader + bodies.size() > 65535 || lookupAt > 65535) throw new IOException("Слишком много правил GSUB/GPOS");
        write16(lookupList, lookupRefs.size());
        for (int offset : lookupOffsets) write16(lookupList, offset);
        int bodiesAt = lookupAt + lookupList.size(), copyAt = bodiesAt + bodies.size();
        ByteBuffer out = ByteBuffer.allocate(copyAt + table.length);
        out.putInt(0x00010000).putShort((short) scriptAt).putShort((short) featureAt).putShort((short) lookupAt);
        out.position(scriptAt); out.put(scriptList.toByteArray()); out.put(featureList.toByteArray()); out.put(lookupList.toByteArray());
        byte[] body = bodies.toByteArray();
        ByteBuffer bodyBuffer = ByteBuffer.wrap(body);
        for (int[] fix : extensionFixups) bodyBuffer.putInt(fix[0] + 4, copyAt + fix[1] - (bodiesAt + fix[0]));
        out.put(body).put(table);
        return out.array();
    }

    /** {type, flag, markFilteringSet, subtable targets...}; Extension lookups are unwrapped. */
    private static int[] lookupRef(Layout layout, int index, int extension) throws IOException {
        int lookup = layout.lookup(index);
        int type = layout.u16(lookup), flag = layout.u16(lookup + 2), count = layout.u16(lookup + 4);
        int[] ref = new int[3 + count];
        ref[1] = flag; ref[2] = (flag & 0x10) != 0 ? layout.u16(lookup + 6 + count * 2) : 0;
        int realType = type;
        for (int k = 0; k < count; k++) {
            int sub = lookup + layout.u16(lookup + 6 + k * 2);
            if (type == extension) {
                realType = layout.u16(sub + 2);
                ref[3 + k] = sub + layout.b.getInt(sub + 4);
            } else ref[3 + k] = sub;
            if (ref[3 + k] < 0 || ref[3 + k] >= layout.data.length) throw new IOException("Повреждена таблица " + (extension == 7 ? "GSUB" : "GPOS"));
        }
        ref[0] = realType;
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
}
