package io.github.yaroslaz.oppomoji;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded SFNT reader and copy-only post-table editor. No glyph rewriting. */
public final class SfntFont {
    public static final int MAX_BYTES = 64 * 1024 * 1024;
    private static final long CHECKSUM_MAGIC = 0xB1B0AFBAL;
    private final byte[] source;
    private final Map<String, Table> tables = new LinkedHashMap<>();

    private static final class Table {
        final int offset, length;
        Table(int offset, int length) { this.offset = offset; this.length = length; }
    }

    public static final class Axis {
        public final String tag;
        public final float min, defaultValue, max;
        Axis(String tag, float min, float defaultValue, float max) {
            this.tag = tag; this.min = min; this.defaultValue = defaultValue; this.max = max;
        }
        @Override public String toString() {
            return tag + ": " + min + " … " + max + " (" + defaultValue + ")";
        }
    }

    public SfntFont(byte[] bytes) throws IOException {
        if (bytes.length < 12 || bytes.length > MAX_BYTES) throw invalid("Размер файла шрифта недопустим");
        source = bytes.clone();
        int signature = buffer().getInt(0);
        if (signature == 0x74746366) throw invalid("Это коллекция TTC. Выбери отдельный TTF/OTF");
        if (signature != 0x00010000 && signature != 0x4F54544F && signature != 0x74727565) {
            throw invalid("Нужен распакованный TTF/OTF, не APK, ZIP или WOFF");
        }
        int count = u16(4);
        if (count == 0 || count > 256 || 12L + 16L * count > source.length) throw invalid("Повреждён каталог таблиц");
        List<Table> occupied = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int record = 12 + 16 * i;
            String tag = tag(record);
            long offset = u32(record + 8), length = u32(record + 12);
            if (offset < 12L + count * 16L || offset + length > source.length || offset % 4 != 0) {
                throw invalid("Повреждена таблица " + tag);
            }
            Table table = new Table((int) offset, (int) length);
            if (tables.put(tag, table) != null) throw invalid("Повтор таблицы " + tag);
            for (Table previous : occupied) {
                if (length > 0 && previous.length > 0 && offset < (long) previous.offset + previous.length
                        && previous.offset < offset + length) throw invalid("Таблицы перекрываются");
            }
            occupied.add(table);
        }
        Table head = require("head", 54);
        if (buffer().getInt(head.offset + 12) != 0x5F0F3CF5) throw invalid("Некорректная таблица head");
        if (unitsPerEm() < 16 || unitsPerEm() > 16384) throw invalid("Некорректный unitsPerEm");
    }

    private ByteBuffer buffer() { return ByteBuffer.wrap(source).order(ByteOrder.BIG_ENDIAN); }
    private int u16(int offset) { return buffer().getShort(offset) & 0xffff; }
    private long u32(int offset) { return buffer().getInt(offset) & 0xffffffffL; }
    private int s16(int offset) { return buffer().getShort(offset); }
    private String tag(int offset) { return new String(source, offset, 4, StandardCharsets.ISO_8859_1); }
    private IOException invalid(String message) { return new IOException(message); }
    private Table require(String name, int minimum) throws IOException {
        Table table = tables.get(name);
        if (table == null || table.length < minimum) throw invalid("Нет корректной таблицы " + name);
        return table;
    }
    public boolean hasTable(String tag) { return tables.containsKey(tag); }
    public List<String> tableTags() { return new ArrayList<>(tables.keySet()); }
    public int unitsPerEm() { return u16(tables.get("head").offset + 18); }
    public int underlinePosition() throws IOException { return s16(require("post", 12).offset + 8); }
    public int underlineThickness() throws IOException { return s16(require("post", 12).offset + 10); }
    public int ascent() { Table t = tables.get("hhea"); return t == null || t.length < 10 ? 0 : s16(t.offset + 4); }
    public int descent() { Table t = tables.get("hhea"); return t == null || t.length < 10 ? 0 : s16(t.offset + 6); }

    public String familyName() {
        Table name = tables.get("name");
        if (name == null || name.length < 6) return "Без названия";
        int count = u16(name.offset + 2), strings = u16(name.offset + 4);
        if (6L + 12L * count > name.length) return "Без названия";
        String fallback = null;
        for (int i = 0; i < count; i++) {
            int record = name.offset + 6 + i * 12;
            int platform = u16(record), id = u16(record + 6);
            int length = u16(record + 8), offset = u16(record + 10);
            long start = (long) name.offset + strings + offset;
            if ((id != 1 && id != 16) || start < name.offset || start + length > (long) name.offset + name.length) continue;
            if ((platform == 0 || platform == 3) && length % 2 != 0) continue;
            String value = new String(source, (int) start, length,
                    platform == 0 || platform == 3 ? StandardCharsets.UTF_16BE : StandardCharsets.ISO_8859_1).trim();
            if (!value.isEmpty()) {
                if (id == 16 && (platform == 0 || platform == 3)) return value;
                if (fallback == null || platform == 3) fallback = value;
            }
        }
        return fallback == null ? "Без названия" : fallback;
    }

    public List<Axis> axes() throws IOException {
        Table fvar = tables.get("fvar");
        if (fvar == null) return Collections.emptyList();
        if (fvar.length < 16) throw invalid("Повреждена таблица fvar");
        int offset = u16(fvar.offset + 4), count = u16(fvar.offset + 8), size = u16(fvar.offset + 10);
        if (offset < 16 || size < 20 || count > 64 || offset + (long) count * size > fvar.length) {
            throw invalid("Повреждён список осей шрифта");
        }
        List<Axis> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int record = fvar.offset + offset + i * size;
            float min = buffer().getInt(record + 4) / 65536f;
            float def = buffer().getInt(record + 8) / 65536f;
            float max = buffer().getInt(record + 12) / 65536f;
            if (min > def || def > max) throw invalid("Некорректный диапазон оси");
            result.add(new Axis(tag(record), min, def, max));
        }
        return result;
    }

    public Axis weightAxis() throws IOException {
        for (Axis axis : axes()) if (axis.tag.equals("wght")) return axis;
        return null;
    }

    public boolean hasCodePoint(int codePoint) {
        Table cmap = tables.get("cmap");
        if (cmap == null || cmap.length < 4) return false;
        int count = u16(cmap.offset + 2);
        if (4L + 8L * count > cmap.length) return false;
        for (int i = 0; i < count; i++) {
            int record = cmap.offset + 4 + i * 8;
            int platform = u16(record), encoding = u16(record + 2);
            if (platform != 0 && !(platform == 3 && (encoding == 1 || encoding == 10))) continue;
            long relative = u32(record + 4);
            if (relative + 2 > cmap.length) continue;
            int sub = cmap.offset + (int) relative, format = u16(sub);
            if (format == 12 && relative + 16 <= cmap.length) {
                long length = u32(sub + 4), groups = u32(sub + 12);
                if (relative + length > cmap.length || 16L + 12L * groups > length) continue;
                for (int g = 0; g < groups; g++) {
                    int item = sub + 16 + g * 12;
                    long start = u32(item), end = u32(item + 4);
                    if (codePoint >= start && codePoint <= end && u32(item + 8) + codePoint - start != 0) return true;
                }
            } else if (format == 4 && codePoint <= 0xffff && relative + 16 <= cmap.length) {
                int length = u16(sub + 2), segments = u16(sub + 6) / 2;
                if (length < 16 || relative + length > cmap.length || 16L + segments * 8L > length) continue;
                int ends = sub + 14, starts = ends + segments * 2 + 2;
                int deltas = starts + segments * 2, ranges = deltas + segments * 2;
                for (int s = 0; s < segments; s++) {
                    int start = u16(starts + s * 2), end = u16(ends + s * 2);
                    if (codePoint < start || codePoint > end) continue;
                    int delta = s16(deltas + s * 2), range = u16(ranges + s * 2);
                    if (range == 0) { if (((codePoint + delta) & 0xffff) != 0) return true; }
                    else {
                        long glyphOffset = (long) ranges + s * 2 + range + 2L * (codePoint - start);
                        if (glyphOffset + 2 <= (long) sub + length) {
                            int glyph = u16((int) glyphOffset);
                            if (glyph != 0 && ((glyph + delta) & 0xffff) != 0) return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public String colorFormat() {
        if (hasTable("CBDT") && hasTable("CBLC")) return "CBDT/CBLC";
        if (hasTable("COLR") && hasTable("CPAL")) return "COLR/CPAL";
        if (hasTable("sbix")) return "sbix (нужна проверка совместимости с Android)";
        if (hasTable("SVG ")) return "SVG (нужна проверка совместимости с Android)";
        return "Цветные таблицы не найдены";
    }

    public Map<String, Object> describe() throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("family", familyName()); result.put("bytes", source.length);
        result.put("unitsPerEm", unitsPerEm()); result.put("ascent", ascent()); result.put("descent", descent());
        result.put("color", colorFormat()); result.put("tables", tableTags());
        result.put("supportsCyrillicSample", hasCodePoint('Я') && hasCodePoint('я') && hasCodePoint('щ'));
        if (hasTable("post")) {
            result.put("underlinePosition", underlinePosition()); result.put("underlineThickness", underlineThickness());
        }
        List<String> axes = new ArrayList<>();
        for (Axis axis : axes()) axes.add(axis.toString());
        result.put("axes", axes);
        return result;
    }

    /** Position/thickness in font units. Rebuilds all checksums; original bytes stay untouched. */
    public byte[] withUnderline(int position, int thickness) throws IOException {
        if (position < -unitsPerEm() || position > 0 || thickness < 1 || thickness > unitsPerEm() / 4) {
            throw invalid("Метрики линии выходят за допустимый диапазон");
        }
        require("post", 32);
        Map<String, byte[]> copies = new LinkedHashMap<>();
        List<String> tags = tableTags();
        Collections.sort(tags);
        for (String tag : tags) {
            // An old digital signature is no longer valid after any edit.
            if (tag.equals("DSIG")) continue;
            Table table = tables.get(tag);
            copies.put(tag, Arrays.copyOfRange(source, table.offset, table.offset + table.length));
        }
        ByteBuffer post = ByteBuffer.wrap(copies.get("post")).order(ByteOrder.BIG_ENDIAN);
        post.putShort(8, (short) position); post.putShort(10, (short) thickness);
        if (copies.containsKey("MVAR")) copies.put("MVAR", withoutUnderlineVariations(copies.get("MVAR")));
        return pack(buffer().getInt(0), copies);
    }

    /** Keep unrelated metric variations and their store offsets intact. */
    private static byte[] withoutUnderlineVariations(byte[] original) throws IOException {
        byte[] data = original.clone();
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        if (data.length < 12 || b.getShort(0) != 1 || b.getShort(2) != 0) throw new IOException("Повреждённая или неподдерживаемая MVAR");
        int size = b.getShort(6) & 0xffff, count = b.getShort(8) & 0xffff, store = b.getShort(10) & 0xffff;
        long end = 12L + (long) size * count;
        if (size < 8 || end > data.length || (count > 0 && store == 0) || (store != 0 && (store < end || store >= data.length))) throw new IOException("Повреждён каталог MVAR");
        int kept = 0;
        for (int i = 0; i < count; i++) {
            int offset = 12 + i * size;
            String tag = new String(data, offset, 4, StandardCharsets.US_ASCII);
            if (!tag.equals("undo") && !tag.equals("unds")) {
                System.arraycopy(data, offset, data, 12 + kept * size, size); kept++;
            }
        }
        b.putShort(8, (short) kept);
        if (kept == 0) b.putShort(10, (short) 0);
        // Old records are now unreferenced padding. The variation store stays byte-for-byte intact.
        Arrays.fill(data, 12 + kept * size, (int) end, (byte) 0);
        return data;
    }

    static byte[] pack(int signature, Map<String, byte[]> input) throws IOException {
        Map<String, byte[]> copies = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(input.keySet()); Collections.sort(names);
        for (String name : names) if (!name.equals("DSIG")) copies.put(name, name.equals("head") ? input.get(name).clone() : input.get(name));
        if (!copies.containsKey("head") || copies.get("head").length < 54 || copies.size() > 256) throw new IOException("Нет корректной таблицы head");
        ByteBuffer.wrap(copies.get("head")).order(ByteOrder.BIG_ENDIAN).putInt(8, 0);
        int count = copies.size(), length = 12 + count * 16;
        for (byte[] table : copies.values()) length += padded(table.length);
        if (length > MAX_BYTES) throw new IOException("Результат слишком большой");
        ByteBuffer out = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
        out.putInt(signature); out.putShort((short) count);
        int power = Integer.highestOneBit(count);
        out.putShort((short) (16 * power)); out.putShort((short) Integer.numberOfTrailingZeros(power));
        out.putShort((short) (16 * count - 16 * power));
        int index = 0, offset = 12 + count * 16, headOffset = -1;
        for (Map.Entry<String, byte[]> entry : copies.entrySet()) {
            byte[] data = entry.getValue();
            out.position(12 + index++ * 16);
            out.put(entry.getKey().getBytes(StandardCharsets.ISO_8859_1));
            out.putInt((int) checksum(data)); out.putInt(offset); out.putInt(data.length);
            out.position(offset); out.put(data);
            if (entry.getKey().equals("head")) headOffset = offset;
            offset += padded(data.length);
        }
        byte[] result = out.array();
        out.putInt(headOffset + 8, (int) ((CHECKSUM_MAGIC - checksum(result)) & 0xffffffffL));
        return result;
    }

    private static int padded(int length) { return (length + 3) & ~3; }
    public static long checksum(byte[] data) {
        long sum = 0;
        for (int offset = 0; offset < data.length; offset += 4) {
            long word = 0;
            for (int i = 0; i < 4; i++) word = (word << 8) | (offset + i < data.length ? data[offset + i] & 0xff : 0);
            sum = (sum + word) & 0xffffffffL;
        }
        return sum;
    }
}
