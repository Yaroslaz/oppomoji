package io.github.yaroslaz.oppomoji;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class FontFileTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private static Map<String, byte[]> tables(String family) {
        Map<String, byte[]> tables = new LinkedHashMap<>();
        byte[] head = new byte[54]; ByteBuffer.wrap(head).putInt(12, 0x5F0F3CF5).putShort(18, (short) 1000);
        tables.put("head", head);
        byte[] post = new byte[32]; ByteBuffer.wrap(post).putInt(0, 0x00030000).putShort(8, (short) -100).putShort(10, (short) 40); tables.put("post", post);
        byte[] text = family.getBytes(StandardCharsets.UTF_16BE);
        ByteBuffer name = ByteBuffer.allocate(18 + text.length);
        name.putShort((short) 0).putShort((short) 1).putShort((short) 18);
        name.putShort((short) 3).putShort((short) 1).putShort((short) 0x409).putShort((short) 16).putShort((short) text.length).putShort((short) 0).put(text);
        tables.put("name", name.array());
        ByteBuffer cmap = ByteBuffer.allocate(16 + 16 + 3 * 12);
        cmap.putShort((short) 0).putShort((short) 1).putShort((short) 3).putShort((short) 10).putInt(16);
        cmap.position(16); cmap.putShort((short) 12).putShort((short) 0).putInt(52).putInt(0).putInt(3);
        cmap.putInt('Я').putInt('Я').putInt(1).putInt('щ').putInt('щ').putInt(2).putInt('я').putInt('я').putInt(3);
        tables.put("cmap", cmap.array()); return tables;
    }

    @Test public void readsInternalFamilyAndCyrillicCoverage() throws Exception {
        File file = temp.newFile("generic.ttf"); Files.write(file.toPath(), SfntFont.pack(0x00010000, tables("OPPO Sans")));
        try (FontFile source = new FontFile(file)) {
            assertEquals("OPPO Sans", source.metadata(0).familyName());
            assertTrue(source.metadata(0).hasCodePoint('Я')); assertFalse(source.metadata(0).hasCodePoint('A'));
            assertEquals(true, source.describe(0).get("supportsCyrillicSample"));
        }
    }

    @Test public void extractsSansFromSecondFaceOfCollection() throws Exception {
        byte[] serif = SfntFont.pack(0x00010000, tables("OPPO Serif")), sans = SfntFont.pack(0x00010000, tables("OPPO Sans"));
        File file = temp.newFile("generic.ttc");
        int first = 20, second = first + serif.length;
        byte[] collection = new byte[second + sans.length];
        ByteBuffer b = ByteBuffer.wrap(collection); b.putInt(0x74746366).putInt(0x00010000).putInt(2).putInt(first).putInt(second);
        relocate(serif, first); relocate(sans, second);
        System.arraycopy(serif, 0, collection, first, serif.length); System.arraycopy(sans, 0, collection, second, sans.length);
        Files.write(file.toPath(), collection);
        try (FontFile source = new FontFile(file)) {
            assertEquals(2, source.faceCount()); assertEquals("OPPO Serif", source.metadata(0).familyName());
            byte[] extracted = source.extract(1);
            assertEquals("OPPO Sans", new SfntFont(extracted).familyName()); assertEquals(0xB1B0AFBAL, SfntFont.checksum(extracted));
        }
    }

    @Test public void metadataSkipsHugeBitmapTables() throws Exception {
        Map<String, byte[]> sourceTables = tables("OPPO Sans"); sourceTables.put("CBDT", new byte[4]); sourceTables.put("CBLC", new byte[4]);
        byte[] bytes = SfntFont.pack(0x00010000, sourceTables);
        File file = temp.newFile("large.ttf"); Files.write(file.toPath(), bytes);
        ByteBuffer b = ByteBuffer.wrap(bytes);
        try (RandomAccessFile data = new RandomAccessFile(file, "rw")) {
            data.setLength(128L * 1024 * 1024);
            for (int i = 0; i < (b.getShort(4) & 0xffff); i++) {
                int record = 12 + i * 16;
                if (new String(bytes, record, 4, StandardCharsets.US_ASCII).equals("CBDT")) {
                    data.seek(record + 8); data.writeInt(1024 * 1024); data.writeInt(127 * 1024 * 1024);
                }
            }
        }
        try (FontFile source = new FontFile(file)) {
            assertEquals("OPPO Sans", source.metadata(0).familyName()); assertEquals("CBDT/CBLC", source.metadata(0).colorFormat());
            try { source.extract(0); fail(); } catch (IOException expected) { assertTrue(expected.getMessage().contains("64")); }
        }
    }

    @Test public void removesOnlyUnderlineVariationRecords() throws Exception {
        Map<String, byte[]> source = tables("OPPO Sans");
        byte[] mvar = new byte[48]; ByteBuffer b = ByteBuffer.wrap(mvar);
        b.putShort((short) 1).putShort((short) 0).putShort((short) 0).putShort((short) 8).putShort((short) 3).putShort((short) 36);
        for (String tag : new String[]{"hasc", "undo", "unds"}) b.put(tag.getBytes(StandardCharsets.US_ASCII)).putShort((short) 0).putShort((short) 0);
        b.position(36); b.putShort((short) 1).putInt(8).putShort((short) 0).putShort((short) 1).putShort((short) 0);
        source.put("MVAR", mvar);
        byte[] fixed = new SfntFont(SfntFont.pack(0x00010000, source)).withUnderline(-200, 60);
        File file = temp.newFile("fixed.ttf"); Files.write(file.toPath(), fixed);
        byte[] actual = table(fixed, "MVAR");
        assertEquals(1, ByteBuffer.wrap(actual).getShort(8)); assertEquals(36, ByteBuffer.wrap(actual).getShort(10));
        assertArrayEquals(Arrays.copyOfRange(mvar, 12, 20), Arrays.copyOfRange(actual, 12, 20));
        assertArrayEquals(Arrays.copyOfRange(mvar, 36, 48), Arrays.copyOfRange(actual, 36, 48));
        assertEquals(0xB1B0AFBAL, SfntFont.checksum(fixed));
    }

    @Test public void emptyVariationRecordsHaveZeroStoreOffset() throws Exception {
        Map<String, byte[]> source = tables("OPPO Sans");
        byte[] mvar = new byte[32]; ByteBuffer b = ByteBuffer.wrap(mvar);
        b.putShort((short) 1).putShort((short) 0).putShort((short) 0).putShort((short) 8).putShort((short) 1).putShort((short) 20);
        b.put("undo".getBytes(StandardCharsets.US_ASCII)).putInt(0); source.put("MVAR", mvar);
        byte[] patched = table(new SfntFont(SfntFont.pack(0x00010000, source)).withUnderline(-100, 40), "MVAR");
        assertEquals(0, ByteBuffer.wrap(patched).getShort(8)); assertEquals(0, ByteBuffer.wrap(patched).getShort(10));
    }

    private static void relocate(byte[] face, int offset) {
        ByteBuffer b = ByteBuffer.wrap(face);
        for (int i = 0; i < (b.getShort(4) & 0xffff); i++) { int p = 12 + i * 16 + 8; b.putInt(p, b.getInt(p) + offset); }
    }
    private static byte[] table(byte[] bytes, String name) {
        ByteBuffer b = ByteBuffer.wrap(bytes);
        for (int i = 0; i < (b.getShort(4) & 0xffff); i++) {
            int record = 12 + i * 16;
            if (new String(bytes, record, 4, StandardCharsets.US_ASCII).equals(name)) {
                int start = b.getInt(record + 8); return Arrays.copyOfRange(bytes, start, start + b.getInt(record + 12));
            }
        }
        throw new AssertionError(name);
    }
}
