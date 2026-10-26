package io.github.yaroslaz.oppomoji;

import android.graphics.Paint;
import android.os.Build;
import android.text.Layout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

final class LineDiagnostics {
    static JSONObject metrics(Paint.FontMetrics metrics) throws org.json.JSONException {
        JSONObject result = new JSONObject();
        DeviceFonts.finite(result, "ascent", metrics.ascent); DeviceFonts.finite(result, "descent", metrics.descent);
        DeviceFonts.finite(result, "top", metrics.top); DeviceFonts.finite(result, "bottom", metrics.bottom);
        DeviceFonts.finite(result, "leading", metrics.leading);
        return result;
    }

    /** Call on the UI thread. Reports geometry only; never reads editable input content. */
    static JSONObject snapshot(TextView view) throws org.json.JSONException {
        JSONObject result = new JSONObject();
        DeviceFonts.finite(result, "textSizePx", view.getTextSize());
        DeviceFonts.finite(result, "lineSpacingExtraPx", view.getLineSpacingExtra());
        DeviceFonts.finite(result, "lineSpacingMultiplier", view.getLineSpacingMultiplier());
        result.put("textLocales", view.getTextLocales().toLanguageTags());
        result.put("includeFontPadding", view.getIncludeFontPadding());
        result.put("fallbackLineSpacing", view.isFallbackLineSpacing());
        result.put("elegantTextHeight", view.getPaint().isElegantTextHeight());
        Paint paint = new Paint(view.getPaint()); paint.setTextSize(100);
        result.put("metricsAt100px", metrics(paint.getFontMetrics()));
        if (Build.VERSION.SDK_INT >= 35) {
            result.put("localePreferredMinimum", view.isLocalePreferredLineHeightForMinimumUsed());
            Paint.FontMetrics minimum = view.getMinimumFontMetrics();
            result.put("explicitMinimum", minimum == null ? JSONObject.NULL : metrics(minimum));
            Paint.FontMetrics preferred = new Paint.FontMetrics(); paint.getFontMetricsForLocale(preferred);
            result.put("localeMetricsAt100px", metrics(preferred));
        }
        Layout layout = view.getLayout();
        if (layout != null) {
            result.put("lineCount", layout.getLineCount());
            JSONArray baselines = new JSONArray(), advances = new JSONArray();
            for (int i = 0; i < Math.min(layout.getLineCount(), 8); i++) {
                baselines.put(layout.getLineBaseline(i));
                if (i > 0) advances.put(layout.getLineBaseline(i) - layout.getLineBaseline(i - 1));
            }
            result.put("baselinesPx", baselines); result.put("baselineDistancesPx", advances);
        }
        return result;
    }
}
