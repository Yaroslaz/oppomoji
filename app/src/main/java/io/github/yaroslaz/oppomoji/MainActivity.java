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
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
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
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int IMPORT_TEXT = 10, IMPORT_EMOJI = 11, EXPORT_FONT = 12, EXPORT_TEXT = 14, EXPORT_FIXED_TEXT = 15;
    private static final int EXPORT_ORIGINAL_EMOJI = 16;
    private static final int BG = Color.rgb(16, 17, 20), SURFACE = Color.rgb(29, 31, 36);
    private static final int PRIMARY = Color.rgb(142, 184, 255), WHITE = Color.rgb(243, 244, 248), MUTED = Color.rgb(180, 184, 195);
    // Serialize file operations across Activity recreation as well as within one screen.
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private final List<Button> actions = new ArrayList<>();
    private final List<TextView> interfaceText = new ArrayList<>();
    private final List<TextView> boldInterfaceText = new ArrayList<>();
    private File textFile, emojiFile, repairedFile, repairedTextFile;
    private SharedPreferences prefs;
    private TextView textStatus, emojiStatus, status, weightLabel, depthLabel, thicknessLabel, before, after;
    private SeekBar weightSlider, depthSlider, thicknessSlider;
    private Button exportFont, exportText, exportFixedText;
    private EditText inputBefore, inputAfter;
    private TextView systemSample;
    private Typeface interfaceTypeface = Typeface.DEFAULT;
    Font activeSystemEmoji;
    private String activeSystemEmojiName, activeSystemEmojiError;
    private SfntFont.Axis weightAxis;
    private String textName, emojiName;
    private int weight, depth, thickness;
    private boolean busy;
    private boolean textSupportsCyrillic = true;
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
        textName = prefs.getString("textName", "Системный шрифт");
        emojiName = prefs.getString("emojiName", "Системные эмодзи");
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
                    if (!replaceDigits) { weightAxis = info.weightAxis(); textSupportsCyrillic = supportsCyrillic(info); }
                }
            }
            if (replaceDigits) {
                DeviceFonts.Candidate replacement = null;
                for (DeviceFonts.Candidate font : DeviceFonts.candidates()) if (font.cyrillic) { replacement = font; break; }
                if (replacement != null) importSystemText(replacement);
                else {
                    Files.deleteIfExists(textFile.toPath()); Files.deleteIfExists(repairedTextFile.toPath());
                    weightAxis = null; textName = "Нужен OPPO Sans: служебный шрифт цифр исключён"; savePreferences();
                }
            }
            return makePreview();
        });
    }

    private void buildUi() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        ScrollView scroll = new ScrollView(this);
        scroll.setId(R.id.page_scroll);
        scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        LinearLayout page = column(); page.setPadding(dp(22), dp(20), dp(22), dp(28));
        page.setFocusableInTouchMode(true); page.requestFocus();
        scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets system = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(system.left, system.top, system.right, system.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(scroll);
        TextView eyebrow = label("ЛАБОРАТОРИЯ ШРИФТОВ · " + BuildConfig.VERSION_NAME, 11, PRIMARY); page.addView(eyebrow);
        TextView title = label("OPPOMoji", 34, WHITE); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); boldInterfaceText.add(title); heading(title); page.addView(title);
        page.addView(label("OPPO Sans + любимые эмодзи", 16, MUTED));

        LinearLayout notice = card(page);
        notice.addView(label("Сначала проверяем совместимость", 17, WHITE));
        notice.addView(label("Эта сборка проверяет и подготавливает шрифты. Предпросмотр и его вес действуют внутри OPPOMoji. Установка во всей системе зависит от ColorOS; её выполняет менеджер шрифтов, например zFont.", 14, MUTED));
        Button reportButton = button("Сохранить отчёт о совместимости", v -> saveReport());
        reportButton.setId(R.id.save_report); notice.addView(reportButton);
        notice.addView(label("JSON сохраняется в Загрузки/OPPOMoji. Системное окно выбора папки не требуется.", 12, MUTED));

        LinearLayout sources = card(page);
        section(sources, "01", "Выбрать шрифты");
        textStatus = label(textName, 15, WHITE); sources.addView(textStatus);
        sources.addView(label("Найдём оригинальный OPPO Sans на телефоне или откроем твой TTF/OTF.", 13, MUTED));
        sources.addView(button("Найти OPPO Sans на телефоне", v -> findTextFont()));
        sources.addView(button("Открыть файл шрифта текста", v -> importFont(IMPORT_TEXT)));
        sources.addView(button("Использовать активный системный шрифт", v -> task("Возвращаю системный шрифт в примеры…", () -> {
            Files.deleteIfExists(textFile.toPath()); Files.deleteIfExists(repairedTextFile.toPath());
            weightAxis = null; textName = "Активный системный шрифт"; savePreferences(); return makePreview();
        })));
        emojiStatus = label(emojiName, 15, WHITE); space(emojiStatus, 18); sources.addView(emojiStatus);
        sources.addView(label("Без импорта примеры используют активные системные эмодзи (Android 12+). Для редактирования и экспорта прочитай их кнопкой ниже или открой свой TTF/OTF.", 13, MUTED));
        sources.addView(button("Открыть файл эмодзи", v -> importFont(IMPORT_EMOJI)));
        Button activeEmoji = button("Взять активный emoji-шрифт из системы", v -> loadActiveEmoji());
        activeEmoji.setId(R.id.load_active_emoji); sources.addView(activeEmoji);
        sources.addView(label("Читает шрифт, которым Android сейчас рисует 😀. При включённой замене это может быть твой пакет из zFont.", 13, MUTED));
        Button fullEmoji = button("Загрузить полный iOS-набор · Emoji 17.0", v -> downloadEmoji());
        fullEmoji.setId(R.id.download_emoji); sources.addView(fullEmoji);
        sources.addView(label("35 МБ с GitHub, только по нажатию. Проверяется SHA-256 закреплённой версии. В Android 16 проверены 3 944 эмодзи и 9 компонентов, включая ☺️ ❤️ 😶‍🌫️ 🙂‍↕️ 🙂‍↔️. Для установки через zFont сохрани исправленный emoji-шрифт в разделе 04.", 13, MUTED));

        LinearLayout preview = card(page);
        section(preview, "02", "Сравнить отображение");
        weightLabel = label("Вес предпросмотра: " + weight, 15, WHITE); preview.addView(weightLabel);
        weightSlider = slider(preview, "Вес шрифта в предпросмотре", 900, weight - 100, value -> {
            weight = value + 100; weightLabel.setText("Вес предпросмотра: " + weight);
        });
        weightSlider.setId(R.id.weight_slider);
        preview.addView(label("Плавный вес доступен после выбора шрифта с осью wght. Штатный ползунок ColorOS приложение не меняет.", 13, MUTED));
        preview.addView(label("Исходный текстовый шрифт и эмодзи", 12, PRIMARY));
        before = sample(); before.setId(R.id.preview_before); preview.addView(before);
        TextView repairedLabel = label("Копия с выбранными метриками линии", 12, PRIMARY); space(repairedLabel, 18); preview.addView(repairedLabel);
        after = sample(); after.setId(R.id.preview_after); preview.addView(after);
        preview.addView(label("Поле ввода: исходный шрифт", 12, PRIMARY));
        inputBefore = inputSample(); inputBefore.setId(R.id.input_before); preview.addView(inputBefore);
        preview.addView(label("Поле ввода: исправленная копия", 12, PRIMARY));
        inputAfter = inputSample(); inputAfter.setId(R.id.input_after); preview.addView(inputAfter);
        preview.addView(label("Белая линия — обычное подчёркивание, цветная — тестовая пометка орфографии. Толщину цветной линии задаёт Android; её положение берётся из шрифта. В отчёт содержимое полей не попадает.", 13, MUTED));
        preview.addView(label("Активный системный шрифт: межстрочный интервал", 12, PRIMARY));
        systemSample = label("Первая строка: Яя Щщ\nВторая строка: OPPO Sans\nТретья строка: 😀 👋", 15, WHITE);
        systemSample.setId(R.id.active_system_sample); interfaceText.remove(systemSample);
        systemSample.setTypeface(Typeface.DEFAULT); preview.addView(systemSample);
        preview.addView(label("Этот образец следует системным настройкам Dai. Остальные подписи используют выбранный текстовый шрифт. Отчёт сравнивает реальные расстояния между строками.", 13, MUTED));

        LinearLayout metrics = card(page);
        section(metrics, "03", "Настроить подчёркивание");
        metrics.addView(label("Ползунки меняют линию в копии текстового шрифта и, если он выбран, emoji-шрифта. Изменения видны в исправленном примере. Для начала выбери файл текстового шрифта.", 13, MUTED));
        depthLabel = label("Отступ от базовой линии: " + depth + "%", 15, WHITE); metrics.addView(depthLabel);
        depthSlider = slider(metrics, "Отступ подчёркивания в процентах em", 23, depth - 2, value -> {
            depth = value + 2; depthLabel.setText("Отступ от базовой линии: " + depth + "%");
        });
        depthSlider.setId(R.id.depth_slider);
        thicknessLabel = label(thicknessText(), 15, WHITE); metrics.addView(thicknessLabel);
        thicknessSlider = slider(metrics, "Толщина подчёркивания в десятых процента em", 75, thickness - 5, value -> {
            thickness = value + 5; thicknessLabel.setText(thicknessText());
        });
        thicknessSlider.setId(R.id.thickness_slider);
        metrics.addView(button("Взять метрики из OPPO Sans", v -> useTextMetrics()));

        LinearLayout export = card(page);
        section(export, "04", "Проверить в ColorOS");
        exportFont = button("Сохранить исправленный emoji-шрифт", v -> createDocument(EXPORT_FONT, "OPPOMoji-emoji.ttf", "font/ttf"));
        exportFont.setId(R.id.export_font);
        export.addView(exportFont);
        Button originalEmoji = button("Сохранить исходный emoji-шрифт", v -> createDocument(EXPORT_ORIGINAL_EMOJI, "OPPOMoji-emoji-original.ttf", "font/ttf"));
        originalEmoji.setId(R.id.export_original_emoji); export.addView(originalEmoji);
        exportFixedText = button("Сохранить текстовый шрифт с исправлением линии", v -> createDocument(EXPORT_FIXED_TEXT, "OPPOMoji-text-fixed.ttf", "font/ttf"));
        exportFixedText.setId(R.id.export_fixed_text); export.addView(exportFixedText);
        export.addView(button("Открыть инструкцию для ColorOS", v -> instructions()));
        export.addView(button("Открыть настройки экрана", v -> openDisplaySettings()));
        export.addView(button("Сохранить отчёт о совместимости", v -> saveReport()));
        exportText = button("Сохранить копию шрифта текста", v -> createDocument(EXPORT_TEXT, "OPPOMoji-text.ttf", "font/ttf")); export.addView(exportText);
        export.addView(button("Убрать импортированные шрифты", v -> resetFonts()));

        status = label("Готово к проверке", 14, PRIMARY); status.setId(R.id.status); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setTextIsSelectable(true); page.addView(status);
        TextView footer = label("Обработка шрифтов работает без сети и root. Интернет используется по кнопке загрузки набора; отчёты никуда не отправляются.", 12, MUTED); space(footer, 20); page.addView(footer);
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

    private void createDocument(int request, String name, String mime) {
        task("Сохраняю шрифт в Загрузки…", () -> {
            File source = exportSource(request);
            lastFontUri = ReportStorage.save(this, name, mime, output -> Files.copy(source.toPath(), output));
            return "Шрифт сохранён: Загрузки/OPPOMoji/" + name;
        });
    }

    private File exportSource(int request) throws IOException {
        File source = request == EXPORT_FONT ? repairedFile : request == EXPORT_FIXED_TEXT ? repairedTextFile
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
        Uri uri = data.getData();
        if (request == IMPORT_TEXT || request == IMPORT_EMOJI) {
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
                    weightAxis = info.weightAxis(); textName = info.familyName() + " · " + name;
                    textSupportsCyrillic = supportsCyrillic(info);
                    if (weightAxis != null) weight = Math.round(Math.max(weightAxis.min, Math.min(weightAxis.max, weight)));
                } else {
                    if (!info.hasTable("CBDT") && !info.hasTable("COLR") && !info.hasTable("sbix") && !info.hasTable("SVG ")) {
                        throw new IOException("В файле нет цветных emoji-таблиц. Выбери emoji-шрифт TTF/OTF");
                    }
                    Files.move(temporary.toPath(), emojiFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    emojiName = info.familyName() + " · " + info.colorFormat();
                    verifiedEmoji = false;
                }
                savePreferences(); return makePreview();
            });
        } else if (request == EXPORT_FONT || request == EXPORT_FIXED_TEXT || request == EXPORT_TEXT) {
            task("Сохраняю файл…", () -> {
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IOException("Не удалось открыть выбранное место сохранения");
                    File source = exportSource(request);
                    Files.copy(source.toPath(), output);
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
            emojiName = info.familyName() + " · активные системные эмодзи";
            verifiedEmoji = false;
            savePreferences(); return makePreview();
        });
    }

    private void downloadEmoji() {
        task("Загружаю проверенный iOS-набор · 35 МБ…", () -> {
            File temporary = new File(getCacheDir(), "download-emoji.ttf");
            EmojiSource.download(temporary, bytes -> runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) status.setText("Загружаю iOS-набор: " + bytes * 100 / EmojiSource.BYTES + "%");
            }));
            try (FontFile source = new FontFile(temporary)) {
                SfntFont info = source.metadata(0);
                if (!info.hasTable("CBDT") || !info.hasTable("GSUB")) throw new IOException("Набор не содержит нужных цветных таблиц и составных эмодзи");
            }
            new Font.Builder(temporary).build();
            Files.move(temporary.toPath(), emojiFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            emojiName = "iOS-набор · Emoji 17.0 · 3 944 эмодзи + 9 компонентов"; verifiedEmoji = true;
            savePreferences(); return makePreview();
        });
    }

    private void savePreferences() {
        prefs.edit().putString("textName", textName).putString("emojiName", emojiName)
                .putBoolean("verifiedEmoji", verifiedEmoji)
                .putInt("weight", weight).putInt("depth", depth).putInt("thickness", thickness).apply();
    }

    private void findTextFont() {
        task("Ищу доступные шрифты OPPO…", () -> {
            List<DeviceFonts.Candidate> candidates = DeviceFonts.candidates();
            if (candidates.isEmpty()) return "Доступный файл OPPO Sans не найден. OPPO Serif не подходит. Сохрани отчёт кнопкой вверху — он поможет найти системное семейство Sans.";
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

    private void loadSystemFont(DeviceFonts.Candidate source) {
        task("Проверяю OPPO Sans…", () -> {
            importSystemText(source); return makePreview();
        });
    }

    private void importSystemText(DeviceFonts.Candidate source) throws Exception {
        byte[] bytes;
        try (FontFile font = new FontFile(source.file)) { bytes = font.extract(source.index); }
        SfntFont info = new SfntFont(bytes); SfntFont.Axis axis = info.weightAxis();
        File temporary = new File(getCacheDir(), "system-import.ttf"); Files.write(temporary.toPath(), bytes);
        new Font.Builder(temporary).build();
        Files.move(temporary.toPath(), textFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        weightAxis = axis; textName = info.familyName() + " · " + source.file.getName();
        textSupportsCyrillic = supportsCyrillic(info);
        if (axis != null) weight = Math.round(Math.max(axis.min, Math.min(axis.max, weight)));
        savePreferences();
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
        String message = "Предпросмотр готов. Изменения действуют внутри OPPOMoji.";
        String textError = repair(textFile, repairedTextFile);
        String emojiError = repair(emojiFile, repairedFile);
        Typeface originalResult = typeface(textFile, emojiFile);
        Typeface repairedResult = typeface(repairedTextFile.isFile() ? repairedTextFile : textFile,
                repairedFile.isFile() ? repairedFile : emojiFile);
        Typeface interfaceFont = textFile.isFile() && textSupportsCyrillic ? typeface(textFile, emojiFile, 400) : null;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            before.setTypeface(originalResult); after.setTypeface(repairedResult);
            inputBefore.setTypeface(originalResult); inputAfter.setTypeface(repairedResult);
            restoreDemoSuggestion(inputBefore); restoreDemoSuggestion(inputAfter);
            interfaceTypeface = interfaceFont == null ? Typeface.DEFAULT : interfaceFont;
            for (TextView view : interfaceText) {
                view.setTypeface(boldInterfaceText.contains(view) ? Typeface.create(interfaceTypeface, Typeface.BOLD) : interfaceTypeface);
                // Keep fallback spacing for actual tall glyphs, but avoid an unrelated locale minimum.
                if (Build.VERSION.SDK_INT >= 35) view.setLocalePreferredLineHeightForMinimumUsed(false);
            }
            textStatus.setText(textFile.isFile() ? textName + (textSupportsCyrillic ? "" : " · нет кириллицы: русские буквы из системного шрифта") : "Активный системный шрифт · OPPO Sans пока не подтверждён");
            emojiStatus.setText(emojiFile.isFile() ? emojiName : activeSystemEmoji != null
                    ? activeSystemEmojiName + " · из системы, подключены к примерам. Для экспорта нажми «Взять активный emoji-шрифт»."
                    : "Активные эмодзи недоступны для выбранного Sans: " + (activeSystemEmojiError == null ? "открой TTF/OTF или используй системный шрифт текста" : activeSystemEmojiError));
            if (weightAxis != null) {
                int min = Math.max(1, (int) Math.ceil(weightAxis.min));
                int max = Math.min(1000, (int) Math.floor(weightAxis.max));
                weightSlider.setMin(min); weightSlider.setMax(Math.max(min, max));
                // The slider now represents actual weights, rather than the initial placeholder range.
                weightSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                        if (fromUser) {
                            weight = value; weightLabel.setText("Вес предпросмотра: " + weight);
                            main.removeCallbacks(refreshPreview); main.postDelayed(refreshPreview, 180);
                        }
                    }
                    public void onStartTrackingTouch(SeekBar bar) { }
                    public void onStopTrackingTouch(SeekBar bar) { main.removeCallbacks(refreshPreview); refresh(); }
                });
                weightSlider.setProgress(weight);
                weightLabel.setText("Вес предпросмотра: " + weight);
            } else {
                weightLabel.setText("Вес: нужен шрифт с осью wght");
            }
            depthSlider.setProgress(depth - 2); depthLabel.setText("Отступ от базовой линии: " + depth + "%");
            thicknessSlider.setProgress(thickness - 5); thicknessLabel.setText(thicknessText());
        });
        if (textError != null) message = "Исправление текста недоступно: " + textError;
        else if (repairedTextFile.isFile()) message = "Копия текстового шрифта готова. Ползунки меняют линию в исправленном примере; исходный пример сохраняет свою линию.";
        else if (repairedFile.isFile()) message = "Копия emoji-шрифта готова. Для изменения линии под буквами выбери текстовый шрифт.";
        if (emojiError != null) message += " Исправление эмодзи недоступно: " + emojiError;
        return message;
    }

    private String repair(File source, File target) throws IOException {
        Files.deleteIfExists(target.toPath());
        if (!source.isFile()) return null;
        try {
            SfntFont font = new SfntFont(DeviceFonts.read(source));
            byte[] fixed = font.withUnderline(-Math.round(depth * font.unitsPerEm() / 100f),
                    Math.max(1, Math.round(thickness * font.unitsPerEm() / 1000f)));
            Files.write(target.toPath(), fixed); new Font.Builder(target).build(); return null;
        } catch (Exception error) {
            Files.deleteIfExists(target.toPath()); return error.getMessage();
        }
    }

    private Typeface typeface(File textFile, File emoji) throws Exception {
        return typeface(textFile, emoji, weight);
    }

    private Typeface typeface(File textFile, File emoji, int requestedWeight) throws Exception {
        // An emoji-only family must never become the primary family for letters or switch OEM sans to Roboto.
        if (!textFile.isFile()) return Typeface.DEFAULT;
        Font.Builder text = new Font.Builder(textFile);
        if (weightAxis != null) text.setFontVariationSettings("'wght' " + requestedWeight).setWeight(Math.max(1, Math.min(1000, requestedWeight)));
        FontFamily textFamily = new FontFamily.Builder(text.build()).build();
        Typeface.CustomFallbackBuilder family = new Typeface.CustomFallbackBuilder(textFamily).setSystemFallback("sans-serif");
        if (weightAxis != null) family.setStyle(new FontStyle(Math.max(1, Math.min(1000, requestedWeight)), FontStyle.FONT_SLANT_UPRIGHT));
        Font emojiFont = emoji.isFile() ? new Font.Builder(emoji).build() : activeSystemEmoji;
        if (emojiFont != null) family.addCustomFallback(new FontFamily.Builder(emojiFont).build());
        return family.build();
    }

    private void instructions() {
        new AlertDialog.Builder(this).setTitle("Проверка на твоём OPPO")
                .setMessage("1. Выбери оригинальный OPPO Sans. При включённых эмодзи из zFont нажми «Взять активный emoji-шрифт из системы» или открой его TTF вручную.\n\n"
                        + "2. Нажми «Взять метрики из OPPO Sans», затем подвигай ползунки и сравни исходный и исправленный примеры. Меняются метрики текста и эмодзи.\n\n"
                        + "3. Сохрани нужную копию TTF в Загрузки/OPPOMoji и импортируй её в менеджер шрифтов, например zFont. Эта сборка экспортирует текст и эмодзи отдельно, не объединяя их в один пакет.\n\n"
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
                            Files.deleteIfExists(textFile.toPath()); Files.deleteIfExists(emojiFile.toPath()); Files.deleteIfExists(repairedFile.toPath()); Files.deleteIfExists(repairedTextFile.toPath());
                            weightAxis = null; textName = "Системный шрифт"; emojiName = "Системные эмодзи";
                            verifiedEmoji = false;
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
        depthSlider.setEnabled(!value && repairedTextFile.isFile()); thicknessSlider.setEnabled(!value && repairedTextFile.isFile());
        exportFont.setEnabled(!value && repairedFile.isFile()); exportText.setEnabled(!value && textFile.isFile());
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
        TextView view = label(number + "   " + title, 19, WHITE); view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
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
