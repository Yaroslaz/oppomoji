package io.github.yaroslaz.oppomoji;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.text.PositionedGlyphs;
import android.graphics.text.TextRunShaper;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** How many Unicode Emoji 17.0 sequences one emoji font draws as a single visible glyph. Android 12+. */
final class EmojiCoverage {
    static final int SAMPLE = 8;
    final int supported, total;
    /** The first missing fully-qualified sequences, for display. */
    final List<String> missing;

    EmojiCoverage(int supported, int total, List<String> missing) {
        this.supported = supported; this.total = total; this.missing = missing;
    }

    boolean complete() { return supported == total; }

    static EmojiCoverage measure(Context context, Font font) throws IOException {
        // The emoji font leads, so text-default sequences such as "©️" are judged on the font itself.
        Typeface typeface = new Typeface.CustomFallbackBuilder(new FontFamily.Builder(font).build()).build();
        Paint paint = new Paint(); paint.setTypeface(typeface); paint.setTextSize(48);
        File file = font.getFile();
        RectF bounds = new RectF();
        int supported = 0, total = 0;
        List<String> missing = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open("emoji-17.0.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] row = line.split(";", 3);
                StringBuilder text = new StringBuilder();
                for (String code : row[0].split(" ")) text.appendCodePoint(Integer.parseInt(code, 16));
                total++;
                PositionedGlyphs glyphs = TextRunShaper.shapeTextRun(text, 0, text.length(), 0, text.length(), 0, 0, false, paint);
                int visible = 0; boolean fromFont = true;
                for (int i = 0; i < glyphs.glyphCount(); i++) {
                    Font used = glyphs.getFont(i);
                    used.getGlyphBounds(glyphs.getGlyphId(i), paint, bounds);
                    if (bounds.isEmpty()) continue;
                    visible++;
                    fromFont &= glyphs.getGlyphId(i) != 0 && (file == null || file.equals(used.getFile()));
                }
                if (visible == 1 && fromFont) supported++;
                else if (missing.size() < SAMPLE && row[1].equals("fully-qualified")) missing.add(text.toString());
            }
        }
        return new EmojiCoverage(supported, total, missing);
    }

    String encode() { return supported + "|" + total + "|" + String.join(" ", missing); }

    static EmojiCoverage decode(String value) {
        if (value == null) return null;
        String[] parts = value.split("\\|", 3);
        if (parts.length != 3) return null;
        try {
            List<String> missing = new ArrayList<>();
            for (String item : parts[2].split(" ")) if (!item.isEmpty()) missing.add(item);
            return new EmojiCoverage(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), missing);
        } catch (NumberFormatException error) { return null; }
    }
}
