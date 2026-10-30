package io.github.yaroslaz.oppomoji;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.LongConsumer;
import javax.net.ssl.HttpsURLConnection;

/**
 * Updates from the project's GitHub releases: the APK is checked against SHA256SUMS.txt from the same
 * release, its package and signing key against this app, then handed to PackageInstaller and deleted.
 */
final class AppUpdate {
    static final String RELEASES = "https://api.github.com/repos/Yaroslaz/oppomoji/releases?per_page=15";
    static final String STATUS_ACTION = "io.github.yaroslaz.oppomoji.INSTALL_STATUS";
    static final long MAX_APK_BYTES = 64L * 1024 * 1024;
    private static final int MAX_LIST_BYTES = 2 * 1024 * 1024, MAX_SUMS_BYTES = 64 * 1024;

    final String version, apkName, apkUrl, sumsUrl, notes;
    final long apkBytes;

    private AppUpdate(String version, String apkName, String apkUrl, long apkBytes, String sumsUrl, String notes) {
        this.version = version; this.apkName = apkName; this.apkUrl = apkUrl; this.apkBytes = apkBytes; this.sumsUrl = sumsUrl; this.notes = notes;
    }

    /** The newest published release with an APK, if it is newer than {@code current}; otherwise null. */
    static AppUpdate newer(String current) throws Exception {
        JSONArray releases = new JSONArray(new String(fetch(RELEASES, MAX_LIST_BYTES, "application/vnd.github+json"), StandardCharsets.UTF_8));
        AppUpdate best = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            if (release.optBoolean("draft")) continue;
            String version = release.optString("tag_name").replaceFirst("^v", "");
            if (version.isEmpty() || best != null && compare(version, best.version) <= 0) continue;
            String apkName = "OPPOMoji-" + version + ".apk", apkUrl = null, sumsUrl = null; long apkBytes = 0;
            JSONArray assets = release.optJSONArray("assets");
            for (int a = 0; assets != null && a < assets.length(); a++) {
                JSONObject asset = assets.getJSONObject(a);
                String name = asset.optString("name"), url = asset.optString("browser_download_url");
                if (name.equals(apkName)) { apkUrl = url; apkBytes = asset.optLong("size"); }
                else if (name.equals("SHA256SUMS.txt")) sumsUrl = url;
            }
            if (apkUrl == null || sumsUrl == null || !apkUrl.startsWith("https://") || !sumsUrl.startsWith("https://")) continue;
            String notes = release.optString("body").trim();
            best = new AppUpdate(version, apkName, apkUrl, apkBytes, sumsUrl, notes);
        }
        return best != null && compare(best.version, current) > 0 ? best : null;
    }

    /** Orders "0.1.10-alpha" after "0.1.9-alpha" and a release after its own pre-release. */
    static int compare(String a, String b) {
        String[] left = a.split("-", 2), right = b.split("-", 2);
        String[] x = left[0].split("\\."), y = right[0].split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length ? number(x[i]) : 0, q = i < y.length ? number(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        if (left.length != right.length) return left.length < right.length ? 1 : -1;
        return left.length == 1 ? 0 : Integer.signum(left[1].compareTo(right[1]));
    }

    private static long number(String part) {
        try { return Long.parseLong(part.replaceAll("\\D", "")); } catch (NumberFormatException error) { return 0; }
    }

    /** The SHA-256 listed for {@code name} in a sha256sum file, or null. */
    static String checksum(String sums, String name) {
        for (String line : sums.split("\n")) {
            String[] parts = line.trim().split("\\s+\\*?", 2);
            if (parts.length == 2 && parts[1].equals(name) && parts[0].matches("[0-9a-fA-F]{64}")) return parts[0].toLowerCase(Locale.ROOT);
        }
        return null;
    }

    /** Downloads and verifies the APK into {@code directory}; the caller deletes it. */
    File download(File directory, LongConsumer progress) throws IOException {
        if (apkBytes <= 0 || apkBytes > MAX_APK_BYTES) throw new IOException("Неожиданный размер APK в релизе");
        String expected = checksum(new String(fetch(sumsUrl, MAX_SUMS_BYTES, null), StandardCharsets.UTF_8), apkName);
        if (expected == null) throw new IOException("В SHA256SUMS.txt нет строки для " + apkName);
        Files.createDirectories(directory.toPath());
        File target = new File(directory, apkName);
        HttpsURLConnection connection = open(apkUrl, null);
        boolean complete = false;
        try {
            if (connection.getResponseCode() != 200) throw new IOException("GitHub ответил: " + connection.getResponseCode());
            MessageDigest digest = sha256();
            long count = 0, lastProgress = 0;
            try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(target.toPath())) {
                byte[] buffer = new byte[65536]; int read;
                while ((read = input.read(buffer)) != -1) {
                    count += read;
                    if (count > apkBytes) throw new IOException("APK больше заявленного размера");
                    output.write(buffer, 0, read); digest.update(buffer, 0, read);
                    if (count - lastProgress >= 256 * 1024) { progress.accept(count); lastProgress = count; }
                }
            }
            if (count != apkBytes || !expected.equals(hex(digest.digest()))) throw new IOException("Проверка SHA-256 не прошла, обновление отменено");
            complete = true;
            return target;
        } finally {
            connection.disconnect();
            if (!complete) Files.deleteIfExists(target.toPath());
        }
    }

    /** Refuses an APK of another app, an older build or one signed with another key. */
    static void verify(Context context, File apk) throws IOException {
        PackageManager manager = context.getPackageManager();
        int flags = PackageManager.GET_SIGNING_CERTIFICATES;
        PackageInfo update = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (update == null || !context.getPackageName().equals(update.packageName)) throw new IOException("Загруженный файл не обновление OPPOMoji");
        try {
            PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
            if (update.getLongVersionCode() <= installed.getLongVersionCode()) throw new IOException("Загруженная версия не новее установленной");
            if (update.signingInfo != null && installed.signingInfo != null
                    && !Arrays.equals(update.signingInfo.getApkContentsSigners(), installed.signingInfo.getApkContentsSigners()))
                throw new IOException("APK подписан другим ключом. Установи его вручную после удаления приложения");
        } catch (PackageManager.NameNotFoundException error) { throw new IOException(error); }
    }

    /** Copies the APK into an install session and commits it; the file can be deleted right after. */
    static void install(Context context, File apk) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(apk.length());
        // Without a prompt once this app installed itself; the first update still asks the user.
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream input = Files.newInputStream(apk.toPath()); OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buffer = new byte[65536]; int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                session.fsync(output);
            }
            // Explicit and not exported: only the installer's status reaches it. The installer fills in the extras.
            Intent status = new Intent(context, UpdateReceiver.class).setAction(STATUS_ACTION);
            int mutable = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            session.commit(PendingIntent.getBroadcast(context, id, status, PendingIntent.FLAG_UPDATE_CURRENT | mutable).getIntentSender());
        } catch (IOException | RuntimeException error) {
            installer.abandonSession(id);
            throw error;
        }
    }

    /** Removes downloads left by an interrupted update. */
    static void clean(File directory) {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) if (!file.delete()) file.deleteOnExit();
    }

    private static byte[] fetch(String url, int limit, String accept) throws IOException {
        HttpsURLConnection connection = open(url, accept);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("GitHub ответил: " + connection.getResponseCode());
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384]; int read;
                while ((read = input.read(buffer)) != -1) {
                    if (output.size() + read > limit) throw new IOException("Ответ GitHub слишком большой");
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        } finally { connection.disconnect(); }
    }

    private static HttpsURLConnection open(String url, String accept) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "OPPOMoji/" + BuildConfig.VERSION_NAME);
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (accept != null) connection.setRequestProperty("Accept", accept);
        return connection;
    }

    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException error) { throw new IOException(error); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder hash = new StringBuilder();
        for (byte value : bytes) hash.append(String.format(Locale.ROOT, "%02x", value & 255));
        return hash.toString();
    }
}
