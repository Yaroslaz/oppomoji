package io.github.yaroslaz.oppomoji;

import android.os.Build;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
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

    static List<File> candidates() {
        List<File> found = new ArrayList<>();
        for (String directory : DIRECTORIES) {
            File[] files = new File(directory).listFiles();
            if (files == null) continue;
            for (File file : files) {
                String name = file.getName().toLowerCase(Locale.ROOT);
                if ((name.endsWith(".ttf") || name.endsWith(".otf"))
                        && (name.contains("oppo") || name.contains("oplus") || name.contains("onesans")
                        || name.contains("coloros")) && file.canRead() && file.length() <= SfntFont.MAX_BYTES) {
                    found.add(file);
                }
            }
        }
        found.sort(Comparator.comparingInt(DeviceFonts::priority).thenComparing(File::getAbsolutePath));
        return found;
    }

    private static int priority(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return (name.contains("opposans") ? 0 : 10) + (name.contains("vf") || name.contains("variable") ? 0 : 3)
                + (name.contains("cjk") || name.contains("sc-") ? 20 : 0);
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

    static byte[] read(File file) throws IOException { return read(Files.newInputStream(file.toPath()), SfntFont.MAX_BYTES); }

    static JSONObject report(Context context, File text, File emoji, File repaired, int weight, int depth, int thickness) throws Exception {
        JSONObject report = new JSONObject();
        report.put("appVersion", "0.1.0-alpha");
        report.put("createdAt", java.time.Instant.now().toString());
        report.put("manufacturer", Build.MANUFACTURER); report.put("model", Build.MODEL);
        report.put("android", Build.VERSION.RELEASE); report.put("sdk", Build.VERSION.SDK_INT);
        report.put("buildDisplay", Build.DISPLAY); report.put("locale", Locale.getDefault().toLanguageTag());
        report.put("systemChangesAppliedByApp", false);
        report.put("systemFontScale", Settings.System.getFloat(context.getContentResolver(), Settings.System.FONT_SCALE, 1f));
        Paint paint = new Paint(); paint.setTypeface(Typeface.DEFAULT); paint.setTextSize(100);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        JSONObject active = new JSONObject();
        active.put("textSizePx", 100); active.put("ascent", metrics.ascent); active.put("descent", metrics.descent);
        active.put("top", metrics.top); active.put("bottom", metrics.bottom); active.put("leading", metrics.leading);
        active.put("underlinePosition", paint.getUnderlinePosition()); active.put("underlineThickness", paint.getUnderlineThickness());
        active.put("underlineIsAboveBaseline", paint.getUnderlinePosition() < 0);
        active.put("cyrillicGlyph", paint.hasGlyph("Я")); active.put("emojiGlyph", paint.hasGlyph("😀"));
        report.put("activeSystemTypefaceMetrics", active);
        report.put("previewWeight", weight); report.put("underlineDepthPercent", depth);
        report.put("underlineThicknessPercent", thickness / 10.0);
        report.put("textFont", description(text)); report.put("emojiFont", description(emoji));
        report.put("repairedFont", description(repaired));
        JSONArray candidates = new JSONArray();
        for (File file : candidates()) candidates.put(file.getAbsolutePath());
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

    private static Object description(File file) throws Exception {
        if (!file.isFile()) return JSONObject.NULL;
        JSONObject result = new JSONObject(new SfntFont(read(file)).describe());
        Paint paint = new Paint(); paint.setTextSize(100);
        paint.setTypeface(new Typeface.CustomFallbackBuilder(new FontFamily.Builder(new Font.Builder(file).build()).build()).build());
        result.put("nativeUnderlinePositionAt100Px", paint.getUnderlinePosition());
        result.put("nativeUnderlineThicknessAt100Px", paint.getUnderlineThickness());
        return result;
    }
}
