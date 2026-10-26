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
