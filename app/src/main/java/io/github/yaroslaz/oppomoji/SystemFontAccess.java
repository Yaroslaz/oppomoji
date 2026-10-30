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
        return "Активный системный шрифт";
    }

    /** One line for the main screen: which files draw letters and emoji in the system right now. */
    static String summary() {
        if (Build.VERSION.SDK_INT < 31) return "Сейчас в системе: проверка доступна с Android 12";
        try {
            return "Сейчас в системе: буквы — " + describe(resolve("Я")) + "; эмодзи — " + describe(resolve("😀")) + ".";
        } catch (IOException | RuntimeException error) { return "Сейчас в системе: не удалось определить (" + error.getMessage() + ")"; }
    }

    private static String describe(Font font) {
        File file = font.getFile();
        String name = file == null ? "" : file.getName();
        boolean slot = file != null && file.getAbsolutePath().contains("format_unclear");
        return familyName(font) + (slot ? " из слота Dai (zFont)" : name.isEmpty() ? "" : " (" + name + ")");
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
        // System screens draw with elegant text height and the region locale (ru-MM under Dai); the font
        // chosen for punctuation there can differ from a plain Paint, and a tall fallback widens lines.
        JSONObject screen = new JSONObject();
        Paint ui = new Paint(); ui.setTypeface(Typeface.DEFAULT); ui.setTextSize(100);
        ui.setElegantTextHeight(true); ui.setTextLocales(android.os.LocaleList.getDefault());
        for (String probe : new String[]{".", ",", ":", "?", "\u00B7", "\u2022", "\u2026", "\u201C", "1", "A", "\u042F", " ", "\u00A0"}) {
            JSONObject item = new JSONObject();
            try {
                Font font = resolve(probe, ui);
                File file = font.getFile();
                item.put("file", file == null ? JSONObject.NULL : file.getAbsolutePath()).put("family", familyName(font));
                Paint plain = new Paint(); plain.setTextSize(100);
                Paint.FontMetrics metrics = new Paint.FontMetrics(); font.getMetrics(plain, metrics);
                item.put("metricsAt100px", LineDiagnostics.metrics(metrics));
            } catch (IOException | RuntimeException error) { item.put("error", error.toString()); }
            screen.put(probe, item);
        }
        result.put("screenText", screen);
        // Under Dai, Noto Sans Myanmar wins over Roboto for the characters it has (space, "?", "…", quotes).
        // The same probes with a Russian region show whether the region or the font order decides that.
        JSONObject russia = new JSONObject();
        Paint ru = new Paint(); ru.setTypeface(Typeface.DEFAULT); ru.setTextSize(100); ru.setTextLocales(android.os.LocaleList.forLanguageTags("ru-RU"));
        for (String probe : new String[]{" ", "?", "\u2026", "\u201C"}) {
            try {
                File file = resolve(probe, ru).getFile();
                russia.put(probe, file == null ? JSONObject.NULL : file.getAbsolutePath());
            } catch (IOException | RuntimeException error) { russia.put(probe, error.toString()); }
        }
        result.put("screenTextRuRu", russia);
        // ColorOS keeps OPPO Sans fallbacks tagged lang="osans-rc" and "osans-ext" behind Roboto. Under Dai the
        // letters fall to Roboto; a matching locale in the list might move them to OPPO Sans instead.
        JSONObject hints = new JSONObject();
        String system = android.os.LocaleList.getDefault().toLanguageTags();
        for (String tags : new String[]{system, system + "," + OSANS_HINT, OSANS_HINT + "," + system, system + ",osans", system + ",osans-ext"}) {
            JSONObject item = new JSONObject();
            Paint hinted = new Paint(); hinted.setTypeface(Typeface.DEFAULT); hinted.setTextSize(100);
            hinted.setTextLocales(android.os.LocaleList.forLanguageTags(tags));
            item.put("parsed", hinted.getTextLocales().toLanguageTags());
            for (String probe : new String[]{"\u042F", "A", "1", "?", "\u2026", "\u201C", " "}) {
                try {
                    File file = resolve(probe, hinted).getFile();
                    item.put(probe, file == null ? JSONObject.NULL : file.getName());
                } catch (IOException | RuntimeException error) { item.put(probe, error.toString()); }
            }
            DeviceFonts.finite(item, "width", hinted.measureText("Шрифт OPPO 123"));
            hints.put(tags, item);
        }
        result.put("osansLocaleHints", hints);
        JSONObject files = new JSONObject();
        for (String name : new String[]{"OSans-RC-Regular.ttf", "OSans-Ext-Regular.otf", "SysSans-En-Regular.ttf"}) {
            JSONObject item = new JSONObject();
            try (FontFile source = new FontFile(new File("/system/fonts", name))) {
                item.put("metadata", new JSONObject(source.describe(0)));
                SfntFont font = source.metadata(0);
                item.put("latin", count(font, 'A', 'Z') + count(font, 'a', 'z')).put("cyrillic", count(font, 0x410, 0x44F) + count(font, 0x401, 0x401) + count(font, 0x451, 0x451))
                        .put("digits", count(font, '0', '9'));
                StringBuilder punctuation = new StringBuilder();
                for (char c : ".,:;!?\u2026\u201C\u201D\u00AB\u00BB\u2014 ".toCharArray()) if (font.hasCodePoint(c)) punctuation.append(c);
                item.put("punctuation", punctuation.toString());
            } catch (IOException | RuntimeException error) { item.put("error", error.toString()); }
            files.put(name, item);
        }
        result.put("oplusFallbackFonts", files);
        return result;
    }

    /** The locale tag of the OPPO Sans fallback family in the ColorOS fonts.xml. */
    static final String OSANS_HINT = "osans-RC";

    private static int count(SfntFont font, int first, int last) {
        int found = 0;
        for (int cp = first; cp <= last; cp++) if (font.hasCodePoint(cp)) found++;
        return found;
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
