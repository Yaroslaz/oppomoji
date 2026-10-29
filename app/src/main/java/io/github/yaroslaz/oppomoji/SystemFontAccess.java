package io.github.yaroslaz.oppomoji;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.text.PositionedGlyphs;
import android.graphics.text.TextRunShaper;
import android.os.Build;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

/** Resolves fonts used for fixed test characters, rather than guessing from filenames. */
final class SystemFontAccess {
    static Font resolve(String sample) throws IOException {
        Paint paint = new Paint(); paint.setTypeface(Typeface.DEFAULT); paint.setTextSize(100);
        return resolve(sample, paint);
    }

    static Font resolve(String sample, Paint paint) throws IOException {
        if (Build.VERSION.SDK_INT < 31) throw new IOException("Чтение активного шрифта доступно с Android 12");
        PositionedGlyphs glyphs = TextRunShaper.shapeTextRun(sample, 0, sample.length(), 0, sample.length(), 0, 0, false, paint);
        if (glyphs.glyphCount() == 0 || glyphs.getGlyphId(0) == 0) throw new IOException("Система не нашла глиф для тестового символа");
        return glyphs.getFont(0);
    }

    static String familyName(Font font) {
        File file = font.getFile();
        if (file != null && file.canRead()) {
            try (FontFile source = new FontFile(file)) { return source.metadata(font.getTtcIndex()).familyName(); }
            catch (IOException | RuntimeException ignored) { /* The native Font remains usable without metadata. */ }
        }
        return "Активный системный emoji-шрифт";
    }

    static JSONObject report() throws Exception {
        JSONObject result = new JSONObject();
        if (Build.VERSION.SDK_INT < 31) return result.put("error", "Requires Android 12");
        // Digits, the period and the space went wrong when an emoji-only font filled the Dai slot.
        for (String[] probe : new String[][]{{"latin", "A"}, {"cyrillic", "Я"}, {"digit", "1"}, {"period", "."}, {"space", " "}, {"emoji", "😀"}}) {
            JSONObject item = new JSONObject();
            try {
                Font font = resolve(probe[1]);
                File file = font.getFile();
                item.put("file", file == null ? JSONObject.NULL : file.getAbsolutePath());
                item.put("ttcIndex", font.getTtcIndex()); item.put("weight", font.getStyle().getWeight());
                if (file != null && file.canRead()) {
                    try (FontFile source = new FontFile(file)) { item.put("metadata", new JSONObject(source.describe(font.getTtcIndex()))); }
                    catch (IOException | RuntimeException error) { item.put("metadataError", error.toString()); }
                }
                Paint paint = new Paint(); paint.setTextSize(100);
                Paint.FontMetrics metrics = new Paint.FontMetrics(); font.getMetrics(paint, metrics);
                item.put("metricsAt100px", LineDiagnostics.metrics(metrics));
            } catch (IOException | RuntimeException error) { item.put("error", error.toString()); }
            result.put(probe[0], item);
        }
        // Whether a bold request reaches a heavier font or variation, or only synthetic bolding.
        JSONObject bold = new JSONObject();
        try {
            Paint regular = new Paint(); regular.setTypeface(Typeface.DEFAULT); regular.setTextSize(100);
            Paint heavy = new Paint(); heavy.setTypeface(Typeface.create(Typeface.DEFAULT, 700, false)); heavy.setTextSize(100);
            Font font = resolve("Ш", heavy);
            bold.put("file", font.getFile() == null ? JSONObject.NULL : font.getFile().getAbsolutePath());
            bold.put("axes", java.util.Arrays.toString(font.getAxes()));
            bold.put("fontWeight", font.getStyle().getWeight());
            DeviceFonts.finite(bold, "widthRegular", regular.measureText("Шрифт OPPO 123"));
            DeviceFonts.finite(bold, "widthBold700", heavy.measureText("Шрифт OPPO 123"));
        } catch (IOException | RuntimeException error) { bold.put("error", error.toString()); }
        result.put("boldRequest", bold);
        return result;
    }

    /** Only writes a local copy. The mapped buffer is useful when ColorOS hides the file path. */
    static byte[] copy(Font font, File temporary) throws IOException {
        File file = font.getFile();
        if (file != null && file.isFile() && file.canRead()) {
            try (FontFile source = new FontFile(file)) { return source.extract(font.getTtcIndex()); }
        }
        ByteBuffer bytes = font.getBuffer().duplicate(); bytes.clear();
        if (bytes.remaining() > SfntFont.MAX_BYTES) throw new IOException("Активный шрифт больше 64 МБ. Открой исходный TTF отдельно");
        try {
            try (FileChannel output = FileChannel.open(temporary.toPath(), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                while (bytes.hasRemaining()) output.write(bytes);
            }
            try (FontFile source = new FontFile(temporary)) { return source.extract(font.getTtcIndex()); }
        } finally { java.nio.file.Files.deleteIfExists(temporary.toPath()); }
    }
}
