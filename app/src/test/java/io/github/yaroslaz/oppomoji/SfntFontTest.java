package io.github.yaroslaz.oppomoji;

import org.junit.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class SfntFontTest {
    private static byte[] fixture(boolean variations, boolean signature) {
        Map<String, byte[]> tables = new LinkedHashMap<>();
        byte[] head = new byte[54];
        ByteBuffer.wrap(head).putInt(12, 0x5F0F3CF5).putShort(18, (short) 1000);
        tables.put("head", head);
        byte[] post = new byte[32];
        ByteBuffer.wrap(post).putInt(0, 0x00030000).putShort(8, (short) -900).putShort(10, (short) 800);
        tables.put("post", post);
        tables.put("hhea", new byte[36]);
        tables.put("cmap", new byte[]{1, 2, 3, 4, 5});
        tables.put("CBDT", new byte[]{9, 8, 7});
        tables.put("CBLC", new byte[]{6, 5, 4});
        if (variations) tables.put("MVAR", new byte[12]);
        if (signature) tables.put("DSIG", new byte[8]);
        int length = 12 + tables.size() * 16;
        for (byte[] table : tables.values()) length += (table.length + 3) & ~3;
        ByteBuffer out = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
        out.putInt(0x00010000).putShort((short) tables.size());
        int offset = 12 + tables.size() * 16, index = 0;
        for (Map.Entry<String, byte[]> entry : tables.entrySet()) {
            out.position(12 + index++ * 16);
            out.put(entry.getKey().getBytes(StandardCharsets.US_ASCII)).putInt(0).putInt(offset).putInt(entry.getValue().length);
            out.position(offset); out.put(entry.getValue()); offset += (entry.getValue().length + 3) & ~3;
        }
        return out.array();
    }

    @Test public void repairsOnlyUnderlineAndChecksumsWithoutMutatingInput() throws Exception {
        byte[] input = fixture(false, true), saved = input.clone();
        SfntFont original = new SfntFont(input);
        byte[] patched = original.withUnderline(-100, 45);
        SfntFont repaired = new SfntFont(patched);
        assertEquals(-100, repaired.underlinePosition()); assertEquals(45, repaired.underlineThickness());
        assertEquals(-900, original.underlinePosition()); assertArrayEquals(saved, input);
        assertEquals(0xB1B0AFBAL, SfntFont.checksum(patched));
        assertFalse(repaired.hasTable("DSIG"));
        for (String tag : new String[]{"cmap", "CBDT", "CBLC", "hhea"}) {
            assertArrayEquals(table(input, tag), table(patched, tag));
        }
        assertEquals("CBDT/CBLC", repaired.colorFormat());
    }

    @Test public void everyTableChecksumAndOffsetIsValid() throws Exception {
        byte[] bytes = new SfntFont(fixture(false, false)).withUnderline(-80, 30);
        ByteBuffer data = ByteBuffer.wrap(bytes); int count = data.getShort(4) & 0xffff;
        for (int i = 0; i < count; i++) {
            int record = 12 + i * 16;
            String tag = new String(bytes, record, 4, StandardCharsets.US_ASCII);
            int offset = data.getInt(record + 8); assertEquals(0, offset % 4);
            byte[] contents = table(bytes, tag);
            if (tag.equals("head")) ByteBuffer.wrap(contents).putInt(8, 0);
            assertEquals(data.getInt(record + 4) & 0xffffffffL, SfntFont.checksum(contents));
        }
    }

    @Test public void repeatRepairIsDeterministic() throws Exception {
        SfntFont font = new SfntFont(fixture(false, false));
        byte[] first = font.withUnderline(-100, 40);
        assertArrayEquals(first, new SfntFont(first).withUnderline(-100, 40));
    }

    @Test public void refusesVariableMetricsRatherThanSilentlyOverridingThem() throws Exception {
        try { new SfntFont(fixture(true, false)).withUnderline(-100, 40); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("MVAR")); }
    }

    @Test public void rejectsOverflowingOffsetsAndLengths() throws Exception {
        byte[] input = fixture(false, false);
        ByteBuffer.wrap(input).putInt(20, -16).putInt(24, 64);
        assertInvalid(input);
    }

    @Test public void rejectsOverlappingTables() throws Exception {
        byte[] input = fixture(false, false); ByteBuffer b = ByteBuffer.wrap(input);
        b.putInt(36, b.getInt(20)); assertInvalid(input);
    }

    @Test public void rejectsDuplicateTags() throws Exception {
        byte[] input = fixture(false, false);
        System.arraycopy(input, 12, input, 28, 4); assertInvalid(input);
    }

    @Test public void rejectsTableInsideDirectory() throws Exception {
        byte[] input = fixture(false, false); ByteBuffer.wrap(input).putInt(20, 12); assertInvalid(input);
    }

    @Test public void rejectsCollectionsAndArchives() throws Exception {
        byte[] input = fixture(false, false);
        ByteBuffer.wrap(input).putInt(0, 0x74746366); assertInvalid(input);
        ByteBuffer.wrap(input).putInt(0, 0x504B0304); assertInvalid(input);
    }

    @Test public void rejectsTruncatedHead() throws Exception {
        byte[] input = fixture(false, false); ByteBuffer.wrap(input).putInt(24, 20); assertInvalid(input);
    }

    @Test public void rejectsInvalidMetricValues() throws Exception {
        SfntFont font = new SfntFont(fixture(false, false));
        for (int[] values : new int[][]{{1, 40}, {-2000, 40}, {-100, 0}, {-100, 500}}) {
            try { font.withUnderline(values[0], values[1]); fail(); } catch (IOException expected) { /* expected */ }
        }
    }

    /** cmap: format 12 for ©, ® and a format 14 with text (U+FE0E) and emoji (U+FE0F) selectors. */
    private static byte[] variationFont(boolean colour) {
        ByteBuffer base = ByteBuffer.allocate(16 + 2 * 12);
        base.putShort((short) 12).putShort((short) 0).putInt(16 + 2 * 12).putInt(0).putInt(2);
        base.putInt(0xA9).putInt(0xA9).putInt(1).putInt(0xAE).putInt(0xAE).putInt(2);
        // FE0E: default UVS ©. FE0F: default UVS ©..® and a non-default mapping for U+2122 -> glyph 3.
        ByteBuffer sequences = ByteBuffer.allocate(10 + 2 * 11 + 8 + 8 + 9);
        sequences.putShort((short) 14).putInt(sequences.capacity()).putInt(2);
        sequences.put((byte) 0).put((byte) 0xFE).put((byte) 0x0E).putInt(32).putInt(0);
        sequences.put((byte) 0).put((byte) 0xFE).put((byte) 0x0F).putInt(40).putInt(48);
        sequences.putInt(1).put((byte) 0).put((byte) 0).put((byte) 0xA9).put((byte) 0);
        sequences.putInt(1).put((byte) 0).put((byte) 0).put((byte) 0xA9).put((byte) 5);
        sequences.putInt(1).put((byte) 0).put((byte) 0x21).put((byte) 0x22).putShort((short) 3);
        ByteBuffer cmap = ByteBuffer.allocate(4 + 2 * 8 + base.capacity() + sequences.capacity());
        cmap.putShort((short) 0).putShort((short) 2);
        cmap.putShort((short) 0).putShort((short) 5).putInt(20 + base.capacity());
        cmap.putShort((short) 3).putShort((short) 10).putInt(20);
        cmap.put(base.array()).put(sequences.array());
        Map<String, byte[]> tables = new LinkedHashMap<>();
        byte[] head = new byte[54]; ByteBuffer.wrap(head).putInt(12, 0x5F0F3CF5).putShort(18, (short) 1000);
        tables.put("head", head);
        byte[] post = new byte[32]; ByteBuffer.wrap(post).putInt(0, 0x00030000).putShort(8, (short) -100).putShort(10, (short) 50);
        tables.put("post", post); tables.put("cmap", cmap.array());
        if (colour) { tables.put("CBDT", new byte[4]); tables.put("CBLC", new byte[4]); }
        try { return SfntFont.pack(0x00010000, tables); } catch (IOException error) { throw new AssertionError(error); }
    }

    @Test public void readsDefaultAndNonDefaultVariationSequences() throws Exception {
        SfntFont font = new SfntFont(variationFont(false));
        assertTrue(font.hasVariationSequence(0xA9, 0xFE0F)); assertTrue(font.hasVariationSequence(0xAE, 0xFE0F));
        assertTrue(font.hasVariationSequence(0x2122, 0xFE0F)); assertTrue(font.hasVariationSequence(0xA9, 0xFE0E));
        assertFalse(font.hasVariationSequence(0xAE, 0xFE0E)); assertFalse(font.hasVariationSequence(0xAF, 0xFE0F));
        assertTrue(font.claimsEmojiPresentation());
        assertFalse(new SfntFont(variationFont(true)).claimsEmojiPresentation());
        assertFalse(new SfntFont(fixture(false, false)).claimsEmojiPresentation());
    }

    @Test public void textCopyHandsOnlyEmojiPresentationToFallback() throws Exception {
        byte[] input = variationFont(false);
        SfntFont original = new SfntFont(input);
        SfntFont released = new SfntFont(original.withoutEmojiPresentation());
        assertFalse(released.claimsEmojiPresentation());
        assertFalse(released.hasVariationSequence(0xA9, 0xFE0F)); assertFalse(released.hasVariationSequence(0x2122, 0xFE0F));
        assertTrue(released.hasVariationSequence(0xA9, 0xFE0E)); assertTrue(released.hasCodePoint(0xA9));
        assertEquals(-100, released.underlinePosition());
        assertEquals(table(input, "cmap").length, table(original.withoutEmojiPresentation(), "cmap").length);
        assertTrue(original.hasVariationSequence(0xA9, 0xFE0F));

        SfntFont repaired = new SfntFont(original.withUnderline(-150, 60, true));
        assertEquals(-150, repaired.underlinePosition()); assertFalse(repaired.claimsEmojiPresentation());
        assertTrue(new SfntFont(original.withUnderline(-150, 60)).claimsEmojiPresentation());
        try { released.withoutEmojiPresentation(); fail(); } catch (IOException expected) { /* nothing left to release */ }
    }

    @Test public void ignoresTruncatedVariationSubtable() throws Exception {
        byte[] input = variationFont(false);
        byte[] cmap = table(input, "cmap");
        ByteBuffer.wrap(cmap).putInt(20 + 40 + 2, 1 << 20);
        assertEquals(0, SfntFont.releaseEmojiPresentation(cmap));
    }

    @Test public void checksumPadsPartialWord() { assertEquals(0x01020300L, SfntFont.checksum(new byte[]{1, 2, 3})); }

    private static byte[] table(byte[] font, String tag) {
        ByteBuffer b = ByteBuffer.wrap(font); int count = b.getShort(4) & 0xffff;
        for (int i = 0; i < count; i++) {
            int record = 12 + i * 16;
            if (new String(font, record, 4, StandardCharsets.US_ASCII).equals(tag)) {
                int offset = b.getInt(record + 8); return Arrays.copyOfRange(font, offset, offset + b.getInt(record + 12));
            }
        }
        throw new AssertionError(tag);
    }
    private static void assertInvalid(byte[] input) throws Exception {
        try { new SfntFont(input); fail("Expected an invalid font"); } catch (IOException expected) { /* expected */ }
    }
}
