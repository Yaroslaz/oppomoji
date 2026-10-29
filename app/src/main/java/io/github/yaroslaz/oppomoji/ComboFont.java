package io.github.yaroslaz.oppomoji;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.text.PositionedGlyphs;
import android.graphics.text.TextRunShaper;
import android.icu.lang.UCharacter;
import android.icu.lang.UProperty;
import android.os.Build;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * The file for a ColorOS custom-font slot: the text font at a fixed weight with the colour emoji inside.
 * The slot makes its font primary, so text, digits, spaces and punctuation must come from the text font.
 */
final class ComboFont {
    /** Characters that must stay text glyphs in the slot: digits, spaces and punctuation went wrong before. */
    static final String TEXT_PROBES = "1#*. ,!?Aя©";

    private ComboFont() { }

    static boolean emojiFirst(int codePoint) {
        return FontMerger.structural(codePoint) || UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI_PRESENTATION);
    }

    /** Writes the merged font to {@code target}; returns the number of text glyphs. */
    static int build(File text, File emoji, Integer weight, int depthPercent, int thicknessPermille, File target, File temporary) throws IOException {
        SfntFont sans = new SfntFont(DeviceFonts.read(text));
        if (sans.hasTable("fvar")) {
            Map<String, Float> location = VariableInstancer.location("wght", weight == null ? 400 : weight);
            sans = new SfntFont(VariableInstancer.instantiate(sans, location));
        }
        int upem = sans.unitsPerEm();
        sans = new SfntFont(sans.withUnderline(-Math.round(depthPercent * upem / 100f), Math.max(1, Math.round(thicknessPermille * upem / 1000f))));
        int textGlyphs = glyphCount(sans);
        byte[] merged = FontMerger.merge(sans, new SfntFont(DeviceFonts.read(emoji)), ComboFont::emojiFirst);
        sans = null; // Free the text copy before writing 35 MB.
        Files.write(temporary.toPath(), merged);
        merged = null;
        new Font.Builder(temporary).build();
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return textGlyphs;
    }

    static int glyphCount(SfntFont font) {
        byte[] maxp = font.copies().get("maxp");
        return maxp == null ? 0 : ByteBuffer.wrap(maxp).getShort(4) & 0xffff;
    }

    static Typeface typeface(File combo) throws IOException {
        return new Typeface.CustomFallbackBuilder(new FontFamily.Builder(new Font.Builder(combo).build()).build()).setSystemFallback("sans-serif").build();
    }

    /** Probe characters the text font has but that came out as emoji pictures (glyph IDs past the text glyphs). */
    static String textAsEmoji(File combo, int textGlyphs, SfntFont text) throws IOException {
        if (Build.VERSION.SDK_INT < 31) return "";
        Paint paint = new Paint(); paint.setTypeface(typeface(combo)); paint.setTextSize(48);
        StringBuilder wrong = new StringBuilder();
        for (int i = 0; i < TEXT_PROBES.length(); i = TEXT_PROBES.offsetByCodePoints(i, 1)) {
            int codePoint = TEXT_PROBES.codePointAt(i);
            if (!text.hasCodePoint(codePoint)) continue; // Then the emoji picture is the only glyph there is.
            String probe = new String(Character.toChars(codePoint));
            PositionedGlyphs glyphs = TextRunShaper.shapeTextRun(probe, 0, probe.length(), 0, probe.length(), 0, 0, false, paint);
            for (int g = 0; g < glyphs.glyphCount(); g++) {
                if (combo.equals(glyphs.getFont(g).getFile()) && glyphs.getGlyphId(g) >= textGlyphs) { wrong.append(probe); break; }
            }
        }
        return wrong.toString();
    }
}
