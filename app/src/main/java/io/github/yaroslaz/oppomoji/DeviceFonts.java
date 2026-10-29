package io.github.yaroslaz.oppomoji;

import android.os.Build;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class DeviceFonts {
    private static final String[] DIRECTORIES = {
            "/system/fonts", "/product/fonts", "/system_ext/fonts", "/vendor/fonts",
            "/my_product/fonts", "/my_stock/fonts", "/oplus_product/fonts"
    };
    private static final String[] CONFIGS = {
            "/system/etc/fonts.xml", "/system/etc/font_fallback.xml",
            "/product/etc/fonts_customization.xml", "/system_ext/etc/fonts_customization.xml",
            "/my_product/etc/fonts_customization.xml"
    };

    static final class Candidate {
        final File file; final int index; final String family; final boolean cyrillic;
        Candidate(File file, int index, String family, boolean cyrillic) { this.file = file; this.index = index; this.family = family; this.cyrillic = cyrillic; }
        String label() { return family + " · " + file.getName() + (index > 0 ? " [" + index + "]" : "") + (cyrillic ? "" : " · нет кириллицы"); }
    }

    static boolean isOppoSans(String family, String filename) {
        String internal = family.toLowerCase(Locale.ROOT);
        if (internal.contains("serif") || isDigitsFont(family)) return false;
        if (sansName(internal)) return true;
        String file = filename.toLowerCase(Locale.ROOT);
        return !file.contains("serif") && !isDigitsFont(filename) && sansName(file);
    }

    static boolean isDigitsFont(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").contains("soliddigits");
    }

    private static boolean sansName(String value) {
        String normalized = value.replaceAll("[^a-z0-9]", "");
        return normalized.contains("opposans") || normalized.contains("oplussans") || normalized.contains("oneplussans");
    }

    static List<Candidate> candidates() {
        List<Candidate> found = new ArrayList<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        for (String directory : DIRECTORIES) {
            File[] files;
            try { files = new File(directory).listFiles(); } catch (SecurityException error) { continue; }
            if (files == null) continue;
            for (File file : files) {
                String name = file.getName().toLowerCase(Locale.ROOT);
                if (!(name.endsWith(".ttf") || name.endsWith(".otf") || name.endsWith(".ttc"))) continue;
                try {
                    if (!file.canRead() || !visited.add(file.getCanonicalPath())) continue;
                    try (FontFile font = new FontFile(file)) {
                        for (int index = 0; index < font.faceCount(); index++) {
                            SfntFont metadata = font.metadata(index); String family = metadata.familyName();
                            if (isOppoSans(family, name)) found.add(new Candidate(file, index, family,
                                    metadata.hasCodePoint('Я') && metadata.hasCodePoint('я') && metadata.hasCodePoint('щ')));
                        }
                    }
                } catch (IOException | RuntimeException ignored) {
                    // One inaccessible or unsupported font must not abort the scan or report.
                }
            }
        }
        found.sort(Comparator.comparingInt(DeviceFonts::priority).thenComparing(c -> c.file.getAbsolutePath()));
        return found;
    }

    static int priority(Candidate font) {
        String name = (font.family + " " + font.file.getName()).toLowerCase(Locale.ROOT);
        boolean english = name.matches(".*(?:[ ._-]en[ ._-]|[ ._-]en$).*"),
                cjk = name.contains("cjk") || name.contains("hans") || name.contains("hant") || name.matches(".*[ ._-](?:sc|tc)(?:[ ._-]|$).*" );
        return (font.cyrillic ? 0 : 1000) + (english ? 0 : 30) + (cjk ? 50 : 0)
                + (name.replace(" ", "").contains("opposans") ? 0 : 10);
    }

    static byte[] read(InputStream input, int limit) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[16384];
            int length;
            while ((length = in.read(chunk)) != -1) {
                if ((long) out.size() + length > limit) throw new IOException("Файл больше " + limit / 1024 / 1024 + " МБ");
                out.write(chunk, 0, length);
            }
            return out.toByteArray();
        }
    }

    /** Exact-size read: a 35 MB emoji font must not pass through a growing buffer. */
    static byte[] read(File file) throws IOException {
        if (Files.size(file.toPath()) > SfntFont.MAX_BYTES) throw new IOException("Файл больше " + SfntFont.MAX_BYTES / 1024 / 1024 + " МБ");
        return Files.readAllBytes(file.toPath());
    }

    static JSONObject report(Context context, File text, File emoji, File repaired, int weight, int depth, int thickness) throws Exception {
        JSONObject report = new JSONObject();
        report.put("appVersion", BuildConfig.VERSION_NAME);
        report.put("createdAt", java.time.Instant.now().toString());
        report.put("manufacturer", Build.MANUFACTURER); report.put("model", Build.MODEL);
        report.put("android", Build.VERSION.RELEASE); report.put("sdk", Build.VERSION.SDK_INT);
        report.put("buildDisplay", Build.DISPLAY); report.put("locale", Locale.getDefault().toLanguageTag());
        report.put("systemChangesAppliedByApp", false);
        try { finite(report, "systemFontScale", Settings.System.getFloat(context.getContentResolver(), Settings.System.FONT_SCALE, 1f)); }
        catch (RuntimeException error) { report.put("systemFontScaleError", error.toString()); }
        JSONObject active = new JSONObject();
        try {
            Paint paint = new Paint(); paint.setTypeface(Typeface.DEFAULT); paint.setTextSize(100);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            active.put("textSizePx", 100); finite(active, "ascent", metrics.ascent); finite(active, "descent", metrics.descent);
            finite(active, "top", metrics.top); finite(active, "bottom", metrics.bottom); finite(active, "leading", metrics.leading);
            finite(active, "underlinePosition", paint.getUnderlinePosition()); finite(active, "underlineThickness", paint.getUnderlineThickness());
            active.put("underlineIsAboveBaseline", paint.getUnderlinePosition() < 0);
        } catch (RuntimeException error) { active.put("error", error.toString()); }
        report.put("activeSystemTypefaceMetrics", active);
        report.put("systemResolvedFonts", SystemFontAccess.report());
        report.put("previewWeight", weight); report.put("underlineDepthPercent", depth);
        report.put("underlineThicknessPercent", thickness / 10.0);
        report.put("textFont", description(text)); report.put("emojiFont", description(emoji));
        report.put("repairedFont", description(repaired));
        report.put("repairedTextFont", description(new File(context.getFilesDir(), "text-repaired.ttf")));
        JSONArray candidates = new JSONArray();
        for (Candidate font : candidates()) candidates.put(font.file.getAbsolutePath() + " #" + font.index + " " + font.family);
        report.put("oppoFontCandidates", candidates);
        JSONObject configs = new JSONObject();
        for (String path : CONFIGS) {
            File file = new File(path);
            if (!file.isFile() || !file.canRead()) continue;
            try { configs.put(path, new String(read(Files.newInputStream(file.toPath()), 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8)); }
            catch (IOException e) { configs.put(path, "Не удалось прочитать: " + e.getMessage()); }
        }
        report.put("fontConfigurations", configs);
        return report;
    }

    static String clipboardSummary(JSONObject report) throws org.json.JSONException {
        JSONObject brief = new JSONObject(report.toString());
        JSONObject configs = brief.optJSONObject("fontConfigurations");
        JSONArray paths = new JSONArray();
        if (configs != null) for (java.util.Iterator<String> keys = configs.keys(); keys.hasNext();) paths.put(keys.next());
        brief.remove("fontConfigurations"); brief.put("fontConfigurationFiles", paths);
        brief.put("reportFormat", "summary; full XML is in the saved JSON file");
        return brief.toString(2);
    }

    static void finite(JSONObject object, String key, float value) throws org.json.JSONException {
        object.put(key, Float.isFinite(value) ? value : JSONObject.NULL);
    }

    private static Object description(File file) throws Exception {
        if (!file.isFile()) return JSONObject.NULL;
        try (FontFile font = new FontFile(file)) { return new JSONObject(font.describe(0)); }
        catch (IOException | RuntimeException error) { return new JSONObject().put("error", error.toString()); }
    }
}
