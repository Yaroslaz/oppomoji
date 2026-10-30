package io.github.yaroslaz.oppomoji;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.TreeMap;
import static org.junit.Assert.*;

public class FontTablesTest {
    @Test public void cmapRoundTripsMappingsAndVariationSequences() throws Exception {
        TreeMap<Integer, Integer> map = new TreeMap<>();
        map.put(0x20, 3); map.put(0x31, 20); map.put(0x32, 21); map.put(0xA9, 108); map.put(0x1F600, 5000); map.put(0x1F601, 5001);
        FontTables.Sequences sequences = new FontTables.Sequences();
        sequences.mapped(FontTables.VS16).put(0xA9, 4531);
        sequences.defaults(FontTables.VS16).add(0x1F600);
        sequences.mapped(FontTables.VS15).put(0x1F601, 99);
        byte[] cmap = FontTables.writeCmap(map, sequences);
        assertEquals(map, FontTables.mapping(cmap));
        FontTables.Sequences read = FontTables.sequences(cmap);
        assertEquals(Integer.valueOf(4531), read.mapped(FontTables.VS16).get(0xA9));
        assertTrue(read.defaults(FontTables.VS16).contains(0x1F600));
        assertEquals(Integer.valueOf(99), read.mapped(FontTables.VS15).get(0x1F601));
    }

    @Test public void slotEmojiFontLeavesTextToTheSystemButKeepsEmojiPresentation() throws Exception {
        TreeMap<Integer, Integer> map = new TreeMap<>();
        map.put(0x20, 3); map.put(0x23, 4); map.put(0x31, 7); map.put(0xA9, 16); map.put(0x200D, 18); map.put(0x20E3, 21);
        map.put(0x2122, 22); map.put(0x2764, 168); map.put(0x1F600, 883);
        FontTables.Sequences sequences = new FontTables.Sequences();
        for (int cp : new int[]{0x23, 0x31, 0xA9, 0x2122, 0x2764}) sequences.defaults(FontTables.VS16).add(cp);
        byte[] slot = EmojiSlot.cmap(FontTables.writeCmap(map, sequences), true);
        TreeMap<Integer, Integer> kept = FontTables.mapping(slot);
        for (int cp : new int[]{0x23, 0x31, 0xA9, 0x2122}) assertFalse(Integer.toHexString(cp), kept.containsKey(cp));
        for (int cp : new int[]{0x200D, 0x20E3, 0x2764, 0x1F600}) assertTrue(Integer.toHexString(cp), kept.containsKey(cp));
        // The narrowed space glyph serves both spaces, so no line falls back to the tall Myanmar space.
        assertEquals(Integer.valueOf(3), kept.get(0x20));
        assertEquals(Integer.valueOf(3), kept.get(0xA0));
        TreeMap<Integer, Integer> withoutSpaces = FontTables.mapping(EmojiSlot.cmap(FontTables.writeCmap(map, sequences), false));
        assertFalse(withoutSpaces.containsKey(0x20));
        assertFalse(withoutSpaces.containsKey(0xA0));
        FontTables.Sequences read = FontTables.sequences(slot);
        // "1️⃣", "#️⃣", "©️" and "™️" still reach their pictures; "❤" stays the emoji font's own glyph.
        assertEquals(Integer.valueOf(7), read.mapped(FontTables.VS16).get(0x31));
        assertEquals(Integer.valueOf(4), read.mapped(FontTables.VS16).get(0x23));
        assertEquals(Integer.valueOf(16), read.mapped(FontTables.VS16).get(0xA9));
        assertEquals(Integer.valueOf(22), read.mapped(FontTables.VS16).get(0x2122));
        assertTrue(read.defaults(FontTables.VS16).contains(0x2764));
    }

    @Test public void textLikeCharactersMatchTheSlotRules() {
        for (int cp : new int[]{'1', '#', '*', '.', '?', 0x2026, 0xA9, 0xAE, 0x203C, 0x2049, 0x2122, 0x2139, 0x2194, 0x21AA}) assertTrue(Integer.toHexString(cp), EmojiSlot.textLike(cp));
        for (int cp : new int[]{' ', 0xA0, 0x200D, 0x20E3, 0x231A, 0x2764, 0xFE0F, 0x1F600, 0xE0067}) assertFalse(Integer.toHexString(cp), EmojiSlot.textLike(cp));
    }

    @Test public void setsAnAdvanceOnlyWhereHmtxHasItsOwnEntry() {
        byte[] hhea = new byte[36]; ByteBuffer.wrap(hhea).putShort(34, (short) 4);
        byte[] hmtx = new byte[4 * 4 + 2 * 2];
        ByteBuffer.wrap(hmtx).putShort(3 * 4, (short) 2550);
        assertTrue(EmojiSlot.setAdvance(hhea, hmtx, 3, 507));
        assertEquals(507, ByteBuffer.wrap(hmtx).getShort(3 * 4));
        assertFalse(EmojiSlot.setAdvance(hhea, hmtx, 5, 507));
    }

    @Test public void normalizesThroughAvarAndScalesTuples() throws Exception {
        VariableInstancer.Axis wght = new VariableInstancer.Axis("wght", 100, 400, 900);
        assertArrayEquals(new double[]{0.6}, VariableInstancer.normalize(Collections.singletonList(wght), VariableInstancer.location("wght", 700), null), 1e-4);
        assertArrayEquals(new double[]{-1}, VariableInstancer.normalize(Collections.singletonList(wght), VariableInstancer.location("wght", 50), null), 1e-4);
        ByteBuffer avar = ByteBuffer.allocate(8 + 2 + 4 * 4); // maps 0.5 -> 0.25
        avar.putInt(0x00010000).putShort((short) 0).putShort((short) 1).putShort((short) 4);
        for (double[] pair : new double[][]{{-1, -1}, {0, 0}, {0.5, 0.25}, {1, 1}}) avar.putShort((short) (pair[0] * 16384)).putShort((short) (pair[1] * 16384));
        assertArrayEquals(new double[]{0.25}, VariableInstancer.normalize(Collections.singletonList(wght), VariableInstancer.location("wght", 650), avar.array()), 1e-4);
        assertEquals(0.5, VariableInstancer.scalar(new double[]{0.5}, new double[]{1}, null, null), 1e-9);
        assertEquals(0, VariableInstancer.scalar(new double[]{-0.5}, new double[]{1}, null, null), 1e-9);
        assertEquals(1, VariableInstancer.scalar(new double[]{0.3}, new double[]{0}, null, null), 1e-9);
        assertEquals(0.5, VariableInstancer.scalar(new double[]{0.75}, new double[]{0.5}, new double[]{0}, new double[]{1}), 1e-9);
    }
}
