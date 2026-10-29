package io.github.yaroslaz.oppomoji;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bakes one position of a variable TrueType font (fvar/gvar) into a static font.
 * A font slot that ignores weight requests then still shows the weight chosen in the app.
 */
final class VariableInstancer {
    private static final int ON_CURVE = 0x01, X_SHORT = 0x02, Y_SHORT = 0x04, REPEAT = 0x08, X_SAME = 0x10, Y_SAME = 0x20, OVERLAP = 0x40;
    private static final int ARG_WORDS = 0x0001, ARGS_XY = 0x0002, HAVE_SCALE = 0x0008, MORE = 0x0020,
            XY_SCALE = 0x0040, TWO_BY_TWO = 0x0080, HAVE_INSTRUCTIONS = 0x0100;
    private static final String[] VARIATION_TABLES = {"fvar", "gvar", "avar", "HVAR", "VVAR", "MVAR", "STAT", "cvar"};

    private VariableInstancer() { }

    static final class Axis {
        final String tag; final float min, def, max;
        Axis(String tag, float min, float def, float max) { this.tag = tag; this.min = min; this.def = def; this.max = max; }
    }

    /** One decoded glyph: points with on-curve flags and contour ends, or components. */
    private static final class Glyph {
        int contours; int[] ends = new int[0]; boolean[] onCurve = new boolean[0]; boolean overlap;
        double[] x = new double[0], y = new double[0];
        byte[] instructions = new byte[0];
        final List<Component> components = new ArrayList<>();
        boolean composite() { return contours < 0; }
        int pointCount() { return composite() ? components.size() : x.length; }
    }

    private static final class Component {
        int flags, glyph, arg1, arg2; double dx, dy; double[] transform; // xx, xy, yx, yy
    }

    static List<Axis> axes(byte[] fvar) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(fvar);
        if (fvar.length < 16) throw new IOException("Повреждена таблица fvar");
        int offset = b.getShort(4) & 0xffff, count = b.getShort(8) & 0xffff, size = b.getShort(10) & 0xffff;
        if (size < 20 || offset + (long) count * size > fvar.length) throw new IOException("Повреждён список осей шрифта");
        List<Axis> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int r = offset + i * size;
            result.add(new Axis(new String(fvar, r, 4, java.nio.charset.StandardCharsets.ISO_8859_1),
                    b.getInt(r + 4) / 65536f, b.getInt(r + 8) / 65536f, b.getInt(r + 12) / 65536f));
        }
        return result;
    }

    /** User coordinates by axis tag; missing axes stay at their defaults. Returns a static font. */
    static byte[] instantiate(SfntFont font, Map<String, Float> location) throws IOException {
        Map<String, byte[]> tables = font.copies();
        for (String tag : new String[]{"fvar", "gvar", "glyf", "loca", "head", "hhea", "hmtx", "maxp"}) {
            if (!tables.containsKey(tag)) throw new IOException("Нужен вариативный TrueType-шрифт: нет таблицы " + tag);
        }
        List<Axis> axes = axes(tables.get("fvar"));
        double[] coords = normalize(axes, location, tables.get("avar"));
        ByteBuffer head = ByteBuffer.wrap(tables.get("head")), hhea = ByteBuffer.wrap(tables.get("hhea"));
        int glyphCount = ByteBuffer.wrap(tables.get("maxp")).getShort(4) & 0xffff;
        boolean longLoca = head.getShort(50) != 0;
        int[] loca = readLoca(tables.get("loca"), glyphCount, longLoca);
        byte[] glyf = tables.get("glyf");
        int metrics = hhea.getShort(34) & 0xffff;
        int[] advance = new int[glyphCount], lsb = new int[glyphCount];
        readMetrics(tables.get("hmtx"), metrics, advance, lsb);

        Glyph[] glyphs = new Glyph[glyphCount];
        for (int g = 0; g < glyphCount; g++) glyphs[g] = decode(glyf, loca[g], loca[g + 1]);
        // Phantom point x positions: origin and advance. Defaults follow the TrueType rules.
        double[] left = new double[glyphCount], right = new double[glyphCount];
        int[] defaultMinX = new int[glyphCount];
        for (int g = 0; g < glyphCount; g++) {
            defaultMinX[g] = loca[g + 1] > loca[g] ? ByteBuffer.wrap(glyf).getShort(loca[g] + 2) : 0;
            left[g] = defaultMinX[g] - lsb[g]; right[g] = left[g] + advance[g];
        }
        GlyphVariations variations = new GlyphVariations(tables.get("gvar"), axes.size(), glyphCount);
        for (int g = 0; g < glyphCount; g++) variations.apply(g, glyphs[g], coords, left, right);

        // Rebuild glyf with long loca, then metrics from the moved phantom points.
        ByteArrayOutputStream out = new ByteArrayOutputStream(glyf.length + glyphCount * 8);
        int[] newLoca = new int[glyphCount + 1];
        double[][] bounds = new double[glyphCount][];
        for (int g = 0; g < glyphCount; g++) {
            newLoca[g] = out.size();
            double[] box = bounds(g, glyphs, bounds, 0);
            if (glyphs[g].pointCount() > 0 || glyphs[g].composite()) out.write(encode(glyphs[g], box));
            while (out.size() % 4 != 0) out.write(0);
        }
        newLoca[glyphCount] = out.size();
        ByteBuffer hmtx = ByteBuffer.allocate(glyphCount * 4);
        int xMin = Integer.MAX_VALUE, yMin = Integer.MAX_VALUE, xMax = Integer.MIN_VALUE, yMax = Integer.MIN_VALUE, maxAdvance = 0;
        int minLsb = Integer.MAX_VALUE, minRsb = Integer.MAX_VALUE, maxExtent = Integer.MIN_VALUE;
        for (int g = 0; g < glyphCount; g++) {
            int width = Math.max(0, round(right[g] - left[g]));
            double[] box = bounds[g];
            int bearing = box == null ? 0 : round(box[0] - left[g]);
            hmtx.putShort((short) width).putShort((short) bearing);
            maxAdvance = Math.max(maxAdvance, width);
            if (box != null) {
                xMin = Math.min(xMin, round(box[0])); yMin = Math.min(yMin, round(box[1]));
                xMax = Math.max(xMax, round(box[2])); yMax = Math.max(yMax, round(box[3]));
                int extent = bearing + round(box[2]) - round(box[0]);
                minLsb = Math.min(minLsb, bearing); minRsb = Math.min(minRsb, width - extent); maxExtent = Math.max(maxExtent, extent);
            }
        }
        tables.put("glyf", out.toByteArray());
        ByteBuffer locaOut = ByteBuffer.allocate((glyphCount + 1) * 4);
        for (int value : newLoca) locaOut.putInt(value);
        tables.put("loca", locaOut.array());
        tables.put("hmtx", hmtx.array());
        head.putShort(50, (short) 1);
        if (xMin != Integer.MAX_VALUE) head.putShort(36, (short) xMin).putShort(38, (short) yMin).putShort(40, (short) xMax).putShort(42, (short) yMax);
        hhea.putShort(10, (short) maxAdvance).putShort(34, (short) glyphCount);
        if (minLsb != Integer.MAX_VALUE) hhea.putShort(12, (short) minLsb).putShort(14, (short) minRsb).putShort(16, (short) maxExtent);
        byte[] os2 = tables.get("OS/2");
        Float weight = location.get("wght");
        if (os2 != null && os2.length >= 6 && weight != null) ByteBuffer.wrap(os2).putShort(4, (short) Math.max(1, Math.min(1000, Math.round(weight))));
        for (String tag : new String[]{"hdmx", "LTSH", "VDMX"}) tables.remove(tag); // Per-size caches of the old outlines.
        // Kerning, mark anchors and font-wide metrics vary too: bake their deltas before dropping the tables.
        VariationStore store = VariationStore.at(tables.get("GDEF"), 14, coords);
        if (store != null && tables.containsKey("GPOS")) new PositionDeltas(tables.get("GPOS"), store).apply();
        applyMetricVariations(tables, coords);
        for (String tag : VARIATION_TABLES) tables.remove(tag);
        if (tables.containsKey("GSUB")) tables.put("GSUB", applyFeatureVariations(tables.get("GSUB"), 7, coords));
        if (tables.containsKey("GPOS")) tables.put("GPOS", applyFeatureVariations(tables.get("GPOS"), 9, coords));
        // Without fvar the GDEF variation store is invalid; kerning keeps its default-weight values.
        byte[] gdef = tables.get("GDEF");
        if (gdef != null && gdef.length >= 18 && ByteBuffer.wrap(gdef).getInt(0) == 0x00010003) ByteBuffer.wrap(gdef).putInt(14, 0);
        return SfntFont.pack(font.signature(), tables);
    }

    /**
     * Weight-dependent glyph swaps (for example a simpler "$" when bold) are chosen once for the baked
     * location; the table is rebuilt without FeatureVariations, since alternates may sit beyond 64 KB.
     */
    static byte[] applyFeatureVariations(byte[] table, int extension, double[] coords) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(table);
        if (table.length < 14 || b.getInt(0) != 0x00010001) return table;
        int variations = b.getInt(10);
        Map<Integer, int[]> alternates = new LinkedHashMap<>();
        if (variations > 0 && variations + 8L <= table.length) {
            long records = b.getInt(variations + 4) & 0xffffffffL;
            for (int r = 0; r < records && variations + 8L + (r + 1) * 8L <= table.length; r++) {
                int conditions = b.getInt(variations + 8 + r * 8), substitution = b.getInt(variations + 12 + r * 8);
                if (!matches(b, table.length, variations + conditions, conditions, coords)) continue;
                int fts = variations + substitution;
                if (substitution != 0 && fts + 6L <= table.length) {
                    int count = b.getShort(fts + 4) & 0xffff;
                    for (int i = 0; i < count && fts + 6L + (i + 1) * 6L <= table.length; i++) {
                        int feature = fts + b.getInt(fts + 8 + i * 6);
                        if (feature + 4L > table.length) continue;
                        int lookups = b.getShort(feature + 2) & 0xffff;
                        int[] list = new int[lookups];
                        for (int k = 0; k < lookups && feature + 6L + k * 2L <= table.length; k++) list[k] = b.getShort(feature + 4 + k * 2) & 0xffff;
                        alternates.put(b.getShort(fts + 6 + i * 6) & 0xffff, list);
                    }
                }
                break; // Only the first matching record applies.
            }
        }
        return FontTables.layout(table, extension, alternates);
    }

    private static boolean matches(ByteBuffer b, int length, int set, int offset, double[] coords) {
        if (offset == 0) return true; // An empty condition set always matches.
        if (set + 2L > length) return false;
        int count = b.getShort(set) & 0xffff;
        for (int c = 0; c < count; c++) {
            if (set + 2L + (c + 1) * 4L > length) return false;
            int condition = set + b.getInt(set + 2 + c * 4);
            if (condition + 8L > length || (b.getShort(condition) & 0xffff) != 1) return false;
            int axis = b.getShort(condition + 2) & 0xffff;
            double min = b.getShort(condition + 4) / 16384.0, max = b.getShort(condition + 6) / 16384.0, v = axis < coords.length ? coords[axis] : 0;
            if (v < min || v > max) return false;
        }
        return true;
    }

    /** ItemVariationStore evaluated at one location: (outer, inner) -> delta. */
    static final class VariationStore {
        final ByteBuffer b; final int store; final double[] regionScalars;

        private VariationStore(ByteBuffer b, int store, double[] scalars) { this.b = b; this.store = store; regionScalars = scalars; }

        /** Store referenced by a 32-bit offset at {@code field} of a table (GDEF 1.3: 14; MVAR: 8). */
        static VariationStore at(byte[] table, int field, double[] coords) {
            if (table == null || table.length < field + 4) return null;
            ByteBuffer b = ByteBuffer.wrap(table);
            if (field == 14 && b.getInt(0) != 0x00010003) return null;
            int store = b.getInt(field);
            if (store <= 0 || store + 8L > table.length || (b.getShort(store) & 0xffff) != 1) return null;
            int regions = store + b.getInt(store + 2);
            if (regions + 4L > table.length) return null;
            int axisCount = b.getShort(regions) & 0xffff, regionCount = b.getShort(regions + 2) & 0xffff;
            if (regions + 4L + (long) regionCount * axisCount * 6 > table.length) return null;
            double[] scalars = new double[regionCount];
            for (int r = 0; r < regionCount; r++) {
                double[] start = new double[axisCount], peak = new double[axisCount], end = new double[axisCount];
                for (int a = 0; a < axisCount; a++) {
                    int at = regions + 4 + (r * axisCount + a) * 6;
                    start[a] = b.getShort(at) / 16384.0; peak[a] = b.getShort(at + 2) / 16384.0; end[a] = b.getShort(at + 4) / 16384.0;
                }
                double[] location = java.util.Arrays.copyOf(coords, axisCount);
                scalars[r] = scalar(location, peak, start, end);
            }
            return new VariationStore(b, store, scalars);
        }

        double delta(int outer, int inner) {
            int dataCount = b.getShort(store + 6) & 0xffff;
            if (outer >= dataCount) return 0;
            int data = store + b.getInt(store + 8 + outer * 4);
            int itemCount = b.getShort(data) & 0xffff, words = b.getShort(data + 2) & 0xffff, regions = b.getShort(data + 4) & 0xffff;
            if (inner >= itemCount) return 0;
            boolean longWords = (words & 0x8000) != 0; int wordCount = words & 0x7fff;
            int rowSize = longWords ? wordCount * 4 + (regions - wordCount) * 2 : wordCount * 2 + (regions - wordCount);
            int row = data + 6 + regions * 2 + inner * rowSize, p = row;
            double sum = 0;
            for (int r = 0; r < regions; r++) {
                int value;
                if (r < wordCount) { value = longWords ? b.getInt(p) : b.getShort(p); p += longWords ? 4 : 2; }
                else { value = longWords ? b.getShort(p) : b.get(p); p += longWords ? 2 : 1; }
                int region = b.getShort(data + 6 + r * 2) & 0xffff;
                if (region < regionScalars.length) sum += value * regionScalars[region];
            }
            return sum;
        }
    }

    /** Adds VariationIndex deltas to GPOS value records and anchors in place; their sizes never change. */
    private static final class PositionDeltas {
        final ByteBuffer b; final VariationStore store; final java.util.Set<Long> visited = new java.util.HashSet<>();
        PositionDeltas(byte[] gpos, VariationStore store) { b = ByteBuffer.wrap(gpos); this.store = store; }
        int u16(int at) { return b.getShort(at) & 0xffff; }
        boolean once(int kind, int at) { return visited.add(((long) kind << 32) | at); }

        void apply() {
            int lookups = u16(8);
            if (lookups == 0) return;
            int count = u16(lookups);
            for (int i = 0; i < count; i++) {
                int lookup = lookups + u16(lookups + 2 + i * 2), type = u16(lookup), subtables = u16(lookup + 4);
                for (int k = 0; k < subtables; k++) {
                    int sub = lookup + u16(lookup + 6 + k * 2), realType = type;
                    if (type == 9) { realType = u16(sub + 2); sub += b.getInt(sub + 4); }
                    if (once(0, sub)) subtable(realType, sub);
                }
            }
        }

        double device(int at) {
            if (at == 0 || u16(at + 4) != 0x8000) return 0; // Only VariationIndex tables carry variations.
            return store.delta(u16(at), u16(at + 2));
        }

        int valueSize(int format) { return Integer.bitCount(format & 0xff) * 2; }

        /** Value record at {@code at}; device offsets are relative to the positioning subtable. */
        void value(int at, int format, int base) {
            int[] fields = new int[8]; int p = at;
            for (int bit = 0; bit < 8; bit++) if ((format & (1 << bit)) != 0) { fields[bit] = p; p += 2; } else fields[bit] = -1;
            for (int v = 0; v < 4; v++) {
                if (fields[v] < 0 || fields[v + 4] < 0) continue;
                int offset = u16(fields[v + 4]);
                if (offset == 0) continue;
                double delta = device(base + offset);
                if (delta != 0) b.putShort(fields[v], (short) round(b.getShort(fields[v]) + delta));
            }
        }

        void anchor(int at) {
            if (!once(1, at) || u16(at) != 3) return;
            for (int axis = 0; axis < 2; axis++) {
                int offset = u16(at + 6 + axis * 2);
                if (offset == 0) continue;
                double delta = device(at + offset);
                if (delta != 0) b.putShort(at + 2 + axis * 2, (short) round(b.getShort(at + 2 + axis * 2) + delta));
            }
        }

        void anchors(int array, int records, int perRecord, int skip) {
            for (int r = 0; r < records; r++) for (int c = 0; c < perRecord; c++) {
                int offset = u16(array + 2 + r * (skip + perRecord * 2) + skip + c * 2);
                if (offset != 0) anchor(array + offset);
            }
        }

        void subtable(int type, int at) {
            int format = u16(at);
            switch (type) {
                case 1: {
                    int vf = u16(at + 4);
                    if (format == 1) value(at + 6, vf, at);
                    else { int count = u16(at + 6); for (int i = 0; i < count; i++) value(at + 8 + i * valueSize(vf), vf, at); }
                    break;
                }
                case 2: {
                    int vf1 = u16(at + 4), vf2 = u16(at + 6), s1 = valueSize(vf1), s2 = valueSize(vf2);
                    if (format == 1) {
                        int sets = u16(at + 8);
                        for (int i = 0; i < sets; i++) {
                            int set = at + u16(at + 10 + i * 2);
                            if (!once(2, set)) continue;
                            int pairs = u16(set);
                            for (int k = 0; k < pairs; k++) {
                                int record = set + 2 + k * (2 + s1 + s2);
                                value(record + 2, vf1, at); value(record + 2 + s1, vf2, at);
                            }
                        }
                    } else if (format == 2) {
                        int class1 = u16(at + 12), class2 = u16(at + 14);
                        for (int c1 = 0; c1 < class1; c1++) for (int c2 = 0; c2 < class2; c2++) {
                            int record = at + 16 + (c1 * class2 + c2) * (s1 + s2);
                            value(record, vf1, at); value(record + s1, vf2, at);
                        }
                    }
                    break;
                }
                case 3: {
                    int count = u16(at + 4);
                    for (int i = 0; i < count; i++) for (int e = 0; e < 2; e++) {
                        int offset = u16(at + 6 + i * 4 + e * 2);
                        if (offset != 0) anchor(at + offset);
                    }
                    break;
                }
                case 4: case 6: {
                    int classes = u16(at + 6), marks = at + u16(at + 8), bases = at + u16(at + 10);
                    if (once(3, marks)) anchors(marks, u16(marks), 1, 2);
                    if (once(3, bases)) anchors(bases, u16(bases), classes, 0);
                    break;
                }
                case 5: {
                    int classes = u16(at + 6), marks = at + u16(at + 8), ligatures = at + u16(at + 10);
                    if (once(3, marks)) anchors(marks, u16(marks), 1, 2);
                    if (once(3, ligatures)) {
                        int count = u16(ligatures);
                        for (int i = 0; i < count; i++) {
                            int attach = ligatures + u16(ligatures + 2 + i * 2);
                            if (once(3, attach)) anchors(attach, u16(attach), classes, 0);
                        }
                    }
                    break;
                }
                default: // Contextual lookups hold no values.
            }
        }
    }

    /** MVAR deltas for line metrics, x-height, strikeout and underline. */
    private static void applyMetricVariations(Map<String, byte[]> tables, double[] coords) {
        byte[] mvar = tables.get("MVAR");
        if (mvar == null || mvar.length < 12) return;
        ByteBuffer m = ByteBuffer.wrap(mvar);
        int size = m.getShort(6) & 0xffff, count = m.getShort(8) & 0xffff, storeOffset = m.getShort(10) & 0xffff;
        if (storeOffset == 0) return;
        byte[] withStore = java.util.Arrays.copyOf(mvar, mvar.length);
        ByteBuffer.wrap(withStore).putInt(8, storeOffset); // Reuse the reader: store offset at a 32-bit field.
        VariationStore store = VariationStore.at(withStore, 8, coords);
        if (store == null) return;
        Map<String, Object[]> fields = new LinkedHashMap<>();
        String[][] map = {{"hasc", "OS/2", "68"}, {"hdsc", "OS/2", "70"}, {"hlgp", "OS/2", "72"}, {"hcla", "OS/2", "74"}, {"hcld", "OS/2", "76"},
                {"xhgt", "OS/2", "86"}, {"cpht", "OS/2", "88"}, {"sbxs", "OS/2", "10"}, {"sbys", "OS/2", "12"}, {"sbxo", "OS/2", "14"},
                {"sbyo", "OS/2", "16"}, {"spxs", "OS/2", "18"}, {"spys", "OS/2", "20"}, {"spxo", "OS/2", "22"}, {"spyo", "OS/2", "24"},
                {"strs", "OS/2", "26"}, {"stro", "OS/2", "28"}, {"undo", "post", "8"}, {"unds", "post", "10"},
                {"hcrs", "hhea", "18"}, {"hcrn", "hhea", "20"}, {"hcof", "hhea", "22"}};
        for (String[] entry : map) fields.put(entry[0], new Object[]{entry[1], Integer.parseInt(entry[2])});
        for (int i = 0; i < count && 12 + (i + 1) * size <= mvar.length; i++) {
            int record = 12 + i * size;
            String tag = new String(mvar, record, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            Object[] field = fields.get(tag);
            if (field == null) continue;
            byte[] table = tables.get((String) field[0]);
            int at = (Integer) field[1];
            if (table == null || table.length < at + 2) continue;
            double delta = store.delta(m.getShort(record + 4) & 0xffff, m.getShort(record + 6) & 0xffff);
            ByteBuffer t = ByteBuffer.wrap(table);
            boolean unsigned = tag.equals("hcla") || tag.equals("hcld");
            int value = unsigned ? t.getShort(at) & 0xffff : t.getShort(at);
            t.putShort(at, (short) round(value + delta));
        }
    }

    static double[] normalize(List<Axis> axes, Map<String, Float> location, byte[] avar) throws IOException {
        double[] coords = new double[axes.size()];
        for (int i = 0; i < axes.size(); i++) {
            Axis axis = axes.get(i);
            Float requested = location.get(axis.tag);
            double v = requested == null ? axis.def : Math.max(axis.min, Math.min(axis.max, requested));
            coords[i] = v < axis.def ? (axis.def == axis.min ? 0 : (v - axis.def) / (axis.def - axis.min))
                    : v > axis.def ? (axis.max == axis.def ? 0 : (v - axis.def) / (axis.max - axis.def)) : 0;
        }
        if (avar != null) {
            ByteBuffer b = ByteBuffer.wrap(avar);
            if (avar.length < 8 || (b.getShort(6) & 0xffff) != axes.size()) throw new IOException("Повреждена таблица avar");
            int p = 8;
            for (int i = 0; i < axes.size(); i++) {
                if (p + 2 > avar.length) throw new IOException("Повреждена таблица avar");
                int count = b.getShort(p) & 0xffff; p += 2;
                if (p + count * 4L > avar.length) throw new IOException("Повреждена таблица avar");
                double[] from = new double[count], to = new double[count];
                for (int k = 0; k < count; k++) { from[k] = b.getShort(p) / 16384.0; to[k] = b.getShort(p + 2) / 16384.0; p += 4; }
                coords[i] = map(coords[i], from, to);
            }
        }
        for (int i = 0; i < coords.length; i++) coords[i] = Math.round(coords[i] * 16384) / 16384.0;
        return coords;
    }

    private static double map(double v, double[] from, double[] to) {
        if (from.length == 0) return v;
        for (int k = 0; k < from.length; k++) if (v == from[k]) return to[k];
        if (v < from[0]) return v + to[0] - from[0];
        if (v > from[from.length - 1]) return v + to[to.length - 1] - from[from.length - 1];
        for (int k = 1; k < from.length; k++) {
            if (v < from[k]) return to[k - 1] + (to[k] - to[k - 1]) * (v - from[k - 1]) / (from[k] - from[k - 1]);
        }
        return v;
    }

    static int[] readLoca(byte[] loca, int glyphCount, boolean longFormat) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(loca);
        if (loca.length < (glyphCount + 1) * (longFormat ? 4 : 2)) throw new IOException("Повреждена таблица loca");
        int[] result = new int[glyphCount + 1];
        for (int i = 0; i <= glyphCount; i++) result[i] = longFormat ? b.getInt(i * 4) : (b.getShort(i * 2) & 0xffff) * 2;
        for (int i = 0; i < glyphCount; i++) if (result[i] > result[i + 1] || result[i] < 0) throw new IOException("Повреждена таблица loca");
        return result;
    }

    static void readMetrics(byte[] hmtx, int metrics, int[] advance, int[] lsb) throws IOException {
        int glyphCount = advance.length;
        ByteBuffer b = ByteBuffer.wrap(hmtx);
        if (metrics < 1 || metrics > glyphCount || hmtx.length < metrics * 4 + (glyphCount - metrics) * 2) throw new IOException("Повреждена таблица hmtx");
        for (int g = 0; g < glyphCount; g++) {
            if (g < metrics) { advance[g] = b.getShort(g * 4) & 0xffff; lsb[g] = b.getShort(g * 4 + 2); }
            else { advance[g] = advance[metrics - 1]; lsb[g] = b.getShort(metrics * 4 + (g - metrics) * 2); }
        }
    }

    private static Glyph decode(byte[] glyf, int start, int end) throws IOException {
        Glyph glyph = new Glyph();
        if (end <= start) return glyph;
        if (end > glyf.length || end - start < 10) throw new IOException("Повреждена таблица glyf");
        ByteBuffer b = ByteBuffer.wrap(glyf, 0, end);
        glyph.contours = b.getShort(start);
        int p = start + 10;
        if (glyph.contours >= 0) {
            glyph.ends = new int[glyph.contours];
            for (int i = 0; i < glyph.contours; i++) { glyph.ends[i] = b.getShort(p) & 0xffff; p += 2; }
            int points = glyph.contours == 0 ? 0 : glyph.ends[glyph.contours - 1] + 1;
            int instructionLength = b.getShort(p) & 0xffff; p += 2;
            glyph.instructions = java.util.Arrays.copyOfRange(glyf, p, p + instructionLength); p += instructionLength;
            int[] flags = new int[points];
            for (int i = 0; i < points; ) {
                int flag = glyf[p++] & 0xff; flags[i++] = flag;
                if ((flag & REPEAT) != 0) { int repeat = glyf[p++] & 0xff; for (int r = 0; r < repeat && i < points; r++) flags[i++] = flag; }
            }
            glyph.onCurve = new boolean[points]; glyph.x = new double[points]; glyph.y = new double[points];
            if (points > 0) glyph.overlap = (flags[0] & OVERLAP) != 0;
            int value = 0;
            for (int i = 0; i < points; i++) {
                int flag = flags[i];
                if ((flag & X_SHORT) != 0) { int d = glyf[p++] & 0xff; value += (flag & X_SAME) != 0 ? d : -d; }
                else if ((flag & X_SAME) == 0) { value += b.getShort(p); p += 2; }
                glyph.x[i] = value; glyph.onCurve[i] = (flag & ON_CURVE) != 0;
            }
            value = 0;
            for (int i = 0; i < points; i++) {
                int flag = flags[i];
                if ((flag & Y_SHORT) != 0) { int d = glyf[p++] & 0xff; value += (flag & Y_SAME) != 0 ? d : -d; }
                else if ((flag & Y_SAME) == 0) { value += b.getShort(p); p += 2; }
                glyph.y[i] = value;
            }
            if (p > end) throw new IOException("Повреждена таблица glyf");
        } else {
            int flags;
            do {
                Component c = new Component();
                c.flags = flags = b.getShort(p) & 0xffff; c.glyph = b.getShort(p + 2) & 0xffff; p += 4;
                boolean xy = (flags & ARGS_XY) != 0;
                if ((flags & ARG_WORDS) != 0) {
                    c.arg1 = xy ? b.getShort(p) : b.getShort(p) & 0xffff; c.arg2 = xy ? b.getShort(p + 2) : b.getShort(p + 2) & 0xffff; p += 4;
                } else {
                    c.arg1 = xy ? glyf[p] : glyf[p] & 0xff; c.arg2 = xy ? glyf[p + 1] : glyf[p + 1] & 0xff; p += 2;
                }
                if ((flags & HAVE_SCALE) != 0) { double s = b.getShort(p) / 16384.0; c.transform = new double[]{s, 0, 0, s}; p += 2; }
                else if ((flags & XY_SCALE) != 0) { c.transform = new double[]{b.getShort(p) / 16384.0, 0, 0, b.getShort(p + 2) / 16384.0}; p += 4; }
                else if ((flags & TWO_BY_TWO) != 0) {
                    c.transform = new double[]{b.getShort(p) / 16384.0, b.getShort(p + 2) / 16384.0, b.getShort(p + 4) / 16384.0, b.getShort(p + 6) / 16384.0}; p += 8;
                }
                c.dx = xy ? c.arg1 : 0; c.dy = xy ? c.arg2 : 0;
                glyph.components.add(c);
                if (p > end) throw new IOException("Повреждена таблица glyf");
            } while ((flags & MORE) != 0);
            if ((flags & HAVE_INSTRUCTIONS) != 0 && p + 2 <= end) {
                int length = b.getShort(p) & 0xffff;
                glyph.instructions = java.util.Arrays.copyOfRange(glyf, p + 2, Math.min(end, p + 2 + length));
            }
        }
        return glyph;
    }

    /** gvar reader that moves points, component offsets and the two horizontal phantom points. */
    private static final class GlyphVariations {
        final ByteBuffer b; final byte[] data; final int axisCount, sharedTuples, dataArray; final int[] offsets;
        GlyphVariations(byte[] gvar, int axisCount, int glyphCount) throws IOException {
            data = gvar; b = ByteBuffer.wrap(gvar);
            if (gvar.length < 20 || (b.getShort(4) & 0xffff) != axisCount || (b.getShort(12) & 0xffff) != glyphCount) throw new IOException("gvar не совпадает со шрифтом");
            this.axisCount = axisCount;
            int sharedCount = b.getShort(6) & 0xffff;
            sharedTuples = b.getInt(8);
            boolean longOffsets = (b.getShort(14) & 1) != 0;
            dataArray = b.getInt(16);
            offsets = new int[glyphCount + 1];
            if (20L + (glyphCount + 1L) * (longOffsets ? 4 : 2) > gvar.length || sharedTuples + (long) sharedCount * axisCount * 2 > gvar.length) throw new IOException("Повреждена таблица gvar");
            for (int i = 0; i <= glyphCount; i++) offsets[i] = longOffsets ? b.getInt(20 + i * 4) : (b.getShort(20 + i * 2) & 0xffff) * 2;
        }

        void apply(int gid, Glyph glyph, double[] coords, double[] left, double[] right) throws IOException {
            int start = dataArray + offsets[gid], end = dataArray + offsets[gid + 1];
            if (end <= start) return;
            if (end > data.length) throw new IOException("Повреждена таблица gvar");
            int points = glyph.pointCount(), total = points + 4;
            double[] origX = new double[total], origY = new double[total];
            for (int i = 0; i < points; i++) {
                origX[i] = glyph.composite() ? glyph.components.get(i).dx : glyph.x[i];
                origY[i] = glyph.composite() ? glyph.components.get(i).dy : glyph.y[i];
            }
            origX[points] = left[gid]; origX[points + 1] = right[gid];
            double[] sumX = new double[total], sumY = new double[total];
            int header = b.getShort(start) & 0xffff, count = header & 0x0fff;
            int serialized = start + (b.getShort(start + 2) & 0xffff);
            int[] position = {serialized};
            int[] shared = (header & 0x8000) != 0 ? readPoints(position, total) : null;
            int h = start + 4;
            for (int t = 0; t < count; t++) {
                int size = b.getShort(h) & 0xffff, index = b.getShort(h + 2) & 0xffff; h += 4;
                double[] peak = new double[axisCount], startTuple = null, endTuple = null;
                if ((index & 0x8000) != 0) { for (int a = 0; a < axisCount; a++) { peak[a] = b.getShort(h) / 16384.0; h += 2; } }
                else {
                    int shareIndex = index & 0x0fff;
                    for (int a = 0; a < axisCount; a++) peak[a] = b.getShort(sharedTuples + (shareIndex * axisCount + a) * 2) / 16384.0;
                }
                if ((index & 0x4000) != 0) {
                    startTuple = new double[axisCount]; endTuple = new double[axisCount];
                    for (int a = 0; a < axisCount; a++) { startTuple[a] = b.getShort(h) / 16384.0; h += 2; }
                    for (int a = 0; a < axisCount; a++) { endTuple[a] = b.getShort(h) / 16384.0; h += 2; }
                }
                int dataStart = position[0];
                position[0] = dataStart + size;
                double scalar = scalar(coords, peak, startTuple, endTuple);
                if (scalar == 0) continue;
                int[] cursor = {dataStart};
                int[] list = (index & 0x2000) != 0 ? readPoints(cursor, total) : shared;
                int n = list == null ? total : list.length;
                int[] dx = readDeltas(cursor, n), dy = readDeltas(cursor, n);
                if (cursor[0] > dataStart + size) throw new IOException("Повреждена таблица gvar");
                double[] deltaX = new double[total], deltaY = new double[total];
                boolean[] touched = new boolean[total];
                for (int k = 0; k < n; k++) {
                    int point = list == null ? k : list[k];
                    if (point >= total) continue;
                    deltaX[point] += dx[k]; deltaY[point] += dy[k]; touched[point] = true;
                }
                if (list != null && !glyph.composite() && points > 0) infer(glyph, origX, origY, deltaX, deltaY, touched);
                for (int k = 0; k < total; k++) { sumX[k] += deltaX[k] * scalar; sumY[k] += deltaY[k] * scalar; }
            }
            for (int i = 0; i < points; i++) {
                if (glyph.composite()) {
                    Component c = glyph.components.get(i);
                    if ((c.flags & ARGS_XY) != 0) { c.dx = round(c.dx + sumX[i]); c.dy = round(c.dy + sumY[i]); }
                } else { glyph.x[i] = round(glyph.x[i] + sumX[i]); glyph.y[i] = round(glyph.y[i] + sumY[i]); }
            }
            left[gid] = round(left[gid] + sumX[points]); right[gid] = round(right[gid] + sumX[points + 1]);
        }

        private int[] readPoints(int[] cursor, int total) throws IOException {
            int p = cursor[0];
            int count = data[p++] & 0xff;
            if (count == 0) { cursor[0] = p; return null; }
            if ((count & 0x80) != 0) count = ((count & 0x7f) << 8) | (data[p++] & 0xff);
            int[] points = new int[count];
            int value = 0, i = 0;
            while (i < count) {
                int control = data[p++] & 0xff, run = (control & 0x7f) + 1;
                for (int r = 0; r < run && i < count; r++) {
                    if ((control & 0x80) != 0) { value += b.getShort(p) & 0xffff; p += 2; } else value += data[p++] & 0xff;
                    points[i++] = value;
                }
            }
            if (p > data.length) throw new IOException("Повреждена таблица gvar");
            cursor[0] = p;
            return points;
        }

        private int[] readDeltas(int[] cursor, int count) {
            int[] deltas = new int[count];
            int p = cursor[0], i = 0;
            while (i < count) {
                int control = data[p++] & 0xff, run = (control & 0x3f) + 1;
                for (int r = 0; r < run && i < count; r++) {
                    if ((control & 0x80) != 0) deltas[i++] = 0;
                    else if ((control & 0x40) != 0) { deltas[i++] = b.getShort(p); p += 2; }
                    else deltas[i++] = data[p++];
                }
            }
            cursor[0] = p;
            return deltas;
        }
    }

    static double scalar(double[] coords, double[] peak, double[] start, double[] end) {
        double scalar = 1;
        for (int a = 0; a < coords.length; a++) {
            double p = peak[a];
            if (p == 0) continue;
            double s = start == null ? Math.min(0, p) : start[a], e = end == null ? Math.max(0, p) : end[a];
            if (s > p || p > e || (s < 0 && e > 0)) continue;
            double v = coords[a];
            if (v == p) continue;
            if (v <= s || v >= e) return 0;
            scalar *= v < p ? (v - s) / (p - s) : (e - v) / (e - p);
        }
        return scalar;
    }

    /** Interpolation of untouched points (IUP), per contour, from the touched neighbours. */
    private static void infer(Glyph glyph, double[] x, double[] y, double[] dx, double[] dy, boolean[] touched) {
        int start = 0;
        for (int contour = 0; contour < glyph.contours; contour++) {
            int end = glyph.ends[contour];
            List<Integer> refs = new ArrayList<>();
            for (int i = start; i <= end; i++) if (touched[i]) refs.add(i);
            if (refs.isEmpty()) { start = end + 1; continue; }
            if (refs.size() == 1) {
                int r = refs.get(0);
                for (int i = start; i <= end; i++) if (!touched[i]) { dx[i] = dx[r]; dy[i] = dy[r]; }
                start = end + 1; continue;
            }
            int n = end - start + 1;
            for (int k = 0; k < refs.size(); k++) {
                int r1 = refs.get(k), r2 = refs.get((k + 1) % refs.size());
                for (int i = (r1 - start + 1) % n + start; i != r2; i = (i - start + 1) % n + start) {
                    dx[i] = interpolate(x[i], x[r1], x[r2], dx[r1], dx[r2]);
                    dy[i] = interpolate(y[i], y[r1], y[r2], dy[r1], dy[r2]);
                }
            }
            start = end + 1;
        }
    }

    private static double interpolate(double v, double c1, double c2, double d1, double d2) {
        if (c1 == c2) return d1 == d2 ? d1 : 0;
        if (c1 > c2) { double t = c1; c1 = c2; c2 = t; t = d1; d1 = d2; d2 = t; }
        if (v <= c1) return d1;
        if (v >= c2) return d2;
        return d1 + (v - c1) * (d2 - d1) / (c2 - c1);
    }

    private static double[] bounds(int gid, Glyph[] glyphs, double[][] cache, int depth) throws IOException {
        if (cache[gid] != null || depth > 16) return cache[gid];
        Glyph glyph = glyphs[gid];
        double[] box = null;
        if (!glyph.composite()) {
            for (int i = 0; i < glyph.x.length; i++) box = extend(box, glyph.x[i], glyph.y[i]);
        } else {
            for (Component c : glyph.components) {
                if (c.glyph >= glyphs.length) throw new IOException("Компонент ссылается на несуществующий глиф");
                List<double[]> points = outline(c.glyph, glyphs, depth + 1);
                for (double[] point : points) {
                    double px = point[0], py = point[1];
                    if (c.transform != null) { double tx = px * c.transform[0] + py * c.transform[2]; py = px * c.transform[1] + py * c.transform[3]; px = tx; }
                    box = extend(box, px + c.dx, py + c.dy);
                }
            }
        }
        cache[gid] = box;
        return box;
    }

    private static List<double[]> outline(int gid, Glyph[] glyphs, int depth) throws IOException {
        List<double[]> result = new ArrayList<>();
        if (depth > 16) return result;
        Glyph glyph = glyphs[gid];
        if (!glyph.composite()) { for (int i = 0; i < glyph.x.length; i++) result.add(new double[]{glyph.x[i], glyph.y[i]}); return result; }
        for (Component c : glyph.components) {
            if (c.glyph >= glyphs.length) throw new IOException("Компонент ссылается на несуществующий глиф");
            for (double[] point : outline(c.glyph, glyphs, depth + 1)) {
                double px = point[0], py = point[1];
                if (c.transform != null) { double tx = px * c.transform[0] + py * c.transform[2]; py = px * c.transform[1] + py * c.transform[3]; px = tx; }
                result.add(new double[]{px + c.dx, py + c.dy});
            }
        }
        return result;
    }

    private static double[] extend(double[] box, double x, double y) {
        if (box == null) return new double[]{x, y, x, y};
        box[0] = Math.min(box[0], x); box[1] = Math.min(box[1], y); box[2] = Math.max(box[2], x); box[3] = Math.max(box[3], y);
        return box;
    }

    private static byte[] encode(Glyph glyph, double[] box) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        double[] b = box == null ? new double[4] : box;
        short16(out, glyph.contours);
        short16(out, round(b[0])); short16(out, round(b[1])); short16(out, round(b[2])); short16(out, round(b[3]));
        if (!glyph.composite()) {
            for (int end : glyph.ends) short16(out, end);
            short16(out, glyph.instructions.length); out.write(glyph.instructions, 0, glyph.instructions.length);
            int n = glyph.x.length;
            int[] xs = new int[n], ys = new int[n];
            int prevX = 0, prevY = 0;
            for (int i = 0; i < n; i++) { xs[i] = (int) glyph.x[i] - prevX; ys[i] = (int) glyph.y[i] - prevY; prevX = (int) glyph.x[i]; prevY = (int) glyph.y[i]; }
            for (int i = 0; i < n; i++) {
                int flag = glyph.onCurve[i] ? ON_CURVE : 0;
                if (i == 0 && glyph.overlap) flag |= OVERLAP;
                if (xs[i] == 0) flag |= X_SAME; else if (Math.abs(xs[i]) < 256) flag |= X_SHORT | (xs[i] > 0 ? X_SAME : 0);
                if (ys[i] == 0) flag |= Y_SAME; else if (Math.abs(ys[i]) < 256) flag |= Y_SHORT | (ys[i] > 0 ? Y_SAME : 0);
                out.write(flag);
            }
            for (int d : xs) { if (d != 0 && Math.abs(d) < 256) out.write(Math.abs(d)); else if (d != 0) short16(out, d); }
            for (int d : ys) { if (d != 0 && Math.abs(d) < 256) out.write(Math.abs(d)); else if (d != 0) short16(out, d); }
        } else {
            boolean instructions = false;
            for (int i = 0; i < glyph.components.size(); i++) {
                Component c = glyph.components.get(i);
                int flags = c.flags & ~(MORE | ARG_WORDS);
                if (i < glyph.components.size() - 1) flags |= MORE;
                instructions |= (c.flags & HAVE_INSTRUCTIONS) != 0;
                int a1 = (c.flags & ARGS_XY) != 0 ? (int) c.dx : c.arg1, a2 = (c.flags & ARGS_XY) != 0 ? (int) c.dy : c.arg2;
                flags |= ARG_WORDS;
                if (i == glyph.components.size() - 1 && instructions) flags |= HAVE_INSTRUCTIONS; else flags &= ~HAVE_INSTRUCTIONS;
                short16(out, flags); short16(out, c.glyph); short16(out, a1); short16(out, a2);
                if ((c.flags & HAVE_SCALE) != 0) f2dot14(out, c.transform[0]);
                else if ((c.flags & XY_SCALE) != 0) { f2dot14(out, c.transform[0]); f2dot14(out, c.transform[3]); }
                else if ((c.flags & TWO_BY_TWO) != 0) for (double v : c.transform) f2dot14(out, v);
            }
            if (instructions) { short16(out, glyph.instructions.length); out.write(glyph.instructions, 0, glyph.instructions.length); }
        }
        return out.toByteArray();
    }

    private static void short16(ByteArrayOutputStream out, int value) { out.write((value >> 8) & 0xff); out.write(value & 0xff); }
    private static void f2dot14(ByteArrayOutputStream out, double value) { short16(out, (int) Math.round(value * 16384)); }
    /** OpenType rounding (half up), as fontTools otRound. */
    static int round(double value) { return (int) Math.floor(value + 0.5); }

    static Map<String, Float> location(String tag, float value) {
        Map<String, Float> result = new LinkedHashMap<>(); result.put(tag, value); return result;
    }
}
