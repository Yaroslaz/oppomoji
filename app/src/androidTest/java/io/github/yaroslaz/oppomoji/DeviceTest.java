package io.github.yaroslaz.oppomoji;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.fonts.Font;
import android.net.Uri;
import android.os.SystemClock;
import android.widget.TextView;
import android.widget.Button;
import android.widget.EditText;
import android.text.style.SuggestionSpan;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DeviceTest {
    private Context context;
    @Before public void cleanImports() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (String name : new String[]{"text.ttf", "emoji.ttf", "emoji-repaired.ttf"}) {
            Files.deleteIfExists(new File(context.getFilesDir(), name).toPath());
        }
        context.getSharedPreferences("fonts", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void launchesWithHonestEmptyStateAndNoExport() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            await(scenario, "Предпросмотр готов");
            scenario.onActivity(activity -> {
                assertFalse(activity.findViewById(R.id.export_font).isEnabled());
                assertFalse(activity.findViewById(R.id.weight_slider).isEnabled());
                assertTrue(((TextView) activity.findViewById(R.id.preview_before)).getText().toString().contains("Подчёркнутый"));
                EditText input = activity.findViewById(R.id.input_before);
                assertEquals(1, input.getText().getSpans(0, input.length(), SuggestionSpan.class).length);
            });
            screenshot("01-empty.png");
        }
    }

    @Test public void importsRealAndroidFontsRepairsAndRestoresOnRecreation() throws Exception {
        File text = roboto(); File emoji = new File("/system/fonts/NotoColorEmoji.ttf");
        assertTrue("Emulator emoji font must be available", emoji.isFile());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            await(scenario, "Предпросмотр готов");
            scenario.onActivity(activity -> activity.onActivityResult(10, Activity.RESULT_OK, new Intent().setData(Uri.fromFile(text))));
            await(scenario, "Предпросмотр готов");
            scenario.onActivity(activity -> activity.onActivityResult(11, Activity.RESULT_OK, new Intent().setData(Uri.fromFile(emoji))));
            await(scenario, "Копия emoji-шрифта готова");
            File result = new File(context.getFilesDir(), "emoji-repaired.ttf");
            assertTrue(result.isFile());
            byte[] bytes = DeviceFonts.read(result);
            assertEquals(0xB1B0AFBAL, SfntFont.checksum(bytes));
            assertEquals(new SfntFont(DeviceFonts.read(emoji)).colorFormat(), new SfntFont(bytes).colorFormat());
            new Font.Builder(result).build();
            scenario.onActivity(activity -> {
                assertTrue(((Button) activity.findViewById(R.id.export_font)).isEnabled());
                Paint paint = new Paint(); paint.setTypeface(((TextView) activity.findViewById(R.id.preview_after)).getTypeface());
                assertTrue(paint.hasGlyph("Я")); assertTrue(paint.hasGlyph("😀"));
            });
            scenario.recreate(); await(scenario, "Копия emoji-шрифта готова");
            screenshot("02-imported.png");

            File invalid = new File(context.getCacheDir(), "invalid.ttf"); Files.write(invalid.toPath(), new byte[12]);
            scenario.onActivity(activity -> activity.onActivityResult(11, Activity.RESULT_OK, new Intent().setData(Uri.fromFile(invalid))));
            await(scenario, "Не удалось завершить");
            assertArrayEquals(bytes, DeviceFonts.read(result));
        }
    }

    @Test public void reportContainsActiveMetricsAndNoFontBytes() throws Exception {
        File none = new File(context.getFilesDir(), "missing.ttf");
        org.json.JSONObject report = DeviceFonts.report(context, none, none, none, 500, 10, 40);
        assertTrue(report.has("activeSystemTypefaceMetrics"));
        assertTrue(report.getJSONObject("activeSystemTypefaceMetrics").has("underlineThickness"));
        assertFalse(report.getBoolean("systemChangesAppliedByApp"));
        assertFalse(report.has("serial")); assertEquals(org.json.JSONObject.NULL, report.get("textFont"));
    }

    private static File roboto() {
        File[] files = new File("/system/fonts").listFiles();
        assertNotNull(files);
        for (File file : files) {
            if (file.getName().startsWith("Roboto") && file.getName().endsWith(".ttf")) return file;
        }
        throw new AssertionError("No emulator Roboto font");
    }
    private static void await(ActivityScenario<MainActivity> scenario, String expected) {
        AtomicReference<String> text = new AtomicReference<>("");
        long deadline = SystemClock.elapsedRealtime() + 20000;
        do {
            scenario.onActivity(activity -> text.set(((TextView) activity.findViewById(R.id.status)).getText().toString()));
            if (text.get().contains(expected)) return;
            if (text.get().startsWith("Не удалось") && !expected.startsWith("Не удалось")) fail(text.get());
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Expected " + expected + ", got: " + text.get());
    }
    private void screenshot(String name) throws Exception {
        Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(image);
        try (FileOutputStream out = new FileOutputStream(new File(context.getExternalFilesDir(null), name))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally { image.recycle(); }
    }
}
