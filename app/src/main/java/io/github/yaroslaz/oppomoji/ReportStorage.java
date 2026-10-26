package io.github.yaroslaz.oppomoji;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.IOException;
import java.io.OutputStream;

/** Writes only files explicitly exported by the user, without a DocumentsUI activity. */
final class ReportStorage {
    interface Write { void write(OutputStream output) throws IOException; }

    static Uri save(Context context, String filename, String mime, Write writer) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OPPOMoji");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("ColorOS не предоставила место в Загрузках");
        try {
            try (OutputStream output = context.getContentResolver().openOutputStream(uri, "w")) {
                if (output == null) throw new IOException("Не удалось открыть файл в Загрузках");
                writer.write(output);
            }
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (context.getContentResolver().update(uri, values, null, null) == 0) throw new IOException("Не удалось завершить сохранение");
            return uri;
        } catch (IOException | RuntimeException error) {
            try { context.getContentResolver().delete(uri, null, null); } catch (RuntimeException ignored) { }
            throw error;
        }
    }
}
