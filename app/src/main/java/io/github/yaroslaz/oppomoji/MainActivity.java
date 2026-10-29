package io.github.yaroslaz.oppomoji;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.UnderlineSpan;
import android.text.style.SuggestionSpan;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int IMPORT_TEXT = 10, IMPORT_EMOJI = 11, EXPORT_FONT = 12, EXPORT_TEXT = 14, EXPORT_FIXED_TEXT = 15;
    private static final int EXPORT_ORIGINAL_EMOJI = 16, EXPORT_EMOJI_ONLY = 17;
    private static final int BG = Color.rgb(16, 17, 20), SURFACE = Color.rgb(29, 31, 36), RAISED = Color.rgb(38, 41, 48);
    private static final int PRIMARY = Color.rgb(142, 184, 255), WHITE = Color.rgb(243, 244, 248), MUTED = Color.rgb(180, 184, 195);
    private static final int DONE = Color.rgb(128, 214, 160);
    /** Bump when the repaired copies change for reasons other than the slider values. */
    private static final String REPAIR_FORMAT = "2";
    /** Bump when the merged font changes for reasons other than its inputs. */
    private static final String COMBO_FORMAT = "1";
    private static final int WARN = Color.rgb(255, 200, 120);
    private static final int SHOWCASE_COLUMNS = 8;
    // Includes ZWJ sequences and Emoji 15.1–17.0, so an older set shows its gaps here.
    private static final String[] SHOWCASE = {"😀", "😂", "🥹", "😍", "🥰", "😎", "🤔", "😭",
            "👍", "👏", "🙏", "🫶", "🤌", "👀", "🙈", "💯", "❤️", "🔥", "✨", "🎉", "✅", "❤️‍🔥", "🫠", "🥲",
            "🐱", "🍕", "🚀", "🇷🇺", "👨‍👩‍👧‍👦", "🙂‍↕️", "🫪", "🫯"};
    // Serialize file operations across Activity recreation as well as within one screen.
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private final List<Button> actions = new ArrayList<>();
    private final List<TextView> interfaceText = new ArrayList<>();
    private final List<TextView> boldInterfaceText = new ArrayList<>();
    private final TextView[] checks = new TextView[4];
    private File textFile, emojiFile, repairedFile, repairedTextFile, comboFile;
    private SharedPreferences prefs;
    private TextView textStatus, emojiStatus, status, weightLabel, depthLabel, thicknessLabel, before, after, coverageLabel;
    private final List<TextView> showcase = new ArrayList<>();
    private EmojiCoverage coverage;
    private ProgressBar progress;
    private SeekBar weightSlider, depthSlider, thicknessSlider;
    private Button exportFont, exportText, exportFixedText;
    private EditText inputBefore, inputAfter;
    private TextView systemSample;
    private Typeface interfaceTypeface = Typeface.DEFAULT;
    Font activeSystemEmoji;
    private String activeSystemEmojiName, activeSystemEmojiError;
    private SfntFont.Axis weightAxis;
    private String textName, emojiName, metricsSource;
    private int weight, depth, thickness;
    private boolean busy;
    private boolean textSupportsCyrillic = true;
    private boolean textClaimsEmoji;
    private boolean verifiedEmoji;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refreshPreview = this::refresh;
    Uri lastReportUri;
    Uri lastFontUri;
    AlertDialog reportDialog;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("fonts", MODE_PRIVATE);
        textFile = new File(getFilesDir(), "text.ttf");
        emojiFile = new File(getFilesDir(), "emoji.ttf");
        repairedFile = new File(getFilesDir(), "emoji-repaired.ttf");
        repairedTextFile = new File(getFilesDir(), "text-repaired.ttf");
        comboFile = new File(getFilesDir(), "combo.ttf");
        textName = prefs.getString("textName", "Системный шрифт");
        emojiName = prefs.getString("emojiName", "Системные эмодзи");
        metricsSource = prefs.getString("metricsSource", "default");
        verifiedEmoji = prefs.getBoolean("verifiedEmoji", false) && emojiFile.isFile();
        weight = prefs.getInt("weight", 500); depth = prefs.getInt("depth", 10); thickness = prefs.getInt("thickness", 40);
        buildUi();
        task("Проверяю сохранённые шрифты…", () -> {
            if (Build.VERSION.SDK_INT >= 31) {
                try { activeSystemEmoji = SystemFontAccess.resolve("😀"); activeSystemEmojiName = SystemFontAccess.familyName(activeSystemEmoji); }
                catch (IOException | RuntimeException error) { activeSystemEmojiError = error.getMessage(); }
            }
            boolean replaceDigits = false;
            if (textFile.isFile()) {
                try (FontFile source = new FontFile(textFile)) {
                    SfntFont info = source.metadata(0); replaceDigits = DeviceFonts.isDigitsFont(info.familyName());
                    if (!replaceDigits) { weightAxis = info.weightAxis(); textSupportsCyrillic = supportsCyrillic(info); textClaimsEmoji = info.claimsEmojiPresentation(); }
                }
            }
            if (replaceDigits) {
                DeviceFonts.Candidate replacement = bestSans();
                if (replacement != null) importSystemText(replacement);
                else {
                    deleteText();
                    textName = "Нужен OPPO Sans: служебный шрифт цифр исключён"; savePreferences();
                }
            }
            return makePreview();
        });
    }

    private void buildUi() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(SURFACE);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        LinearLayout root = column(); root.setBackgroundColor(BG);
        ScrollView scroll = new ScrollView(this);
        scroll.setId(R.id.page_scroll);
        scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        LinearLayout page = column(); page.setPadding(dp(20), dp(16), dp(20), dp(28));
        page.setFocusableInTouchMode(true); page.requestFocus();
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(statusBar(), new LinearLayout.LayoutParams(-1, -2));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets system = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(system.left, system.top, system.right, system.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(root);
        TextView eyebrow = label("ЛАБОРАТОРИЯ ШРИФТОВ · " + BuildConfig.VERSION_NAME, 11, PRIMARY); page.addView(eyebrow);
        TextView title = label("OPPOMoji", 34, WHITE); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); boldInterfaceText.add(title); heading(title); page.addView(title);
        page.addView(label("iOS-эмодзи Emoji 17.0 для ColorOS через zFont", 16, MUTED));

        LinearLayout hero = card(page);
        section(hero, "", "OPPO Sans + iOS-эмодзи");
        hero.addView(showcaseGrid(), new LinearLayout.LayoutParams(-1, -2));
        coverageLabel = label("Проверяю полноту набора…", 13, MUTED); coverageLabel.setId(R.id.emoji_coverage);
        space(coverageLabel, 6); hero.addView(coverageLabel);
        String[] steps = {"OPPO Sans", "iOS-набор", "Один файл", "Сохранён для zFont"};
        for (int i = 0; i < checks.length; i++) {
            checks[i] = label(steps[i], 14, MUTED);
            if (i == 0) space(checks[i], 10);
            hero.addView(checks[i]);
        }
        weightLabel = label(weightText(), 15, WHITE); space(weightLabel, 12); hero.addView(weightLabel);
        weightSlider = slider(hero, "Жирность шрифта для zFont", 900, weight - 100, value -> {
            weight = value + 100; weightLabel.setText(weightText());
        });
        weightSlider.setId(R.id.weight_slider);
        hero.addView(label("Жирность вшивается в файл: штатный ползунок ColorOS такой шрифт не меняет. Для другой жирности сдвинь ползунок и подготовь файл заново.", 12, MUTED));
        Button prepare = primaryButton("Подготовить шрифт для zFont", v -> prepareIos());
        prepare.setId(R.id.prepare_ios); hero.addView(prepare, spaced(10));
        hero.addView(label("Один файл: OPPO Sans выбранной жирности с iOS-эмодзи Emoji 17.0 внутри. Буквы, цифры, пробелы и точки остаются от OPPO Sans, эмодзи — iOS. Набор (35 МБ) загружается с проверкой SHA-256. Систему меняет только zFont.", 12, MUTED));
        hero.addView(button("Как установить через zFont", v -> instructions()));

        LinearLayout sources = card(page);
        section(sources, "01", "Шрифты");
        textStatus = label(textName, 15, WHITE); sources.addView(textStatus);
        sources.addView(button("Найти OPPO Sans на телефоне", v -> findTextFont()));
        sources.addView(button("Открыть файл шрифта текста", v -> importFont(IMPORT_TEXT)));
        sources.addView(button("Использовать активный системный шрифт", v -> task("Возвращаю системный шрифт в примеры…", () -> {
            deleteText(); textName = "Активный системный шрифт"; savePreferences(); return makePreview();
        })));
        emojiStatus = label(emojiName, 15, WHITE); space(emojiStatus, 18); sources.addView(emojiStatus);
        Button fullEmoji = button("Загрузить полный iOS-набор · Emoji 17.0", v -> downloadEmoji());
        fullEmoji.setId(R.id.download_emoji); sources.addView(fullEmoji);
        sources.addView(label("Закреплённая версия с GitHub, только по нажатию. В Android 16 проверены 3 944 эмодзи и 9 компонентов, включая ☺️ ❤️ 😶‍🌫️ 🙂‍↕️ и ©️ ‼️ #️⃣.", 12, MUTED));
        sources.addView(button("Открыть файл эмодзи", v -> importFont(IMPORT_EMOJI)));
        Button activeEmoji = button("Взять активный emoji-шрифт из системы", v -> loadActiveEmoji());
        activeEmoji.setId(R.id.load_active_emoji); sources.addView(activeEmoji);
        sources.addView(label("Без импорта примеры используют эмодзи, которыми Android сейчас рисует 😀 (Android 12+). При включённой замене это может быть пакет из zFont.", 12, MUTED));

        LinearLayout preview = card(page);
        section(preview, "02", "Сравнить отображение");
        preview.addView(label("Исходный текстовый шрифт и эмодзи", 12, PRIMARY));
        before = sample(); before.setId(R.id.preview_before); preview.addView(before);
        TextView repairedLabel = label("Файл для zFont (после подготовки) или копия с выбранной линией", 12, PRIMARY); space(repairedLabel, 18); preview.addView(repairedLabel);
        after = sample(); after.setId(R.id.preview_after); preview.addView(after);
        preview.addView(label("После подготовки нижний пример рисуется самим файлом для zFont: ©️ ‼️ 1️⃣ — эмодзи, а © 1 — буквы OPPO Sans. До подготовки ©️ ™️ #️⃣ здесь могут выглядеть текстом: Android не даёт приложению пометить отдельный шрифт как эмодзи.", 12, MUTED));
        preview.addView(label("Поле ввода: исходный шрифт", 12, PRIMARY));
        inputBefore = inputSample(); inputBefore.setId(R.id.input_before); preview.addView(inputBefore);
        preview.addView(label("Поле ввода: исправленная копия", 12, PRIMARY));
        inputAfter = inputSample(); inputAfter.setId(R.id.input_after); preview.addView(inputAfter);
        preview.addView(label("Белая линия — обычное подчёркивание, цветная — тестовая пометка орфографии. Толщину цветной линии задаёт Android; её положение берётся из шрифта. В отчёт содержимое полей не попадает.", 12, MUTED));
        preview.addView(label("Активный системный шрифт: межстрочный интервал", 12, PRIMARY));
        systemSample = label("Первая строка: Яя Щщ\nВторая строка: OPPO Sans\nТретья строка: 😀 👋", 15, WHITE);
        systemSample.setId(R.id.active_system_sample); interfaceText.remove(systemSample);
        systemSample.setTypeface(Typeface.DEFAULT); preview.addView(systemSample);
        preview.addView(label("Этот образец следует системным настройкам Dai. Остальные подписи используют выбранный текстовый шрифт.", 12, MUTED));

        LinearLayout metrics = card(page);
        section(metrics, "03", "Линия подчёркивания");
        metrics.addView(label("Линия вшивается в файл для zFont. По умолчанию её стоит взять из OPPO Sans: тогда подчёркивания в системе не съезжают.", 12, MUTED));
        metrics.addView(button("Взять метрики из OPPO Sans", v -> useTextMetrics()));
        depthLabel = label("Отступ от базовой линии: " + depth + "%", 15, WHITE); metrics.addView(depthLabel);
        depthSlider = slider(metrics, "Отступ подчёркивания в процентах em", 23, depth - 2, value -> {
            depth = value + 2; metricsSource = "custom"; depthLabel.setText("Отступ от базовой линии: " + depth + "%");
        });
        depthSlider.setId(R.id.depth_slider);
        thicknessLabel = label(thicknessText(), 15, WHITE); metrics.addView(thicknessLabel);
        thicknessSlider = slider(metrics, "Толщина подчёркивания в десятых процента em", 75, thickness - 5, value -> {
            thickness = value + 5; metricsSource = "custom"; thicknessLabel.setText(thicknessText());
        });
        thicknessSlider.setId(R.id.thickness_slider);

        LinearLayout export = card(page);
        section(export, "04", "Сохранить и проверить");
        exportFont = primaryButton("Сохранить OPPO Sans + iOS-эмодзи", v -> createDocument(EXPORT_FONT, comboName(), "font/ttf"));
        exportFont.setId(R.id.export_font);
        export.addView(exportFont, spaced(4));
        Button reportButton = button("Сохранить отчёт о совместимости", v -> saveReport());
        reportButton.setId(R.id.save_report); export.addView(reportButton);
        export.addView(label("Файлы и JSON сохраняются в Загрузки/OPPOMoji без окна выбора папки. Отчёт никуда не отправляется.", 12, MUTED));
        Button more = button("Дополнительно ▾", null); more.setId(R.id.advanced_toggle); export.addView(more);
        LinearLayout advanced = column(); advanced.setVisibility(View.GONE); export.addView(advanced);
        more.setOnClickListener(v -> {
            boolean show = advanced.getVisibility() != View.VISIBLE;
            advanced.setVisibility(show ? View.VISIBLE : View.GONE); more.setText(show ? "Дополнительно ▴" : "Дополнительно ▾");
        });
        advanced.addView(button("Только emoji-шрифт (не для Dai: цифры станут эмодзи)", v -> createDocument(EXPORT_EMOJI_ONLY, "OPPOMoji-emoji.ttf", "font/ttf")));
        Button originalEmoji = button("Сохранить исходный emoji-шрифт", v -> createDocument(EXPORT_ORIGINAL_EMOJI, "OPPOMoji-emoji-original.ttf", "font/ttf"));
        originalEmoji.setId(R.id.export_original_emoji); advanced.addView(originalEmoji);
        exportFixedText = button("Сохранить текстовый шрифт с исправлением линии", v -> createDocument(EXPORT_FIXED_TEXT, "OPPOMoji-text-fixed.ttf", "font/ttf"));
        exportFixedText.setId(R.id.export_fixed_text); advanced.addView(exportFixedText);
        exportText = button("Сохранить копию шрифта текста", v -> createDocument(EXPORT_TEXT, "OPPOMoji-text.ttf", "font/ttf")); advanced.addView(exportText);
        advanced.addView(button("Открыть настройки экрана", v -> openDisplaySettings()));
        advanced.addView(button("Убрать импортированные шрифты", v -> resetFonts()));

        TextView footer = label("Обработка шрифтов работает без root и без отправки данных. Сеть нужна только для загрузки набора по кнопке.", 12, MUTED); space(footer, 20); page.addView(footer);
    }

    /** Equal cells keep every row aligned; each emoji shrinks to its cell instead of wrapping. */
    private LinearLayout showcaseGrid() {
        LinearLayout grid = column(); grid.setPadding(dp(6), dp(10), dp(6), dp(10));
        grid.setContentDescription("Образец эмодзи с выбранными шрифтами");
        GradientDrawable stage = new GradientDrawable(); stage.setColor(RAISED); stage.setCornerRadius(dp(18)); grid.setBackground(stage);
        LinearLayout row = null;
        for (int i = 0; i < SHOWCASE.length; i++) {
            if (i % SHOWCASE_COLUMNS == 0) { row = new LinearLayout(this); grid.addView(row, new LinearLayout.LayoutParams(-1, dp(46))); }
            TextView cell = new TextView(this); cell.setText(SHOWCASE[i]); cell.setTextColor(WHITE);
            cell.setGravity(Gravity.CENTER); cell.setMaxLines(1); cell.setIncludeFontPadding(false);
            cell.setAutoSizeTextTypeUniformWithConfiguration(12, 30, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            if (i == 0) cell.setId(R.id.emoji_showcase);
            showcase.add(cell); row.addView(cell, new LinearLayout.LayoutParams(0, -1, 1));
        }
        return grid;
    }

    private LinearLayout statusBar() {
        LinearLayout bar = column(); bar.setBackgroundColor(SURFACE); bar.setPadding(dp(20), dp(6), dp(20), dp(10));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setId(R.id.progress); progress.setIndeterminate(true); progress.setVisibility(View.INVISIBLE);
        progress.setProgressTintList(ColorStateList.valueOf(PRIMARY)); progress.setIndeterminateTintList(ColorStateList.valueOf(PRIMARY));
        bar.addView(progress, new LinearLayout.LayoutParams(-1, dp(6)));
        status = label("Готово к проверке", 14, PRIMARY); status.setId(R.id.status); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setTextIsSelectable(true); status.setMaxLines(3); bar.addView(status);
        return bar;
    }

    private TextView sample() {
        TextView view = label("", 19, WHITE);
        interfaceText.remove(view);
        String copy = "Съешь ещё этих мягких булок.\nПодчёркнутый текст: руфдцщ 😀\nПривет 👋 ☺️ ❤️ 😶‍🌫️ 🙂‍↕️ 🙂‍↔️\nСимволы ©️ ™️ ⁉️ #️⃣ 1️⃣\nНовое: 🫪 🫍 🧑‍🩰 🫯\nСемья 👨‍👩‍👧‍👦 · флаг 🇷🇺\nСледующая строка без наложений?";
        SpannableString styled = new SpannableString(copy);
        int start = copy.indexOf("Подчёркнутый");
        styled.setSpan(new UnderlineSpan(), start, copy.indexOf('\n', start), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(styled); view.setTextIsSelectable(true);
        view.setPadding(0, dp(12), 0, dp(4)); view.setIncludeFontPadding(true);
        return view;
    }

    private interface Progress { void set(int value); }
    private EditText inputSample() {
        EditText input = new EditText(this);
        input.setTextColor(WHITE); input.setTextSize(23); input.setMinHeight(dp(120));
        // Use a deterministic native SuggestionSpan; the dictionary must not replace the demo.
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setContentDescription("Проверка подчёркивания и орфографии в поле ввода");
        SpannableString text = new SpannableString("Шорнт\nИрлоил\nСледующая строка 👋");
        text.setSpan(new UnderlineSpan(), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        input.setText(text); restoreDemoSuggestion(input); return input;
    }

    private void restoreDemoSuggestion(EditText input) {
        if (input.getText().toString().equals("Шорнт\nИрлоил\nСледующая строка 👋")
                && input.getText().getSpans(0, input.length(), SuggestionSpan.class).length == 0) {
            // setText() and saved-state restoration remove these spans; add it to the live Editable.
            input.getText().setSpan(new SuggestionSpan(this, new String[]{"Ирландия"},
                    SuggestionSpan.FLAG_EASY_CORRECT | SuggestionSpan.FLAG_MISSPELLED), 6, 12, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private SeekBar slider(LinearLayout parent, String label, int max, int value, Progress changed) {
        SeekBar slider = new SeekBar(this); slider.setMax(max); slider.setProgress(Math.max(0, Math.min(max, value)));
        slider.setContentDescription(label); slider.setMinHeight(dp(48));
        slider.setProgressTintList(ColorStateList.valueOf(PRIMARY)); slider.setThumbTintList(ColorStateList.valueOf(PRIMARY));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) { changed.set(progress); main.removeCallbacks(refreshPreview); main.postDelayed(refreshPreview, 180); }
            }
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) { main.removeCallbacks(refreshPreview); refresh(); }
        });
        parent.addView(slider, new LinearLayout.LayoutParams(-1, -2)); return slider;
    }

    private String thicknessText() { return String.format(Locale.getDefault(), "Толщина линии: %.1f%%", thickness / 10.0); }
    private void refresh() {
        if (isFinishing() || isDestroyed()) return;
        if (busy) { main.removeCallbacks(refreshPreview); main.postDelayed(refreshPreview, 120); return; }
        savePreferences(); task("Обновляю предпросмотр…", this::makePreview);
    }

    private void importFont(int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try { startActivityForResult(intent, request); }
        catch (ActivityNotFoundException | SecurityException error) { status.setText("ColorOS не открыла выбор файла. Можно найти системный OPPO Sans кнопкой выше. Отчёт сохраняется без этого окна."); }
    }

    private String comboName() { return "OPPOMoji-iOS-" + (weightAxis == null ? "Sans" : String.valueOf(weight)) + ".ttf"; }

    private String weightText() {
        return weightAxis == null ? "Жирность: нужен OPPO Sans с осью веса" : "Жирность шрифта: " + weight;
    }

    private String comboKey() {
        return COMBO_FORMAT + ":" + textFile.length() + ":" + textFile.lastModified() + ":" + emojiFile.length() + ":" + emojiFile.lastModified()
                + ":" + (weightAxis == null ? 0 : weight) + ":" + depth + ":" + thickness;
    }

    private boolean comboCurrent() { return comboFile.isFile() && textFile.isFile() && emojiFile.isFile() && comboKey().equals(prefs.getString("comboKey", null)); }

    /** Runs on the worker. Builds the merged font when its inputs changed; returns the text glyph count. */
    private int ensureCombo() throws IOException {
        if (!textFile.isFile()) throw new IOException("Сначала найди OPPO Sans: без него в слоте Dai цифры и пробелы станут эмодзи");
        if (!emojiFile.isFile()) throw new IOException("Сначала загрузи iOS-набор");
        if (comboCurrent()) return prefs.getInt("comboGlyphs", 0);
        Files.deleteIfExists(comboFile.toPath());
        step("Собираю OPPO Sans " + (weightAxis == null ? "" : weight + " ") + "+ iOS-эмодзи…");
        int glyphs = ComboFont.build(textFile, emojiFile, weightAxis == null ? null : weight, depth, thickness, comboFile, new File(getCacheDir(), "combo.tmp"));
        prefs.edit().putString("comboKey", comboKey()).putInt("comboGlyphs", glyphs).commit();
        return glyphs;
    }

    /** Runs on the worker: every Emoji 17.0 sequence from the file, and text characters still text glyphs. */
    private String verifyCombo(int glyphs) throws IOException {
        if (Build.VERSION.SDK_INT < 31) return "";
        step("Проверяю файл: 3953 эмодзи, цифры и пробелы…");
        EmojiCoverage result = EmojiCoverage.measure(this, new Font.Builder(comboFile).build());
        SfntFont text;
        try (FontFile source = new FontFile(textFile)) { text = source.metadata(0); }
        String wrong = ComboFont.textAsEmoji(comboFile, glyphs, text);
        if (!wrong.isEmpty()) throw new IOException("В файле символы «" + wrong + "» стали эмодзи. Сообщи об этом и пришли отчёт");
        return " Проверено: " + result.supported + " из " + result.total + " эмодзи, цифры и пробелы из OPPO Sans.";
    }

    private void createDocument(int request, String name, String mime) {
        task("Сохраняю шрифт в Загрузки…", () -> "Шрифт сохранён: Загрузки/OPPOMoji/" + save(request, name, mime));
    }

    /** Runs on the worker. Returns the name MediaStore actually used. */
    private String save(int request, String name, String mime) throws IOException {
        if (request == EXPORT_FONT) ensureCombo();
        File source = exportSource(request);
        lastFontUri = ReportStorage.save(this, name, mime, output -> Files.copy(source.toPath(), output));
        if (request == EXPORT_FONT) {
            prefs.edit().putString("exported", comboKey()).commit();
            runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) updateChecklist(); });
        }
        return ReportStorage.displayName(this, lastFontUri, name);
    }

    private File exportSource(int request) throws IOException {
        File source = request == EXPORT_FONT ? comboFile : request == EXPORT_EMOJI_ONLY ? repairedFile : request == EXPORT_FIXED_TEXT ? repairedTextFile
                : request == EXPORT_ORIGINAL_EMOJI ? emojiFile : textFile;
        if (!source.isFile()) throw new IOException("Сначала выбери и проверь шрифт");
        return source;
    }

    private void saveReport() {
        JSONObject layouts = new JSONObject();
        try {
            layouts.put("activeSystemSample", LineDiagnostics.snapshot(systemSample));
            layouts.put("selectedTextBefore", LineDiagnostics.snapshot(before));
            layouts.put("selectedTextAfter", LineDiagnostics.snapshot(after));
        } catch (org.json.JSONException error) { status.setText("Не удалось измерить строки: " + error.getMessage()); return; }
        task("Сохраняю отчёт…", () -> {
            JSONObject report = DeviceFonts.report(this, textFile, emojiFile, repairedFile, weight, depth, thickness);
            report.put("lineLayouts", layouts);
            report.put("previewEmojiSource", emojiFile.isFile() ? "imported-file" : activeSystemEmoji != null ? "active-system-font" : "system-fallback");
            report.put("underlineMetricsSource", metricsSource);
            report.put("comboFont", new JSONObject().put("current", comboCurrent()).put("weight", weightAxis == null ? JSONObject.NULL : weight)
                    .put("exported", comboCurrent() && comboKey().equals(prefs.getString("exported", null)))
                    .put("textGlyphs", prefs.getInt("comboGlyphs", 0)).put("bytes", comboFile.isFile() ? comboFile.length() : 0));
            if (coverage != null) report.put("previewEmojiCoverage", new JSONObject().put("unicodeVersion", "17.0")
                    .put("supported", coverage.supported).put("total", coverage.total).put("missingSample", String.join(" ", coverage.missing)));
            if (verifiedEmoji) report.put("verifiedEmojiSet", new JSONObject().put("unicodeVersion", "17.0")
                    .put("sourceUrl", EmojiSource.URL).put("sourceSha256BeforeRepair", EmojiSource.SHA256)
                    .put("fullyQualifiedTested", 3944).put("componentsTested", 9));
            String json = report.toString(2), brief = DeviceFonts.clipboardSummary(report);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            String name = "OPPOMoji-report-" + System.currentTimeMillis() + ".json";
            String outcome;
            lastReportUri = null;
            // A private copy and an in-app viewer remain available if the vendor storage service fails.
            Files.write(new File(getFilesDir(), "last-report.json").toPath(), bytes);
            try {
                lastReportUri = ReportStorage.save(this, name, "application/json", output -> output.write(bytes));
                outcome = "Отчёт сохранён: Загрузки/OPPOMoji/" + name;
            } catch (IOException | RuntimeException error) {
                outcome = "Не удалось сохранить в Загрузки. Отчёт открыт в приложении — его можно скопировать.";
            }
            String result = outcome;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                String display = brief.length() <= 12000 ? brief : brief.substring(0, 12000) + "\n…\nПолный отчёт сохранён в JSON-файле.";
                TextView content = label(display, 12, WHITE); content.setTextIsSelectable(true); content.setPadding(dp(16), dp(8), dp(16), dp(8));
                interfaceText.remove(content);
                if (Build.VERSION.SDK_INT >= 35) content.setLocalePreferredLineHeightForMinimumUsed(false);
                ScrollView scroll = new ScrollView(this); scroll.addView(content);
                reportDialog = new AlertDialog.Builder(this).setTitle("Отчёт о совместимости").setMessage(result).setView(scroll)
                        .setPositiveButton("Закрыть", null).setNeutralButton("Копировать JSON", (dialog, which) -> {
                            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("OPPOMoji", brief));
                            status.setText("Отчёт скопирован");
                        }).create();
                reportDialog.setOnShowListener(dialog -> {
                    android.view.ViewGroup.LayoutParams params = scroll.getLayoutParams();
                    params.height = Math.min(dp(280), getResources().getDisplayMetrics().heightPixels / 3);
                    scroll.setLayoutParams(params);
                });
                reportDialog.show();
            });
            return outcome;
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request != IMPORT_TEXT && request != IMPORT_EMOJI) return;
        Uri uri = data.getData();
        String name = displayName(uri);
        task("Проверяю файл…", () -> {
            byte[] bytes = DeviceFonts.read(getContentResolver().openInputStream(uri), SfntFont.MAX_BYTES);
            // Let Android validate the font before replacing a previous import.
            File temporary = new File(getCacheDir(), "import.ttf");
            Files.write(temporary.toPath(), bytes);
            if (bytes.length >= 4 && java.nio.ByteBuffer.wrap(bytes).getInt() == 0x74746366) {
                try (FontFile collection = new FontFile(temporary)) {
                    int chosen = 0;
                    for (int i = 0; i < collection.faceCount(); i++) {
                        SfntFont metadata = collection.metadata(i);
                        if (request == IMPORT_TEXT && DeviceFonts.isOppoSans(metadata.familyName(), "")) { chosen = i; break; }
                        if (request == IMPORT_EMOJI && (metadata.hasTable("CBDT") || metadata.hasTable("COLR"))) { chosen = i; break; }
                    }
                    bytes = collection.extract(chosen);
                }
                Files.write(temporary.toPath(), bytes);
            }
            SfntFont info = new SfntFont(bytes); info.axes();
            if (request == IMPORT_TEXT && DeviceFonts.isDigitsFont(info.familyName())) {
                throw new IOException("Это служебный шрифт цифр. Выбери OPPO Sans 4.0 EN для букв и кириллицы");
            }
            new Font.Builder(temporary).build();
            if (request == IMPORT_TEXT) {
                Files.move(temporary.toPath(), textFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                textChanged(info, info.familyName() + " · " + name);
            } else {
                if (!info.hasTable("CBDT") && !info.hasTable("COLR") && !info.hasTable("sbix") && !info.hasTable("SVG ")) {
                    throw new IOException("В файле нет цветных emoji-таблиц. Выбери emoji-шрифт TTF/OTF");
                }
                Files.move(temporary.toPath(), emojiFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                emojiChanged(info.familyName() + " · " + info.colorFormat(), false);
            }
            savePreferences(); return makePreview();
        });
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (RuntimeException ignored) { /* Some document providers omit names. */ }
        return "Выбранный файл";
    }

    private void loadActiveEmoji() {
        task("Читаю активный emoji-шрифт…", () -> {
            Font active = SystemFontAccess.resolve("😀");
            byte[] bytes = SystemFontAccess.copy(active, new File(getCacheDir(), "mapped-emoji.ttf"));
            SfntFont info = new SfntFont(bytes);
            if (!info.hasTable("CBDT") && !info.hasTable("COLR") && !info.hasTable("sbix") && !info.hasTable("SVG ")) {
                throw new IOException("Активный глиф не содержит цветных emoji-таблиц. Открой файл эмодзи из zFont");
            }
            File temporary = new File(getCacheDir(), "active-emoji.ttf"); Files.write(temporary.toPath(), bytes);
            new Font.Builder(temporary).build();
            Files.move(temporary.toPath(), emojiFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            emojiChanged(info.familyName() + " · активные системные эмодзи", false);
            savePreferences(); return makePreview();
        });
    }

    private void downloadEmoji() {
        task("Загружаю проверенный iOS-набор · 35 МБ…", () -> { downloadVerified(); savePreferences(); return makePreview(); });
    }

    /** Runs on the worker. The previous import stays until the new file passes every check. */
    private void downloadVerified() throws Exception {
        File temporary = new File(getCacheDir(), "download-emoji.ttf");
        runOnUiThread(() -> { progress.setIndeterminate(false); progress.setMax(100); progress.setProgress(0); });
        try {
            EmojiSource.download(temporary, bytes -> runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                int percent = (int) (bytes * 100 / EmojiSource.BYTES);
                progress.setProgress(percent); status.setText("Загружаю iOS-набор: " + percent + "% из 35 МБ");
            }));
        } finally { runOnUiThread(() -> progress.setIndeterminate(true)); }
        try (FontFile source = new FontFile(temporary)) {
            SfntFont info = source.metadata(0);
            if (!info.hasTable("CBDT") || !info.hasTable("GSUB")) throw new IOException("Набор не содержит нужных цветных таблиц и составных эмодзи");
        }
        new Font.Builder(temporary).build();
        Files.move(temporary.toPath(), emojiFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        emojiChanged("iOS-набор · Emoji 17.0 · 3 944 эмодзи + 9 компонентов", true);
    }

    /** One tap: Sans → verified iOS set → Sans underline → TTF in Downloads. Skips finished steps. */
    /** One tap: Sans → verified iOS set → one merged file at the chosen weight → Downloads. */
    private void prepareIos() {
        task("Готовлю шрифт для zFont…", () -> {
            if (!textFile.isFile() || !textSupportsCyrillic) {
                step("Ищу OPPO Sans…");
                DeviceFonts.Candidate sans = bestSans();
                if (sans != null) importSystemText(sans);
                else if (!textFile.isFile()) throw new IOException("OPPO Sans не найден. Без него в слоте Dai цифры и пробелы станут эмодзи: открой файл Sans в разделе 01");
            }
            if (!verifiedEmoji) { step("Загружаю iOS-набор · 35 МБ…"); downloadVerified(); }
            if (!"custom".equals(metricsSource)) applyTextMetrics();
            savePreferences();
            int glyphs = ensureCombo();
            String checked = verifyCombo(glyphs);
            String saved = save(EXPORT_FONT, comboName(), "font/ttf");
            makePreview();
            return "Готово: Загрузки/OPPOMoji/" + saved + "." + checked + " Примени файл в zFont тем же способом, что и раньше.";
        });
    }

    private void step(String message) {
        runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) status.setText(message); });
    }

    private void savePreferences() {
        prefs.edit().putString("textName", textName).putString("emojiName", emojiName)
                .putBoolean("verifiedEmoji", verifiedEmoji).putString("metricsSource", metricsSource)
                .putInt("weight", weight).putInt("depth", depth).putInt("thickness", thickness).apply();
    }

    private void findTextFont() {
        task("Ищу доступные шрифты OPPO…", () -> {
            List<DeviceFonts.Candidate> candidates = DeviceFonts.candidates();
            if (candidates.isEmpty()) return "Доступный файл OPPO Sans не найден. OPPO Serif не подходит. Сохрани отчёт — он поможет найти системное семейство Sans.";
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                String[] names = new String[candidates.size()];
                for (int i = 0; i < names.length; i++) names[i] = candidates.get(i).label();
                new AlertDialog.Builder(this).setTitle("Доступные семейства Sans")
                        .setItems(names, (dialog, index) -> loadSystemFont(candidates.get(index)))
                        .setNegativeButton("Отмена", null).show();
            });
            return "Выбери OPPO Sans. Файлы в списке доступны для чтения без root.";
        });
    }

    private static DeviceFonts.Candidate bestSans() {
        for (DeviceFonts.Candidate font : DeviceFonts.candidates()) if (font.cyrillic) return font;
        return null;
    }

    private void loadSystemFont(DeviceFonts.Candidate source) {
        task("Проверяю OPPO Sans…", () -> {
            importSystemText(source); return makePreview();
        });
    }

    private void importSystemText(DeviceFonts.Candidate source) throws Exception {
        byte[] bytes;
        try (FontFile font = new FontFile(source.file)) { bytes = font.extract(source.index); }
        SfntFont info = new SfntFont(bytes);
        File temporary = new File(getCacheDir(), "system-import.ttf"); Files.write(temporary.toPath(), bytes);
        new Font.Builder(temporary).build();
        Files.move(temporary.toPath(), textFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        textChanged(info, info.familyName() + " · " + source.file.getName());
        savePreferences();
    }

    private void textChanged(SfntFont info, String name) throws IOException {
        weightAxis = info.weightAxis(); textName = name;
        textSupportsCyrillic = supportsCyrillic(info); textClaimsEmoji = info.claimsEmojiPresentation();
        if (weightAxis != null) weight = Math.round(Math.max(weightAxis.min, Math.min(weightAxis.max, weight)));
        forgetCopies();
    }

    private void emojiChanged(String name, boolean verified) {
        emojiName = name; verifiedEmoji = verified; forgetCopies();
    }

    private void deleteText() throws IOException {
        Files.deleteIfExists(textFile.toPath()); Files.deleteIfExists(repairedTextFile.toPath()); Files.deleteIfExists(comboFile.toPath());
        weightAxis = null; textClaimsEmoji = false; textSupportsCyrillic = true; forgetCopies();
    }

    /** Sources changed: rebuild every derived copy on the next preview. */
    private void forgetCopies() {
        prefs.edit().remove(repairKeyName(repairedFile)).remove(repairKeyName(repairedTextFile)).remove("comboKey").apply();
    }

    private void useTextMetrics() {
        task("Считываю метрики шрифта текста…", () -> {
            applyTextMetrics(); savePreferences(); return makePreview();
        });
    }

    private void applyTextMetrics() throws IOException {
        if (!textFile.isFile()) throw new IOException("Сначала найди OPPO Sans или открой файл шрифта текста");
        SfntFont text;
        try (FontFile source = new FontFile(textFile)) { text = source.metadata(0); }
        depth = Math.max(2, Math.min(25, Math.round(-text.underlinePosition() * 100f / text.unitsPerEm())));
        thickness = Math.max(5, Math.min(80, Math.round(text.underlineThickness() * 1000f / text.unitsPerEm())));
        metricsSource = "text";
    }

    /** Generated files are local experimental exports, never automatically installed. */
    private String makePreview() throws Exception {
        String message = "Предпросмотр готов.";
        String textError = repair(textFile, repairedTextFile);
        String emojiError = repair(emojiFile, repairedFile);
        Typeface originalResult = typeface(textFile, emojiFile);
        Typeface repairedResult = typeface(repairedTextFile.isFile() ? repairedTextFile : textFile,
                repairedFile.isFile() ? repairedFile : emojiFile);
        Typeface interfaceFont = textFile.isFile() && textSupportsCyrillic ? typeface(textFile, emojiFile, 400) : null;
        // Once prepared, the lower sample is drawn by the very file that goes to zFont.
        Typeface lower = comboCurrent() ? ComboFont.typeface(comboFile) : repairedResult;
        EmojiCoverage measured = measureCoverage();
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            before.setTypeface(originalResult); after.setTypeface(lower);
            for (TextView cell : showcase) cell.setTypeface(lower);
            coverage = measured; showCoverage();
            inputBefore.setTypeface(originalResult); inputAfter.setTypeface(lower);
            restoreDemoSuggestion(inputBefore); restoreDemoSuggestion(inputAfter);
            interfaceTypeface = interfaceFont == null ? Typeface.DEFAULT : interfaceFont;
            for (TextView view : interfaceText) {
                view.setTypeface(boldInterfaceText.contains(view) ? Typeface.create(interfaceTypeface, Typeface.BOLD) : interfaceTypeface);
                // Keep fallback spacing for actual tall glyphs, but avoid an unrelated locale minimum.
                if (Build.VERSION.SDK_INT >= 35) view.setLocalePreferredLineHeightForMinimumUsed(false);
            }
            textStatus.setText(textFile.isFile() ? textName + (textSupportsCyrillic ? "" : " · нет кириллицы: русские буквы из системного шрифта") : "Активный системный шрифт · OPPO Sans пока не подтверждён");
            emojiStatus.setText(emojiFile.isFile() ? emojiName : activeSystemEmoji != null
                    ? activeSystemEmojiName + " · из системы, подключены к примерам"
                    : "Активные эмодзи недоступны для выбранного Sans: " + (activeSystemEmojiError == null ? "открой TTF/OTF или используй системный шрифт текста" : activeSystemEmojiError));
            if (weightAxis != null) {
                int min = Math.max(1, (int) Math.ceil(weightAxis.min));
                int max = Math.min(1000, (int) Math.floor(weightAxis.max));
                weightSlider.setMin(min); weightSlider.setMax(Math.max(min, max));
                // The slider now represents actual weights, rather than the initial placeholder range.
                weightSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                        if (fromUser) {
                            weight = value; weightLabel.setText(weightText());
                            main.removeCallbacks(refreshPreview); main.postDelayed(refreshPreview, 180);
                        }
                    }
                    public void onStartTrackingTouch(SeekBar bar) { }
                    public void onStopTrackingTouch(SeekBar bar) { main.removeCallbacks(refreshPreview); refresh(); }
                });
                weightSlider.setProgress(weight);
                weightLabel.setText(weightText());
            } else {
                weightLabel.setText(weightText());
            }
            depthSlider.setProgress(depth - 2); depthLabel.setText("Отступ от базовой линии: " + depth + "%");
            thicknessSlider.setProgress(thickness - 5); thicknessLabel.setText(thicknessText());
            updateChecklist();
        });
        if (textError != null) message = "Исправление текста недоступно: " + textError;
        else if (repairedTextFile.isFile()) message = "Копия текстового шрифта готова: ползунки меняют исправленный пример.";
        else if (repairedFile.isFile()) message = "Копия emoji-шрифта готова. Для линии под буквами выбери текстовый шрифт.";
        if (textClaimsEmoji) message += " Внимание: текстовый шрифт оставит ©️ ‼️ текстом.";
        if (emojiError != null) message += " Исправление эмодзи недоступно: " + emojiError;
        return message;
    }

    /** Counted once per emoji file: the slider copies have the same glyphs. */
    private EmojiCoverage measureCoverage() {
        if (Build.VERSION.SDK_INT < 31) return null;
        Font font; String key;
        try {
            if (emojiFile.isFile()) { font = new Font.Builder(emojiFile).build(); key = "file:" + emojiFile.length() + ":" + emojiFile.lastModified(); }
            else if (activeSystemEmoji != null) {
                font = activeSystemEmoji; File file = font.getFile();
                key = "system:" + (file == null ? "buffer" : file.getAbsolutePath() + ":" + file.length());
            } else return null;
            EmojiCoverage cached = key.equals(prefs.getString("coverageKey", null)) ? EmojiCoverage.decode(prefs.getString("coverage", null)) : null;
            if (cached != null) return cached;
            EmojiCoverage result = EmojiCoverage.measure(this, font);
            prefs.edit().putString("coverageKey", key).putString("coverage", result.encode()).apply();
            return result;
        } catch (IOException | RuntimeException error) { return null; }
    }

    private void showCoverage() {
        String source = emojiFile.isFile() ? shortName(emojiName) : activeSystemEmoji != null ? "системные эмодзи · " + activeSystemEmojiName : "системные эмодзи";
        if (coverage == null) {
            coverageLabel.setText("Набор: " + source + (Build.VERSION.SDK_INT < 31 ? ". Полнота проверяется с Android 12." : "."));
            coverageLabel.setTextColor(MUTED); return;
        }
        String count = coverage.supported + " из " + coverage.total + " эмодзи Unicode 17.0";
        if (coverage.complete()) {
            coverageLabel.setText("✓  " + source + " · " + count);
            coverageLabel.setTextColor(DONE);
        } else {
            coverageLabel.setText("!  " + source + " · " + count + ". Нет: " + String.join(" ", coverage.missing)
                    + (coverage.total - coverage.supported > coverage.missing.size() ? " …" : "")
                    + (verifiedEmoji ? "" : "\nСтарый набор: нажми «Подготовить iOS-эмодзи», чтобы загрузить полный."));
            coverageLabel.setTextColor(WARN);
        }
    }

    private void updateChecklist() {
        boolean sans = textFile.isFile() && textSupportsCyrillic;
        boolean combo = comboCurrent();
        boolean exported = combo && comboKey().equals(prefs.getString("exported", null));
        String weightName = weightAxis == null ? "" : " " + weight;
        check(0, sans, sans ? shortName(textName) : "OPPO Sans не выбран");
        check(1, verifiedEmoji, verifiedEmoji ? "iOS-набор Emoji 17.0 · 3 944 эмодзи"
                : emojiFile.isFile() ? "Загружен другой набор · нужен полный iOS 35 МБ" : "iOS-набор не загружен · 35 МБ");
        check(2, combo, combo ? "OPPO Sans" + weightName + " + iOS-эмодзи в одном файле" : "Файл для жирности" + weightName + " ещё не собран");
        check(3, exported, exported ? "Сохранён " + comboName() : "Файл для zFont не сохранён");
    }

    private void check(int index, boolean done, String text) {
        checks[index].setText((done ? "✓  " : "○  ") + text);
        checks[index].setTextColor(done ? DONE : MUTED);
        checks[index].setContentDescription((done ? "Готово: " : "Не готово: ") + text);
    }

    private static String shortName(String name) {
        int separator = name.indexOf(" · ");
        return separator > 0 ? name.substring(0, separator) : name;
    }

    private static String repairKeyName(File target) { return "repair:" + target.getName(); }

    /** Rewriting a 35 MB emoji font is slow; reuse the copy while its source and metrics stay the same. */
    private String repair(File source, File target) throws IOException {
        if (!source.isFile()) { Files.deleteIfExists(target.toPath()); return null; }
        String key = REPAIR_FORMAT + ":" + source.length() + ":" + source.lastModified() + ":" + depth + ":" + thickness;
        if (target.isFile() && key.equals(prefs.getString(repairKeyName(target), null))) return null;
        Files.deleteIfExists(target.toPath());
        try {
            SfntFont font = new SfntFont(DeviceFonts.read(source));
            byte[] fixed = font.withUnderline(-Math.round(depth * font.unitsPerEm() / 100f),
                    Math.max(1, Math.round(thickness * font.unitsPerEm() / 1000f)));
            Files.write(target.toPath(), fixed); new Font.Builder(target).build();
            prefs.edit().putString(repairKeyName(target), key).commit();
            return null;
        } catch (Exception error) {
            Files.deleteIfExists(target.toPath()); prefs.edit().remove(repairKeyName(target)).commit(); return error.getMessage();
        }
    }

    private Typeface typeface(File textFile, File emoji) throws Exception {
        return typeface(textFile, emoji, weight);
    }

    private Typeface typeface(File textFile, File emoji, int requestedWeight) throws Exception {
        // An emoji-only family must never become the primary family for letters or switch OEM sans to Roboto.
        Font.Builder text = null;
        if (textFile.isFile()) {
            text = new Font.Builder(textFile);
            if (weightAxis != null) text.setFontVariationSettings("'wght' " + requestedWeight).setWeight(Math.max(1, Math.min(1000, requestedWeight)));
        }
        FontFamily textFamily;
        if (text != null) textFamily = new FontFamily.Builder(text.build()).build();
        else if (emoji.isFile() && Build.VERSION.SDK_INT >= 31) {
            // Without a chosen Sans, keep the system letters but still show the imported emoji.
            try { textFamily = new FontFamily.Builder(SystemFontAccess.resolve("A")).build(); }
            catch (IOException | RuntimeException error) { return Typeface.DEFAULT; }
        } else return Typeface.DEFAULT;
        Typeface.CustomFallbackBuilder family = new Typeface.CustomFallbackBuilder(textFamily).setSystemFallback("sans-serif");
        if (weightAxis != null && text != null) family.setStyle(new FontStyle(Math.max(1, Math.min(1000, requestedWeight)), FontStyle.FONT_SLANT_UPRIGHT));
        Font emojiFont = emoji.isFile() ? new Font.Builder(emoji).build() : activeSystemEmoji;
        if (emojiFont != null) family.addCustomFallback(new FontFamily.Builder(emojiFont).build());
        return family.build();
    }

    private void instructions() {
        new AlertDialog.Builder(this).setTitle("Установка через zFont")
                .setMessage("1. Выбери жирность ползунком и нажми «Подготовить шрифт для zFont». Приложение найдёт OPPO Sans, загрузит проверенный набор Emoji 17.0 и соберёт один файл: OPPO Sans этой жирности с iOS-эмодзи внутри. Файл появится в Загрузки/OPPOMoji.\n\n"
                        + "2. Примени этот файл в zFont тем же способом, что и раньше (регион Мьянма и поддержка Dai). Слот Dai делает свой шрифт главным: поэтому в файле буквы, цифры, пробелы и точки — от OPPO Sans, а эмодзи — iOS.\n\n"
                        + "3. Проверь цифры в часах, пробелы, точки, подчёркивания и эмодзи: ☺️ ❤️‍🔥 🙂‍↕️ ©️ 1️⃣ 🇷🇺.\n\n"
                        + "4. Другая жирность: сдвинь ползунок, подготовь файл заново и примени его в zFont. Штатный ползунок ColorOS вшитую жирность не меняет.\n\n"
                        + "5. Если стало хуже, верни стандартный шрифт в zFont и выключи поддержку Dai.\n\n"
                        + "Приложения со своими эмодзи (Telegram, WhatsApp) рисуют свой набор. Для разбора проблем сохрани отчёт: он показывает, каким файлом система рисует буквы, цифры, точки и эмодзи.")
                .setPositiveButton("Понятно", null).show();
    }

    private void openDisplaySettings() {
        try { startActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS)); }
        catch (android.content.ActivityNotFoundException error) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private void resetFonts() {
        new AlertDialog.Builder(this).setTitle("Убрать импортированные шрифты?")
                .setMessage("Удалятся локальные копии из OPPOMoji. Исходные файлы, сохранённые в Загрузки, и шрифты системы останутся.")
                .setNegativeButton("Отмена", null).setPositiveButton("Убрать копии", (dialog, which) ->
                        task("Убираю локальные копии…", () -> {
                            deleteText(); Files.deleteIfExists(emojiFile.toPath()); Files.deleteIfExists(repairedFile.toPath());
                            textName = "Системный шрифт"; emojiName = "Системные эмодзи";
                            verifiedEmoji = false; metricsSource = "default";
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
            catch (Exception | OutOfMemoryError error) { outcome = "Не удалось завершить: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()); }
            String result = outcome;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false); status.setText(result);
            });
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        progress.setVisibility(value ? View.VISIBLE : View.INVISIBLE);
        for (Button button : actions) button.setEnabled(!value);
        weightSlider.setEnabled(!value && weightAxis != null);
        boolean editable = repairedTextFile.isFile() || repairedFile.isFile();
        depthSlider.setEnabled(!value && editable); thicknessSlider.setEnabled(!value && editable);
        exportFont.setEnabled(!value && textFile.isFile() && emojiFile.isFile()); exportText.setEnabled(!value && textFile.isFile());
        exportFixedText.setEnabled(!value && repairedTextFile.isFile());
        findViewById(R.id.export_original_emoji).setEnabled(!value && emojiFile.isFile());
        findViewById(R.id.load_active_emoji).setEnabled(!value && Build.VERSION.SDK_INT >= 31);
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
        view.setTypeface(interfaceTypeface);
        interfaceText.add(view);
        view.setLineSpacing(dp(3), 1); view.setPadding(0, dp(3), 0, dp(5)); return view;
    }
    private void heading(TextView view) { view.setAccessibilityHeading(true); }
    private void section(LinearLayout parent, String number, String title) {
        TextView view = label(number.isEmpty() ? title : number + "   " + title, 19, WHITE); view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        boldInterfaceText.add(view);
        heading(view); parent.addView(view);
    }
    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this); button.setText(text); button.setTextSize(14); button.setAllCaps(false);
        interfaceText.add(button);
        button.setMinHeight(dp(52)); button.setTextColor(ColorStateList.valueOf(WHITE));
        button.setBackgroundTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_enabled}, new int[]{}}, new int[]{Color.rgb(45, 57, 78), Color.rgb(44, 45, 49)}));
        button.setPadding(dp(12), dp(8), dp(12), dp(8)); button.setOnClickListener(listener); actions.add(button); return button;
    }
    private Button primaryButton(String text, View.OnClickListener listener) {
        Button button = button(text, listener); button.setTextSize(15); button.setMinHeight(dp(58));
        button.setTextColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_enabled}, new int[]{}}, new int[]{BG, MUTED}));
        button.setBackgroundTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_enabled}, new int[]{}}, new int[]{PRIMARY, Color.rgb(44, 45, 49)}));
        boldInterfaceText.add(button); return button;
    }
    private LinearLayout.LayoutParams spaced(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(top); return params;
    }
    private void space(View view, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(height); view.setLayoutParams(params);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static boolean supportsCyrillic(SfntFont font) { return font.hasCodePoint('Я') && font.hasCodePoint('я') && font.hasCodePoint('щ'); }
    @Override protected void onDestroy() {
        main.removeCallbacks(refreshPreview);
        if (reportDialog != null && reportDialog.isShowing()) reportDialog.dismiss();
        super.onDestroy();
    }
}
