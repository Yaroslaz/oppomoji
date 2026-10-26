package io.github.yaroslaz.oppomoji;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.net.Uri;
import android.os.SystemClock;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;
import android.widget.ScrollView;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DeviceTest {
    private Context context;
    @Before public void cleanImports() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (String name : new String[]{"text.ttf", "text-repaired.ttf", "emoji.ttf", "emoji-repaired.ttf"}) {
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
                assertFalse(activity.findViewById(R.id.depth_slider).isEnabled());
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
            await(scenario, "Копия текстового шрифта готова");
            scenario.onActivity(activity -> activity.onActivityResult(11, Activity.RESULT_OK, new Intent().setData(Uri.fromFile(emoji))));
            await(scenario, "Копия текстового шрифта готова");
            File result = new File(context.getFilesDir(), "emoji-repaired.ttf");
            assertTrue(result.isFile());
            byte[] bytes = DeviceFonts.read(result);
            assertEquals(0xB1B0AFBAL, SfntFont.checksum(bytes));
            assertEquals(new SfntFont(DeviceFonts.read(emoji)).colorFormat(), new SfntFont(bytes).colorFormat());
            new Font.Builder(result).build();
            Paint nativeMetrics = new Paint(); nativeMetrics.setTextSize(100);
            nativeMetrics.setTypeface(new Typeface.CustomFallbackBuilder(
                    new FontFamily.Builder(new Font.Builder(result).build()).build()).build());
            assertEquals(10f, nativeMetrics.getUnderlinePosition(), 0.5f);
            assertEquals(4f, nativeMetrics.getUnderlineThickness(), 0.5f);
            scenario.onActivity(activity -> {
                assertTrue(((Button) activity.findViewById(R.id.export_font)).isEnabled());
                Paint paint = new Paint(); paint.setTypeface(((TextView) activity.findViewById(R.id.preview_after)).getTypeface());
                assertTrue(paint.hasGlyph("Я")); assertTrue(paint.hasGlyph("😀"));
            });
            scenario.recreate(); await(scenario, "Копия текстового шрифта готова");
            screenshot("02-imported.png");
            awaitLayout(scenario, R.id.input_after);
            scenario.onActivity(activity -> {
                android.view.View input = activity.findViewById(R.id.input_after);
                ScrollView scroll = activity.findViewById(R.id.page_scroll);
                int[] inputPosition = new int[2], scrollPosition = new int[2];
                input.getLocationInWindow(inputPosition); scroll.getLocationInWindow(scrollPosition);
                scroll.scrollTo(0, scroll.getScrollY() + inputPosition[1] - scrollPosition[1] - scroll.getHeight() / 2);
                Rect visible = new Rect(); assertTrue(input.getGlobalVisibleRect(visible)); assertTrue(visible.height() > 0);
            });
            frame(scenario);
            screenshot("03-input-underlines.png");

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

    @Test public void rejectsSerifAndIdentifiesSansByFamilyRatherThanFilename() {
        assertFalse(DeviceFonts.isOppoSans("OPPO Serif", "OPPOSerif.ttf"));
        assertFalse(DeviceFonts.isOppoSans("OPPO Serif", "OPPOSans.ttf"));
        assertTrue(DeviceFonts.isOppoSans("OPPO Sans", "renamed-font.ttc"));
        assertTrue(DeviceFonts.isOppoSans("OPlus Sans", "generic.ttf"));
        assertFalse(DeviceFonts.isOppoSans("Roboto", "Roboto.ttf"));
    }

    @Test public void slidersChangeNativeTextMetricsWithoutImportingEmoji() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            await(scenario, "Предпросмотр готов");
            scenario.onActivity(activity -> activity.onActivityResult(10, Activity.RESULT_OK, new Intent().setData(Uri.fromFile(roboto()))));
            await(scenario, "Копия текстового шрифта готова");
            AtomicReference<Float> original = new AtomicReference<>();
            scenario.onActivity(activity -> {
                Paint paint = new Paint(((TextView) activity.findViewById(R.id.preview_before)).getPaint()); paint.setTextSize(100);
                original.set(paint.getUnderlinePosition());
                setProgress(activity.findViewById(R.id.depth_slider), 18); // 20% of em
                setProgress(activity.findViewById(R.id.thickness_slider), 75); // 8% of em
            });
            awaitPosition(scenario, 20f, 8f);
            scenario.onActivity(activity -> {
                Paint paint = new Paint(((TextView) activity.findViewById(R.id.preview_before)).getPaint()); paint.setTextSize(100);
                assertEquals(original.get(), paint.getUnderlinePosition(), 0.01f);
                assertTrue(activity.findViewById(R.id.export_fixed_text).isEnabled());
            });
            SfntFont fixed = new SfntFont(DeviceFonts.read(new File(context.getFilesDir(), "text-repaired.ttf")));
            assertEquals(-Math.round(fixed.unitsPerEm() * .2f), fixed.underlinePosition());
            scenario.recreate(); await(scenario, "Копия текстового шрифта готова"); awaitPosition(scenario, 20f, 8f);
        }
    }

    @Test public void reportButtonWritesJsonWithoutLaunchingDocumentPicker() throws Exception {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        android.app.Instrumentation.ActivityMonitor picker = instrumentation.addMonitor(new android.content.IntentFilter(Intent.ACTION_CREATE_DOCUMENT), null, true);
        Uri uri = null;
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            await(scenario, "Предпросмотр готов");
            scenario.onActivity(activity -> activity.findViewById(R.id.save_report).performClick());
            await(scenario, "Отчёт сохранён: Загрузки/OPPOMoji/");
            AtomicReference<Uri> saved = new AtomicReference<>(); scenario.onActivity(activity -> saved.set(activity.lastReportUri)); uri = saved.get();
            assertNotNull(uri); assertEquals(0, picker.getHits());
            org.json.JSONObject report = new org.json.JSONObject(new String(DeviceFonts.read(context.getContentResolver().openInputStream(uri), 2 * 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("0.1.1-alpha", report.getString("appVersion"));
            screenshot("04-saved-report.png");
        } finally {
            instrumentation.removeMonitor(picker);
            if (uri != null) context.getContentResolver().delete(uri, null, null);
        }
    }

    @Test public void reportSurvivesBadFontAndNonFiniteVendorMetrics() throws Exception {
        File invalid = new File(context.getFilesDir(), "text.ttf"); Files.write(invalid.toPath(), new byte[16]);
        org.json.JSONObject report = DeviceFonts.report(context, invalid, new File(context.getFilesDir(), "missing-emoji.ttf"), new File(context.getFilesDir(), "missing-fixed.ttf"), 500, 10, 40);
        assertTrue(report.getJSONObject("textFont").has("error"));
        DeviceFonts.finite(report, "badMetric", Float.NaN); DeviceFonts.finite(report, "badMetric2", Float.POSITIVE_INFINITY);
        assertTrue(report.isNull("badMetric")); assertTrue(report.isNull("badMetric2")); assertNotNull(report.toString(2));
    }

    private static void setProgress(SeekBar slider, int value) {
        Bundle args = new Bundle(); args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value);
        assertTrue(slider.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), args));
    }
    private static void awaitPosition(ActivityScenario<MainActivity> scenario, float position, float thickness) {
        long deadline = SystemClock.elapsedRealtime() + 20000; boolean[] ready = {false};
        do {
            scenario.onActivity(activity -> {
                Paint paint = new Paint(((TextView) activity.findViewById(R.id.preview_after)).getPaint()); paint.setTextSize(100);
                ready[0] = Math.abs(paint.getUnderlinePosition() - position) < .5f && Math.abs(paint.getUnderlineThickness() - thickness) < .5f
                        && activity.findViewById(R.id.export_fixed_text).isEnabled();
            });
            if (ready[0]) return; SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Text underline did not respond to the sliders");
    }
    private static void awaitLayout(ActivityScenario<MainActivity> scenario, int id) {
        long deadline = SystemClock.elapsedRealtime() + 5000; boolean[] ready = {false};
        do {
            scenario.onActivity(activity -> ready[0] = activity.findViewById(id).isLaidOut() && activity.findViewById(id).getHeight() > 0);
            if (ready[0]) return; SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Preview did not finish layout");
    }
    private static void frame(ActivityScenario<MainActivity> scenario) throws Exception {
        CountDownLatch rendered = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            android.view.View decor = activity.getWindow().getDecorView();
            decor.getViewTreeObserver().registerFrameCommitCallback(rendered::countDown); decor.invalidate();
        });
        assertTrue("No rendered frame", rendered.await(5, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().getUiAutomation().syncInputTransactions();
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
