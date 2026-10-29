package io.github.yaroslaz.oppomoji;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.TreeMap;
import static org.junit.Assert.*;

public class FontMergerTest {
    @Test public void cmapRoundTripsMappingsAndVariationSequences() throws Exception {
        TreeMap<Integer, Integer> map = new TreeMap<>();
        map.put(0x20, 3); map.put(0x31, 20); map.put(0x32, 21); map.put(0xA9, 108); map.put(0x1F600, 5000); map.put(0x1F601, 5001);
        FontMerger.Sequences sequences = new FontMerger.Sequences();
        sequences.mapped(FontMerger.VS16).put(0xA9, 4531); // "©️" -> emoji picture while "©" stays text
        sequences.defaults(FontMerger.VS16).add(0x1F600);
        sequences.mapped(FontMerger.VS15).put(0x1F601, 99);
        byte[] cmap = FontMerger.writeCmap(map, sequences);
        assertEquals(map, FontMerger.mapping(cmap));
        FontMerger.Sequences read = FontMerger.sequences(cmap);
        assertEquals(Integer.valueOf(4531), read.mapped(FontMerger.VS16).get(0xA9));
        assertTrue(read.defaults(FontMerger.VS16).contains(0x1F600));
        assertEquals(Integer.valueOf(99), read.mapped(FontMerger.VS15).get(0x1F601));
    }

    @Test public void structuralEmojiCharactersAlwaysComeFromTheEmojiFont() {
        for (int cp : new int[]{0x200D, 0x20E3, 0xFE0F, 0xE0067, 0x1F1F7, 0x1F3FB, 0x1F600, 0x1FAEA}) assertTrue(Integer.toHexString(cp), FontMerger.structural(cp));
        for (int cp : new int[]{'1', ' ', '.', 0xA9, 0x2122, 0x203C, 'A', 'Я'}) assertFalse(Integer.toHexString(cp), FontMerger.structural(cp));
    }

    @Test public void cblcGlyphRangesMoveBehindTheTextGlyphs() throws Exception {
        ByteBuffer cblc = ByteBuffer.allocate(8 + 48 + 8 + 8 + 12);
        cblc.putInt(0x00030000).putInt(1);
        cblc.putInt(56).putInt(0).putInt(1).putInt(0); // index array at 56, one subtable
        cblc.position(8 + 40); cblc.putShort((short) 4).putShort((short) 9);
        cblc.position(56); cblc.putShort((short) 4).putShort((short) 9).putInt(8); // first, last, subtable offset
        cblc.putShort((short) 1).putShort((short) 17).putInt(0);
        byte[] shifted = FontMerger.shiftCblc(cblc.array(), 1000);
        ByteBuffer b = ByteBuffer.wrap(shifted);
        assertEquals(1004, b.getShort(8 + 40) & 0xffff); assertEquals(1009, b.getShort(8 + 42) & 0xffff);
        assertEquals(1004, b.getShort(56) & 0xffff); assertEquals(1009, b.getShort(58) & 0xffff);
    }

    @Test public void normalizesThroughAvarAndScalesTuples() throws Exception {
        VariableInstancer.Axis wght = new VariableInstancer.Axis("wght", 100, 400, 900);
        assertArrayEquals(new double[]{0.6}, VariableInstancer.normalize(Collections.singletonList(wght), VariableInstancer.location("wght", 700), null), 1e-4);
        assertArrayEquals(new double[]{-1}, VariableInstancer.normalize(Collections.singletonList(wght), VariableInstancer.location("wght", 50), null), 1e-4);
        // avar maps 0.5 -> 0.25.
        ByteBuffer avar = ByteBuffer.allocate(8 + 2 + 4 * 4);
        avar.putInt(0x00010000).putShort((short) 0).putShort((short) 1).putShort((short) 4);
        for (double[] pair : new double[][]{{-1, -1}, {0, 0}, {0.5, 0.25}, {1, 1}}) avar.putShort((short) (pair[0] * 16384)).putShort((short) (pair[1] * 16384));
        assertArrayEquals(new double[]{0.25}, VariableInstancer.normalize(Collections.singletonList(wght), VariableInstancer.location("wght", 650), avar.array()), 1e-4);
        assertEquals(0.5, VariableInstancer.scalar(new double[]{0.5}, new double[]{1}, null, null), 1e-9);
        assertEquals(0, VariableInstancer.scalar(new double[]{-0.5}, new double[]{1}, null, null), 1e-9);
        assertEquals(1, VariableInstancer.scalar(new double[]{0.3}, new double[]{0}, null, null), 1e-9);
        assertEquals(0.5, VariableInstancer.scalar(new double[]{0.75}, new double[]{0.5}, new double[]{0}, new double[]{1}), 1e-9);
    }
}
