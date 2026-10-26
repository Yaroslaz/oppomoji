package io.github.yaroslaz.oppomoji;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.UnderlineSpan;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int IMPORT_TEXT = 10, IMPORT_EMOJI = 11, EXPORT_FONT = 12, EXPORT_REPORT = 13, EXPORT_TEXT = 14;
    private static final int BG = Color.rgb(16, 17, 20), SURFACE = Color.rgb(29, 31, 36);
    private static final int PRIMARY = Color.rgb(142, 184, 255), WHITE = Color.rgb(243, 244, 248), MUTED = Color.rgb(180, 184, 195);
    // Serialize file operations across Activity recreation as well as within one screen.
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private final List<Button> actions = new ArrayList<>();
    private File textFile, emojiFile, repairedFile;
    private SharedPreferences prefs;
    private TextView textStatus, emojiStatus, status, weightLabel, depthLabel, thicknessLabel, before, after;
    private SeekBar weightSlider, depthSlider, thicknessSlider;
    private Button exportFont, exportText;
    private SfntFont.Axis weightAxis;
    private String textName, emojiName;
    private int weight, depth, thickness;
    private boolean busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("fonts", MODE_PRIVATE);
        textFile = new File(getFilesDir(), "text.ttf");
        emojiFile = new File(getFilesDir(), "emoji.ttf");
        repairedFile = new File(getFilesDir(), "emoji-repaired.ttf");
        textName = prefs.getString("textName", "Системный шрифт");
        emojiName = prefs.getString("emojiName", "Системные эмодзи");
        weight = prefs.getInt("weight", 500); depth = prefs.getInt("depth", 10); thickness = prefs.getInt("thickness", 40);
        buildUi();
        task("Проверяю сохранённые шрифты…", () -> {
            if (textFile.isFile()) weightAxis = new SfntFont(DeviceFonts.read(textFile)).weightAxis();
            return makePreview();
        });
    }

    private void buildUi() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        LinearLayout page = column(); page.setPadding(dp(22), dp(20), dp(22), dp(28));
        scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets system = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(system.left, system.top, system.right, system.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(scroll);
        TextView eyebrow = label("ЛАБОРАТОРИЯ ШРИФТОВ · 0.1 ALPHA", 11, PRIMARY); page.addView(eyebrow);
        TextView title = label("OPPOMoji", 34, WHITE); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); heading(title); page.addView(title);
        page.addView(label("OPPO Sans + любимые эмодзи", 16, MUTED));

        LinearLayout notice = card(page);
        notice.addView(label("Сначала проверяем совместимость", 17, WHITE));
        notice.addView(label("Эта сборка проверяет и подготавливает шрифты. Предпросмотр и его вес действуют внутри OPPOMoji. Установка во всей системе зависит от ColorOS; её выполняет менеджер шрифтов, например zFont.", 14, MUTED));

        LinearLayout sources = card(page);
        section(sources, "01", "Выбрать шрифты");
        textStatus = label(textName, 15, WHITE); sources.addView(textStatus);
        sources.addView(label("Найдём оригинальный OPPO Sans на телефоне или откроем твой TTF/OTF.", 13, MUTED));
        sources.addView(button("Найти OPPO Sans на телефоне", v -> findTextFont()));
        sources.addView(button("Открыть файл шрифта текста", v -> importFont(IMPORT_TEXT)));
        emojiStatus = label(emojiName, 15, WHITE); space(emojiStatus, 18); sources.addView(emojiStatus);
        sources.addView(label("Открой распакованный emoji-шрифт для Android. Изображения iOS в приложение не включены.", 13, MUTED));
        sources.addView(button("Открыть файл эмодзи", v -> importFont(IMPORT_EMOJI)));

        LinearLayout preview = card(page);
        section(preview, "02", "Сравнить отображение");
        weightLabel = label("Вес предпросмотра: " + weight, 15, WHITE); preview.addView(weightLabel);
        weightSlider = slider(preview, "Вес шрифта в предпросмотре", 900, weight - 100, value -> {
            weight = value + 100; weightLabel.setText("Вес предпросмотра: " + weight);
        });
        weightSlider.setId(R.id.weight_slider);
        preview.addView(label("Плавный вес доступен после выбора шрифта с осью wght. Штатный ползунок ColorOS приложение не меняет.", 13, MUTED));
        preview.addView(label("Исходный emoji-шрифт", 12, PRIMARY));
        before = sample(); before.setId(R.id.preview_before); preview.addView(before);
        TextView repairedLabel = label("Копия с выбранными метриками линии", 12, PRIMARY); space(repairedLabel, 18); preview.addView(repairedLabel);
        after = sample(); after.setId(R.id.preview_after); preview.addView(after);

        LinearLayout metrics = card(page);
        section(metrics, "03", "Настроить подчёркивание");
        metrics.addView(label("Меняются только метрики линии в копии emoji-шрифта. Это эксперимент: наложение может зависеть и от ColorOS. Межстрочные интервалы и рисунки букв сохраняются.", 13, MUTED));
        depthLabel = label("Отступ от базовой линии: " + depth + "%", 15, WHITE); metrics.addView(depthLabel);
        depthSlider = slider(metrics, "Отступ подчёркивания в процентах em", 23, depth - 2, value -> {
            depth = value + 2; depthLabel.setText("Отступ от базовой линии: " + depth + "%");
        });
        thicknessLabel = label(thicknessText(), 15, WHITE); metrics.addView(thicknessLabel);
        thicknessSlider = slider(metrics, "Толщина подчёркивания в десятых процента em", 75, thickness - 5, value -> {
            thickness = value + 5; thicknessLabel.setText(thicknessText());
        });
        metrics.addView(button("Взять метрики из OPPO Sans", v -> useTextMetrics()));

        LinearLayout export = card(page);
        section(export, "04", "Проверить в ColorOS");
        exportFont = button("Сохранить исправленный emoji-шрифт", v -> createDocument(EXPORT_FONT, "OPPOMoji-emoji.ttf", "font/ttf"));
        exportFont.setId(R.id.export_font);
        export.addView(exportFont);
        export.addView(button("Открыть инструкцию для ColorOS", v -> instructions()));
        export.addView(button("Открыть настройки экрана", v -> openDisplaySettings()));
        export.addView(button("Сохранить отчёт о совместимости", v -> createDocument(EXPORT_REPORT, "OPPOMoji-report.json", "application/json")));
        exportText = button("Сохранить копию шрифта текста", v -> createDocument(EXPORT_TEXT, "OPPOMoji-text.ttf", "font/ttf")); export.addView(exportText);
        export.addView(button("Убрать импортированные шрифты", v -> resetFonts()));

        status = label("Готово к проверке", 14, PRIMARY); status.setId(R.id.status); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setTextIsSelectable(true); page.addView(status);
        TextView footer = label("Работает без сети и root. Отчёт сохраняется только по твоей команде.", 12, MUTED); space(footer, 20); page.addView(footer);
    }

    private TextView sample() {
        TextView view = label("", 19, WHITE);
        String copy = "Съешь ещё этих мягких булок.\nПодчёркнутый текст: руфдцщ\nПривет 👋 😀 🥹 ❤️‍🔥 👨‍👩‍👧‍👦 🇷🇺\nСледующая строка без наложений?";
        SpannableString styled = new SpannableString(copy);
        int start = copy.indexOf("Подчёркнутый");
        styled.setSpan(new UnderlineSpan(), start, copy.indexOf('\n', start), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(styled); view.setTextIsSelectable(true);
        view.setPadding(0, dp(12), 0, dp(4)); view.setIncludeFontPadding(true);
        return view;
    }

    private interface Progress { void set(int value); }
    private SeekBar slider(LinearLayout parent, String label, int max, int value, Progress changed) {
        SeekBar slider = new SeekBar(this); slider.setMax(max); slider.setProgress(Math.max(0, Math.min(max, value)));
        slider.setContentDescription(label); slider.setMinHeight(dp(48));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { if (fromUser) changed.set(progress); }
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) { refresh(); }
        });
        parent.addView(slider, new LinearLayout.LayoutParams(-1, -2)); return slider;
    }

    private String thicknessText() { return String.format(Locale.getDefault(), "Толщина линии: %.1f%%", thickness / 10.0); }
    private void refresh() { savePreferences(); task("Обновляю предпросмотр…", this::makePreview); }

    private void importFont(int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        startActivityForResult(intent, request);
    }

    private void createDocument(int request, String name, String mime) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(intent, request);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == IMPORT_TEXT || request == IMPORT_EMOJI) {
            String name = displayName(uri);
            task("Проверяю файл…", () -> {
                byte[] bytes = DeviceFonts.read(getContentResolver().openInputStream(uri), SfntFont.MAX_BYTES);
                SfntFont info = new SfntFont(bytes); info.axes();
                // Let Android validate the font before replacing a previous import.
                File temporary = new File(getCacheDir(), "import.ttf");
                Files.write(temporary.toPath(), bytes);
                new Font.Builder(temporary).build();
                if (request == IMPORT_TEXT) {
                    Files.move(temporary.toPath(), textFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    weightAxis = info.weightAxis(); textName = info.familyName() + " · " + name;
                    if (weightAxis != null) weight = Math.round(Math.max(weightAxis.min, Math.min(weightAxis.max, weight)));
                } else {
                    if (!info.hasTable("CBDT") && !info.hasTable("COLR") && !info.hasTable("sbix") && !info.hasTable("SVG ")) {
                        throw new IOException("В файле нет цветных emoji-таблиц. Выбери emoji-шрифт TTF/OTF");
                    }
                    Files.move(temporary.toPath(), emojiFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    emojiName = info.familyName() + " · " + info.colorFormat();
                }
                savePreferences(); return makePreview();
            });
        } else if (request == EXPORT_FONT || request == EXPORT_REPORT || request == EXPORT_TEXT) {
            task("Сохраняю файл…", () -> {
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IOException("Не удалось открыть выбранное место сохранения");
                    if (request == EXPORT_REPORT) {
                        byte[] report = DeviceFonts.report(this, textFile, emojiFile, repairedFile, weight, depth, thickness)
                                .toString(2).getBytes(StandardCharsets.UTF_8);
                        output.write(report);
                    } else {
                        File source = request == EXPORT_FONT ? repairedFile : textFile;
                        if (!source.isFile()) throw new IOException("Сначала выбери и проверь шрифт");
                        Files.copy(source.toPath(), output);
                    }
                }
                return "Файл сохранён. Системный шрифт пока не изменён.";
            });
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (RuntimeException ignored) { /* Some document providers omit names. */ }
        return "Выбранный файл";
    }

    private void savePreferences() {
        prefs.edit().putString("textName", textName).putString("emojiName", emojiName)
                .putInt("weight", weight).putInt("depth", depth).putInt("thickness", thickness).apply();
    }

    private void findTextFont() {
        task("Ищу доступные шрифты OPPO…", () -> {
            List<File> candidates = DeviceFonts.candidates();
            if (candidates.isEmpty()) return "OPPO Sans не найден в доступных каталогах. Можно открыть его TTF/OTF вручную и сохранить отчёт.";
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                String[] names = new String[candidates.size()];
                for (int i = 0; i < names.length; i++) names[i] = candidates.get(i).getName();
                new AlertDialog.Builder(this).setTitle("Выбрать шрифт с телефона")
                        .setItems(names, (dialog, index) -> loadSystemFont(candidates.get(index)))
                        .setNegativeButton("Отмена", null).show();
            });
            return "Выбери OPPO Sans. Файлы в списке доступны для чтения без root.";
        });
    }

    private void loadSystemFont(File source) {
        task("Проверяю OPPO Sans…", () -> {
            byte[] bytes = DeviceFonts.read(source);
            SfntFont info = new SfntFont(bytes); SfntFont.Axis axis = info.weightAxis();
            new Font.Builder(source).build();
            File temporary = new File(getCacheDir(), "system-import.ttf");
            Files.write(temporary.toPath(), bytes);
            Files.move(temporary.toPath(), textFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            weightAxis = axis; textName = info.familyName() + " · " + source.getName();
            if (axis != null) weight = Math.round(Math.max(axis.min, Math.min(axis.max, weight)));
            savePreferences(); return makePreview();
        });
    }

    private void useTextMetrics() {
        task("Считываю метрики шрифта текста…", () -> {
            if (!textFile.isFile()) throw new IOException("Сначала найди OPPO Sans или открой файл шрифта текста");
            SfntFont text = new SfntFont(DeviceFonts.read(textFile));
            depth = Math.max(2, Math.min(25, Math.round(-text.underlinePosition() * 100f / text.unitsPerEm())));
            thickness = Math.max(5, Math.min(80, Math.round(text.underlineThickness() * 1000f / text.unitsPerEm())));
            savePreferences(); return makePreview();
        });
    }

    /** Generated files are local experimental exports, never automatically installed. */
    private String makePreview() throws Exception {
        Typeface original, repaired;
        String message = "Предпросмотр готов. Изменения действуют внутри OPPOMoji.";
        // Remove a previous export before attempting regeneration: never export stale settings.
        Files.deleteIfExists(repairedFile.toPath());
        String repairError = null;
        if (emojiFile.isFile()) {
            SfntFont emoji = new SfntFont(DeviceFonts.read(emojiFile));
            try {
                byte[] fixed = emoji.withUnderline(-Math.round(depth * emoji.unitsPerEm() / 100f),
                        Math.max(1, Math.round(thickness * emoji.unitsPerEm() / 1000f)));
                Files.write(repairedFile.toPath(), fixed);
                new Font.Builder(repairedFile).build();
            } catch (Exception error) {
                Files.deleteIfExists(repairedFile.toPath());
                repairError = error.getMessage();
            }
        }
        original = typeface(emojiFile);
        repaired = typeface(repairedFile.isFile() ? repairedFile : emojiFile);
        Typeface originalResult = original, repairedResult = repaired;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            before.setTypeface(originalResult); after.setTypeface(repairedResult);
            textStatus.setText(textFile.isFile() ? textName : "Системный шрифт · OPPO Sans ещё не выбран");
            emojiStatus.setText(emojiFile.isFile() ? emojiName : "Системные эмодзи · файл ещё не выбран");
            if (weightAxis != null) {
                int min = Math.max(1, (int) Math.ceil(weightAxis.min));
                int max = Math.min(1000, (int) Math.floor(weightAxis.max));
                weightSlider.setMin(min); weightSlider.setMax(Math.max(min, max));
                // The slider now represents actual weights, rather than the initial placeholder range.
                weightSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                        if (fromUser) { weight = value; weightLabel.setText("Вес предпросмотра: " + weight); }
                    }
                    public void onStartTrackingTouch(SeekBar bar) { }
                    public void onStopTrackingTouch(SeekBar bar) { refresh(); }
                });
                weightSlider.setProgress(weight);
                weightLabel.setText("Вес предпросмотра: " + weight);
            } else {
                weightLabel.setText("Вес: нужен шрифт с осью wght");
            }
            depthSlider.setProgress(depth - 2); depthLabel.setText("Отступ от базовой линии: " + depth + "%");
            thicknessSlider.setProgress(thickness - 5); thicknessLabel.setText(thicknessText());
        });
        if (repairError != null) message = "Исходный шрифт открыт. Экспорт исправления недоступен: " + repairError;
        else if (emojiFile.isFile()) message = "Копия emoji-шрифта готова к сохранению. Сравни строки, затем проверь её через менеджер шрифтов ColorOS.";
        return message;
    }

    private Typeface typeface(File emoji) throws Exception {
        if (!textFile.isFile()) {
            if (!emoji.isFile()) return Typeface.DEFAULT;
            FontFamily emojiFamily = new FontFamily.Builder(new Font.Builder(emoji).build()).build();
            return new Typeface.CustomFallbackBuilder(emojiFamily).setSystemFallback("sans-serif").build();
        }
        Font.Builder text = new Font.Builder(textFile);
        if (weightAxis != null) text.setFontVariationSettings("'wght' " + weight).setWeight(Math.max(1, Math.min(1000, weight)));
        FontFamily textFamily = new FontFamily.Builder(text.build()).build();
        Typeface.CustomFallbackBuilder family = new Typeface.CustomFallbackBuilder(textFamily).setSystemFallback("sans-serif");
        if (weightAxis != null) family.setStyle(new FontStyle(Math.max(1, Math.min(1000, weight)), FontStyle.FONT_SLANT_UPRIGHT));
        if (emoji.isFile()) family.addCustomFallback(new FontFamily.Builder(new Font.Builder(emoji).build()).build());
        return family.build();
    }

    private void instructions() {
        new AlertDialog.Builder(this).setTitle("Проверка на твоём OPPO")
                .setMessage("1. Выбери оригинальный OPPO Sans и emoji-шрифт, на котором есть проблема.\n\n"
                        + "2. Нажми «Взять метрики из OPPO Sans», сравни строки и сохрани исправленный emoji-шрифт.\n\n"
                        + "3. Импортируй сохранённый TTF в используемый менеджер шрифтов, например zFont. Применяй тем способом, который уже работает на твоей ColorOS.\n\n"
                        + "4. Проверь подчёркивания и штатный ползунок веса в настройках, сообщениях и браузере. Если стало хуже, верни стандартный шрифт и выключи поддержку Dai.\n\n"
                        + "Без root нельзя гарантировать отдельную системную замену эмодзи и сохранение ползунка OPPO Sans. Приложения со своими эмодзи могут не учитывать системный шрифт.\n\n"
                        + "Для сравнения сохрани два отчёта: с выключенным Dai и с включённым. После переключения закрой и снова открой OPPOMoji, затем сохрани отчёт.\n\n"
                        + "Отчёт содержит модель, версию системы и настройки шрифтов; прочитай его перед отправкой.")
                .setPositiveButton("Закрыть", null).show();
    }

    private void openDisplaySettings() {
        try { startActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS)); }
        catch (android.content.ActivityNotFoundException error) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private void resetFonts() {
        new AlertDialog.Builder(this).setTitle("Убрать импортированные шрифты?")
                .setMessage("Удалятся локальные копии из OPPOMoji. Исходные файлы и шрифты системы сохранятся.")
                .setNegativeButton("Отмена", null).setPositiveButton("Убрать копии", (dialog, which) ->
                        task("Убираю локальные копии…", () -> {
                            Files.deleteIfExists(textFile.toPath()); Files.deleteIfExists(emojiFile.toPath()); Files.deleteIfExists(repairedFile.toPath());
                            weightAxis = null; textName = "Системный шрифт"; emojiName = "Системные эмодзи";
                            savePreferences(); return makePreview();
                        })).show();
    }

    private interface Work { String run() throws Exception; }
    private void task(String message, Work work) {
        if (busy) return;
        setBusy(true); status.setText(message);
        WORKER.execute(() -> {
            String outcome;
            try { outcome = work.run(); }
            catch (Exception error) { outcome = "Не удалось завершить: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()); }
            String result = outcome;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false); status.setText(result);
            });
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        for (Button button : actions) button.setEnabled(!value);
        weightSlider.setEnabled(!value && weightAxis != null);
        depthSlider.setEnabled(!value); thicknessSlider.setEnabled(!value);
        exportFont.setEnabled(!value && repairedFile.isFile()); exportText.setEnabled(!value && textFile.isFile());
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout;
    }
    private LinearLayout card(LinearLayout page) {
        LinearLayout box = column(); box.setPadding(dp(18), dp(18), dp(18), dp(16));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(SURFACE); bg.setCornerRadius(dp(24)); box.setBackground(bg);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(18); page.addView(box, params); return box;
    }
    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setLineSpacing(dp(3), 1); view.setPadding(0, dp(3), 0, dp(5)); return view;
    }
    private void heading(TextView view) { view.setAccessibilityHeading(true); }
    private void section(LinearLayout parent, String number, String title) {
        TextView view = label(number + "   " + title, 19, WHITE); view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading(view); parent.addView(view);
    }
    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this); button.setText(text); button.setTextSize(14); button.setAllCaps(false);
        button.setMinHeight(dp(52)); button.setTextColor(ColorStateList.valueOf(WHITE));
        button.setBackgroundTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_enabled}, new int[]{}}, new int[]{Color.rgb(45, 57, 78), Color.rgb(44, 45, 49)}));
        button.setPadding(dp(12), dp(8), dp(12), dp(8)); button.setOnClickListener(listener); actions.add(button); return button;
    }
    private void space(View view, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(height); view.setLayoutParams(params);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
