package io.github.yaroslaz.oppomoji;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads small metadata tables without loading color bitmaps or creating native font families. */
final class FontFile implements AutoCloseable {
    private final RandomAccessFile input;
    private final long length;
    private final long[] faces;

    FontFile(File file) throws IOException {
        input = new RandomAccessFile(file, "r"); length = input.length();
        try {
            if (length < 12) throw new IOException("Слишком короткий файл шрифта");
            int signature = input.readInt();
            if (signature == 0x74746366) {
                int version = input.readInt(), count = input.readInt();
                if ((version != 0x00010000 && version != 0x00020000) || count < 1 || count > 128 || 12L + count * 4L > length) throw new IOException("Повреждён каталог TTC");
                faces = new long[count];
                for (int i = 0; i < count; i++) faces[i] = input.readInt() & 0xffffffffL;
            } else if (signature == 0x00010000 || signature == 0x4F54544F || signature == 0x74727565) faces = new long[]{0};
            else throw new IOException("Нужен файл TTF, OTF или TTC");
        } catch (IOException | RuntimeException error) { input.close(); throw error; }
    }

    int faceCount() { return faces.length; }

    private Map<String, long[]> directory(int face) throws IOException {
        if (face < 0 || face >= faces.length || faces[face] + 12 > length) throw new IOException("Некорректный индекс шрифта TTC");
        input.seek(faces[face]); int signature = input.readInt();
        if (signature != 0x00010000 && signature != 0x4F54544F && signature != 0x74727565) throw new IOException("Некорректный SFNT в коллекции");
        int count = input.readUnsignedShort(); input.skipBytes(6);
        if (count < 1 || count > 256 || faces[face] + 12L + count * 16L > length) throw new IOException("Повреждён каталог шрифта");
        Map<String, long[]> tables = new LinkedHashMap<>();
        byte[] tag = new byte[4];
        for (int i = 0; i < count; i++) {
            input.readFully(tag); String name = new String(tag, StandardCharsets.ISO_8859_1);
            input.readInt(); long offset = input.readInt() & 0xffffffffL, size = input.readInt() & 0xffffffffL;
            if (offset + size > length || offset % 4 != 0 || tables.put(name, new long[]{offset, size}) != null) throw new IOException("Повреждена таблица " + name);
        }
        return tables;
    }

    private byte[] table(long[] entry, long limit) throws IOException {
        if (entry[1] > limit) throw new IOException("Таблица метаданных слишком большая");
        byte[] data = new byte[(int) entry[1]]; input.seek(entry[0]); input.readFully(data); return data;
    }

    SfntFont metadata(int face) throws IOException {
        Map<String, long[]> directory = directory(face);
        Map<String, byte[]> small = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> entry : directory.entrySet()) {
            String name = entry.getKey();
            if (name.equals("head") || name.equals("name") || name.equals("fvar") || name.equals("hhea") || name.equals("cmap")) small.put(name, table(entry.getValue(), 1024 * 1024));
            else if (name.equals("post")) {
                int size = (int) Math.min(32, entry.getValue()[1]); byte[] post = new byte[size]; input.seek(entry.getValue()[0]); input.readFully(post); small.put(name, post);
            } else if (!name.equals("DSIG")) small.put(name, new byte[0]);
        }
        input.seek(faces[face]); return new SfntFont(SfntFont.pack(input.readInt(), small));
    }

    Map<String, Object> describe(int face) throws IOException {
        Map<String, Object> result = metadata(face).describe();
        result.put("tables", new java.util.ArrayList<>(directory(face).keySet()));
        result.put("fileBytes", length); result.put("ttcIndex", face); result.remove("bytes"); return result;
    }

    byte[] extract(int face) throws IOException {
        Map<String, long[]> directory = directory(face);
        long size = 12 + directory.size() * 16L;
        for (long[] table : directory.values()) size += (table[1] + 3) & ~3L;
        if (size > SfntFont.MAX_BYTES) throw new IOException("Шрифт больше 64 МБ");
        Map<String, byte[]> tables = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> entry : directory.entrySet()) if (!entry.getKey().equals("DSIG")) tables.put(entry.getKey(), table(entry.getValue(), SfntFont.MAX_BYTES));
        input.seek(faces[face]); byte[] result = SfntFont.pack(input.readInt(), tables);
        new SfntFont(result); return result;
    }

    @Override public void close() throws IOException { input.close(); }
}
