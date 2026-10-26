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
        assertTrue(DeviceFonts.isOppoSans("OPPO Sans", "OppoSerifCollection.ttc"));
        assertTrue(DeviceFonts.isOppoSans("OPlus Sans", "generic.ttf"));
        assertFalse(DeviceFonts.isOppoSans("Roboto", "Roboto.ttf"));
        assertFalse(DeviceFonts.isOppoSans("OPlus Sans Solid Digits VF", "OSans-Solid-Digits-VF.ttf"));
        assertFalse(DeviceFonts.isOppoSans("OPlus Sans Solid Digits VF", "OPPOSans.ttf"));
        DeviceFonts.Candidate en = new DeviceFonts.Candidate(new File("SysSans-En-Regular.ttf"), 0, "OPPO Sans 4.0 EN", true);
        DeviceFonts.Candidate sc = new DeviceFonts.Candidate(new File("SysFont-Hans-Regular.ttf"), 0, "OPPO Sans 4.0 SC", true);
        assertTrue(DeviceFonts.priority(en) < DeviceFonts.priority(sc));
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
            assertEquals(BuildConfig.VERSION_NAME, report.getString("appVersion"));
            assertTrue(report.getJSONObject("lineLayouts").getJSONObject("activeSystemSample").getJSONArray("baselineDistancesPx").length() > 0);
            CountDownLatch reportFrame = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                android.view.View decor = activity.reportDialog.getWindow().getDecorView();
                decor.getViewTreeObserver().registerFrameCommitCallback(reportFrame::countDown); decor.invalidate();
            });
            assertTrue(reportFrame.await(5, TimeUnit.SECONDS));
            scenario.onActivity(activity -> {
                Button copy = activity.reportDialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL);
                Rect bounds = new Rect(); assertTrue("Copy JSON must be visible", copy.getGlobalVisibleRect(bounds));
                assertEquals(copy.getHeight(), bounds.height());
            });
            screenshot("04-saved-report.png");
            scenario.onActivity(activity -> activity.reportDialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick());
            await(scenario, "Отчёт скопирован");
            AtomicReference<String> copied = new AtomicReference<>();
            long clipboardDeadline = SystemClock.elapsedRealtime() + 5000;
            do {
                scenario.onActivity(activity -> {
                    if (!activity.hasWindowFocus()) return;
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    android.content.ClipData clip = clipboard.getPrimaryClip();
                    if (clip != null) { copied.set(clip.getItemAt(0).getText().toString()); clipboard.clearPrimaryClip(); }
                });
                if (copied.get() != null) break;
                SystemClock.sleep(50);
            } while (SystemClock.elapsedRealtime() < clipboardDeadline);
            assertNotNull("Copied report must be readable after the dialog closes", copied.get());
            org.json.JSONObject summary = new org.json.JSONObject(copied.get());
            assertEquals(BuildConfig.VERSION_NAME, summary.getString("appVersion"));
            assertFalse(summary.has("fontConfigurations")); assertTrue(summary.has("lineLayouts"));
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

    @Test public void resolvesAndExportsActualSystemEmojiWithoutDocumentPicker() throws Exception {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        android.app.Instrumentation.ActivityMonitor picker = instrumentation.addMonitor(new android.content.IntentFilter(Intent.ACTION_CREATE_DOCUMENT), null, true);
        Uri[] exported = {null};
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            await(scenario, "Предпросмотр готов");
            scenario.onActivity(activity -> activity.findViewById(R.id.load_active_emoji).performClick());
            await(scenario, "Копия emoji-шрифта готова");
            File imported = new File(context.getFilesDir(), "emoji.ttf");
            byte[] bytes = DeviceFonts.read(imported); SfntFont font = new SfntFont(bytes);
            assertTrue(font.hasCodePoint(0x1f600)); assertTrue(font.hasTable("CBDT") || font.hasTable("COLR"));
            assertEquals(0xB1B0AFBAL, SfntFont.checksum(bytes));
            Font active = SystemFontAccess.resolve("😀");
            Font bufferOnly = new Font.Builder(active.getBuffer().duplicate()).setTtcIndex(active.getTtcIndex()).build();
            assertNull(bufferOnly.getFile());
            File mapped = new File(context.getCacheDir(), "test-mapped.ttf");
            assertArrayEquals(bytes, SystemFontAccess.copy(bufferOnly, mapped));
            assertFalse(mapped.exists());
            org.json.JSONObject resolved = SystemFontAccess.report();
            assertFalse(resolved.getJSONObject("cyrillic").has("error"));
            assertEquals(font.familyName(), resolved.getJSONObject("emoji").getJSONObject("metadata").getString("family"));
            scenario.onActivity(activity -> activity.findViewById(R.id.export_original_emoji).performClick());
            await(scenario, "Шрифт сохранён: Загрузки/OPPOMoji/");
            scenario.onActivity(activity -> exported[0] = activity.lastFontUri);
            assertNotNull(exported[0]); assertEquals(0, picker.getHits());
            assertArrayEquals(bytes, DeviceFonts.read(context.getContentResolver().openInputStream(exported[0]), SfntFont.MAX_BYTES));
        } finally {
            instrumentation.removeMonitor(picker);
            if (exported[0] != null) context.getContentResolver().delete(exported[0], null, null);
        }
    }

    @Test public void lineDiagnosticsDistinguishPrimaryMetricsFromLocaleMinimum() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            await(scenario, "Предпросмотр готов");
            scenario.onActivity(activity -> {
                TextView control = new TextView(activity), probe = new TextView(activity);
                int width = android.view.View.MeasureSpec.makeMeasureSpec(900, android.view.View.MeasureSpec.EXACTLY);
                int height = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED);
                Paint.FontMetrics huge = new Paint.FontMetrics(); huge.ascent = -200; huge.descent = 100; huge.top = -200; huge.bottom = 100;
                probe.setMinimumFontMetrics(huge);
                // These setters do not invalidate an existing TextView layout. Measure fresh views.
                for (TextView view : new TextView[]{control, probe}) {
                    view.setLocalePreferredLineHeightForMinimumUsed(false);
                    view.setText("Первая\nВторая\nТретья");
                    view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 100);
                    view.measure(width, height); view.layout(0, 0, 900, view.getMeasuredHeight());
                }
                int originalSpacing = control.getLayout().getLineBaseline(1) - control.getLayout().getLineBaseline(0);
                try {
                    org.json.JSONObject snapshot = LineDiagnostics.snapshot(probe);
                    int measured = snapshot.getJSONArray("baselineDistancesPx").getInt(0);
                    assertTrue("Minimum metrics must expand spacing: " + originalSpacing + " -> " + measured, measured > originalSpacing + 100);
                    assertEquals(100, snapshot.getInt("textSizePx"));
                    assertTrue(snapshot.has("localeMetricsAt100px"));
                } catch (org.json.JSONException error) { throw new AssertionError(error); }
            });
        }
    }

    @Test public void customTextUsesActiveEmojiWithoutImportAndExplicitImportTakesPrecedence() throws Exception {
        File systemEmoji = new File("/system/fonts/NotoColorEmoji.ttf");
        SfntFont original = new SfntFont(DeviceFonts.read(systemEmoji));
        File simulated = new File(context.getCacheDir(), "active-replacement-emoji.ttf");
        Files.write(simulated.toPath(), original.withUnderline(-original.unitsPerEm() / 2, 90));
        Font replacement = new Font.Builder(simulated).build();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            await(scenario, "Предпросмотр готов");
            scenario.onActivity(activity -> activity.activeSystemEmoji = replacement);
            scenario.onActivity(activity -> activity.onActivityResult(10, Activity.RESULT_OK, new Intent().setData(Uri.fromFile(roboto()))));
            await(scenario, "Копия текстового шрифта готова");
            scenario.onActivity(activity -> {
                for (int id : new int[]{R.id.preview_before, R.id.preview_after, R.id.input_before, R.id.input_after}) {
                    try {
                        TextView view = activity.findViewById(id);
                        assertEquals(simulated, SystemFontAccess.resolve("😀", view.getPaint()).getFile());
                        File text = SystemFontAccess.resolve("Я", view.getPaint()).getFile();
                        assertTrue(text.getName().equals("text.ttf") || text.getName().equals("text-repaired.ttf"));
                        org.json.JSONObject snapshot = LineDiagnostics.snapshot(view);
                        assertEquals(simulated.getAbsolutePath(), snapshot.getJSONObject("resolvedFonts").getJSONObject("emoji").getString("file"));
                    } catch (Exception error) { throw new AssertionError(error); }
                }
                assertFalse(activity.findViewById(R.id.export_font).isEnabled());
            });
            assertFalse(new File(context.getFilesDir(), "emoji.ttf").exists());
            scenario.onActivity(activity -> activity.onActivityResult(11, Activity.RESULT_OK, new Intent().setData(Uri.fromFile(systemEmoji))));
            await(scenario, "Копия текстового шрифта готова");
            scenario.onActivity(activity -> {
                try {
                    TextView before = activity.findViewById(R.id.preview_before), after = activity.findViewById(R.id.preview_after);
                    assertEquals(new File(context.getFilesDir(), "emoji.ttf"), SystemFontAccess.resolve("😀", before.getPaint()).getFile());
                    assertEquals(new File(context.getFilesDir(), "emoji-repaired.ttf"), SystemFontAccess.resolve("😀", after.getPaint()).getFile());
                } catch (Exception error) { throw new AssertionError(error); }
            });
        }
    }

    @Test public void oldDigitsOnlySelectionIsRemovedBeforeNativePreview() throws Exception {
        // A metadata-only fixture must be rejected before Android tries to render it as a body font.
        java.util.Map<String, byte[]> tables = new java.util.LinkedHashMap<>();
        byte[] head = new byte[54]; java.nio.ByteBuffer.wrap(head).putInt(12, 0x5F0F3CF5).putShort(18, (short) 1000);
        tables.put("head", head);
        byte[] family = "OPlus Sans Solid Digits VF".getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
        java.nio.ByteBuffer name = java.nio.ByteBuffer.allocate(18 + family.length);
        name.putShort((short) 0).putShort((short) 1).putShort((short) 18);
        name.putShort((short) 3).putShort((short) 1).putShort((short) 0x409).putShort((short) 16).putShort((short) family.length).putShort((short) 0).put(family);
        tables.put("name", name.array());
        File selected = new File(context.getFilesDir(), "text.ttf"); Files.write(selected.toPath(), SfntFont.pack(0x00010000, tables));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            await(scenario, "Предпросмотр готов");
            assertFalse(selected.exists()); // The AOSP emulator has no replacement OPPO Sans.
            scenario.onActivity(activity -> assertFalse(activity.findViewById(R.id.weight_slider).isEnabled()));
        }
    }

    @Test public void fullEmoji17SetUsesOneColorGlyphFromSelectedFontForEverySequence() throws Exception {
        File prepared = new File(context.getExternalFilesDir(null), "verified-emoji.ttf");
        assertTrue("Prepare the pinned font with tools/prepare_emoji.py and push it into the app's external files", prepared.isFile());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            await(scenario, "Предпросмотр готов");
            scenario.onActivity(activity -> activity.onActivityResult(10, Activity.RESULT_OK, new Intent().setData(Uri.fromFile(roboto()))));
            await(scenario, "Копия текстового шрифта готова");
            scenario.onActivity(activity -> activity.onActivityResult(11, Activity.RESULT_OK, new Intent().setData(Uri.fromFile(prepared))));
            await(scenario, "Копия текстового шрифта готова");
            AtomicReference<Typeface> before = new AtomicReference<>(), after = new AtomicReference<>();
            scenario.onActivity(activity -> {
                before.set(((TextView) activity.findViewById(R.id.preview_before)).getTypeface());
                after.set(((TextView) activity.findViewById(R.id.preview_after)).getTypeface());
            });
            java.util.List<String> failures = new java.util.ArrayList<>();
            int total = 0, qualified = 0, components = 0;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    context.getAssets().open("emoji-17.0.txt"), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] row = line.split(";", 3); StringBuilder text = new StringBuilder();
                    for (String cp : row[0].split(" ")) text.appendCodePoint(Integer.parseInt(cp, 16));
                    total++; if (row[1].equals("fully-qualified")) qualified++; else components++;
                    for (boolean fixed : new boolean[]{false, true}) {
                        Paint paint = new Paint(); paint.setTextSize(48); paint.setTypeface(fixed ? after.get() : before.get());
                        android.graphics.text.PositionedGlyphs glyphs = android.graphics.text.TextRunShaper.shapeTextRun(
                                text, 0, text.length(), 0, text.length(), 0, 0, false, paint);
                        File expected = new File(context.getFilesDir(), fixed ? "emoji-repaired.ttf" : "emoji.ttf");
                        if (glyphs.glyphCount() != 1 || glyphs.getGlyphId(0) == 0 || !expected.equals(glyphs.getFont(0).getFile())) {
                            failures.add(row[0] + " " + row[2] + " (fixed=" + fixed + ", glyphs=" + glyphs.glyphCount() + ")");
                        }
                    }
                }
            }
            org.json.JSONObject result = new org.json.JSONObject().put("fullyQualified", qualified).put("components", components)
                    .put("total", total).put("typefacesChecked", 2).put("failures", new org.json.JSONArray(failures));
            Files.write(new File(context.getExternalFilesDir(null), "emoji-set-verification.json").toPath(), result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(3944, qualified); assertEquals(9, components); assertEquals(3953, total);
            assertTrue("Selected emoji font failed: " + failures.subList(0, Math.min(20, failures.size())), failures.isEmpty());
            awaitLayout(scenario, R.id.preview_before);
            scenario.onActivity(activity -> {
                android.view.View sample = activity.findViewById(R.id.preview_before); ScrollView scroll = activity.findViewById(R.id.page_scroll);
                int[] samplePosition = new int[2], scrollPosition = new int[2];
                sample.getLocationInWindow(samplePosition); scroll.getLocationInWindow(scrollPosition);
                scroll.scrollTo(0, scroll.getScrollY() + samplePosition[1] - scrollPosition[1]);
            });
            frame(scenario); screenshot("05-full-emoji-set.png");
        }
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
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
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
