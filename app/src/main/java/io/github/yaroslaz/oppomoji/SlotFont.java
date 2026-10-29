package io.github.yaroslaz.oppomoji;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.text.PositionedGlyphs;
import android.graphics.text.TextRunShaper;
import android.os.Build;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Files for the ColorOS custom-font slot (Dai), which makes its file the primary system font.
 * Android draws nothing but bitmaps from a font with CBDT, so text and iOS emoji cannot share one file:
 * the slot gets either the reduced iOS emoji font (text falls back to the next system font) or a static
 * text font at the chosen weight (emoji stay the system ones).
 */
final class SlotFont {
    /** Characters that must be visible and must not come from the emoji file in the slot. */
    static final String TEXT_PROBES = "1#*.,!?Aя©";
    static final String[] EMOJI_PROBES = {"©️", "1️⃣", "❤️‍🔥", "🙂‍↕️", "😀"};

    private SlotFont() { }

    static void buildEmoji(File emoji, int depthPercent, int thicknessPermille, File target, File temporary) throws IOException {
        SfntFont font = new SfntFont(DeviceFonts.read(emoji));
        int upem = font.unitsPerEm();
        byte[] slot = EmojiSlot.build(font, -Math.round(depthPercent * upem / 100f), Math.max(1, Math.round(thicknessPermille * upem / 1000f)));
        font = null; // Free 70 MB before writing.
        write(slot, target, temporary);
    }

    static void buildText(File text, Integer weight, int depthPercent, int thicknessPermille, File target, File temporary) throws IOException {
        SfntFont font = new SfntFont(DeviceFonts.read(text));
        if (font.hasTable("fvar")) font = new SfntFont(VariableInstancer.instantiate(font, VariableInstancer.location("wght", weight == null ? 400 : weight)));
        int upem = font.unitsPerEm();
        write(font.withUnderline(-Math.round(depthPercent * upem / 100f), Math.max(1, Math.round(thicknessPermille * upem / 1000f))), target, temporary);
    }

    private static void write(byte[] bytes, File target, File temporary) throws IOException {
        Files.write(temporary.toPath(), bytes);
        new Font.Builder(temporary).build();
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /** The slot as the system will use it: this file first, then the system fallback chain. */
    static Typeface typeface(File slot) throws IOException {
        return new Typeface.CustomFallbackBuilder(new FontFamily.Builder(new Font.Builder(slot).build()).build()).setSystemFallback("sans-serif").build();
    }

    /** Problems found when drawing the probes through the slot typeface, or "" when everything is visible. */
    static String problems(File slot, boolean emojiSlot) throws IOException {
        if (Build.VERSION.SDK_INT < 31) return "";
        Paint paint = new Paint(); paint.setTypeface(typeface(slot)); paint.setTextSize(48);
        StringBuilder problems = new StringBuilder();
        for (int i = 0; i < TEXT_PROBES.length(); i = TEXT_PROBES.offsetByCodePoints(i, 1)) {
            String probe = new String(Character.toChars(TEXT_PROBES.codePointAt(i)));
            Glyph glyph = glyph(probe, paint);
            if (!glyph.ink) problems.append(" «").append(probe).append("» невидим;");
            else if (emojiSlot && slot.equals(glyph.file)) problems.append(" «").append(probe).append("» нарисован эмодзи;");
            else if (!emojiSlot && !slot.equals(glyph.file)) problems.append(" «").append(probe).append("» не из файла;");
        }
        if (emojiSlot) {
            for (String probe : EMOJI_PROBES) {
                Glyph glyph = glyph(probe, paint);
                if (!glyph.ink || !slot.equals(glyph.file)) problems.append(" ").append(probe).append(" не из iOS-набора;");
            }
        }
        return problems.toString().trim();
    }

    private static final class Glyph { File file; boolean ink; }

    /** Which file draws the first glyph, and whether the text actually paints pixels (not just has bounds). */
    private static Glyph glyph(String text, Paint paint) {
        Glyph result = new Glyph();
        if (Build.VERSION.SDK_INT < 31) return result;
        PositionedGlyphs glyphs = TextRunShaper.shapeTextRun(text, 0, text.length(), 0, text.length(), 0, 0, false, paint);
        RectF bounds = new RectF();
        for (int g = 0; g < glyphs.glyphCount(); g++) {
            Font font = glyphs.getFont(g);
            font.getGlyphBounds(glyphs.getGlyphId(g), paint, bounds);
            if (!bounds.isEmpty()) { result.file = font.getFile(); break; }
        }
        Bitmap bitmap = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888);
        new Canvas(bitmap).drawText(text, 16, 64, paint);
        int[] pixels = new int[128 * 96];
        bitmap.getPixels(pixels, 0, 128, 0, 0, 128, 96);
        bitmap.recycle();
        for (int pixel : pixels) if ((pixel >>> 24) > 32) { result.ink = true; break; }
        return result;
    }
}
