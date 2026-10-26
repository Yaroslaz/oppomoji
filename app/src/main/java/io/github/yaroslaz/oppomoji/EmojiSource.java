package io.github.yaroslaz.oppomoji;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.function.LongConsumer;
import javax.net.ssl.HttpsURLConnection;

/** A versioned external font, fetched only by an explicit button press. */
final class EmojiSource {
    static final String URL = "https://raw.githubusercontent.com/dtingley11/KernelSU-iOS-Emoji/dd14ebd2db66fa4584a85876b002a6f1057dbc7f/system/fonts/NotoColorEmoji.ttf";
    static final String SHA256 = "eba8f2686c3e2bc953da8278c03f6b24c36ac81291d233748211a8d1f377062d";
    static final long BYTES = 35322044;

    static void download(File target, LongConsumer progress) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(URL).openConnection();
        connection.setConnectTimeout(20000); connection.setReadTimeout(30000);
        connection.setRequestProperty("Accept-Encoding", "identity");
        boolean complete = false;
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Сервер шрифта ответил: " + connection.getResponseCode());
            long advertised = connection.getContentLengthLong();
            if (advertised > 0 && advertised != BYTES) throw new IOException("Размер загрузки отличается от проверенного набора");
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException error) { throw new IOException(error); }
            long count = 0, lastProgress = 0;
            try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(target.toPath())) {
                byte[] buffer = new byte[65536]; int read;
                while ((read = input.read(buffer)) != -1) {
                    count += read;
                    if (count > BYTES) throw new IOException("Загрузка больше проверенного размера");
                    output.write(buffer, 0, read); digest.update(buffer, 0, read);
                    if (count - lastProgress >= 1024 * 1024) { progress.accept(count); lastProgress = count; }
                }
            }
            StringBuilder hash = new StringBuilder();
            for (byte value : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", value & 255));
            if (count != BYTES || !SHA256.contentEquals(hash)) throw new IOException("Проверка SHA-256 не прошла. Прежний шрифт сохранён");
            complete = true;
        } finally {
            connection.disconnect();
            if (!complete) Files.deleteIfExists(target.toPath());
        }
    }
}
