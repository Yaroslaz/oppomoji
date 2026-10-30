package io.github.yaroslaz.oppomoji;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import java.util.function.Consumer;

/** Install-session status from PackageInstaller: opens the confirmation and reports the outcome. */
public final class UpdateReceiver extends BroadcastReceiver {
    /** The visible screen's status line, if any. */
    static volatile Consumer<String> listener;

    @Override public void onReceive(Context context, Intent intent) {
        if (!AppUpdate.STATUS_ACTION.equals(intent.getAction())) return;
        int code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message;
        if (code == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class) : legacyIntent(intent);
            if (confirm == null) message = "Система не показала окно установки. Попробуй ещё раз";
            else {
                try { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); message = "Подтверди установку обновления"; }
                catch (RuntimeException error) { message = "Не удалось открыть окно установки: " + error.getMessage(); }
            }
        } else if (code == PackageInstaller.STATUS_SUCCESS) message = "Обновление установлено";
        else if (code == PackageInstaller.STATUS_FAILURE_ABORTED) message = "Установка обновления отменена";
        else {
            String detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            message = "Обновление не установлено" + (detail == null ? "" : ": " + detail);
        }
        Consumer<String> target = listener;
        if (target != null) target.accept(message);
    }

    @SuppressWarnings("deprecation")
    private static Intent legacyIntent(Intent intent) { return intent.getParcelableExtra(Intent.EXTRA_INTENT); }
}
