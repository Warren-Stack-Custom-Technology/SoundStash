package com.warrenstack.nightaudio;

import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

final class YouTubeAccount {
    private static final String HEADER = "# Netscape HTTP Cookie File";
    private static final String HTTP_ONLY = "#HttpOnly_";
    private static final int MAX_IMPORT_BYTES = 1024 * 1024;
    private static boolean clearedOldSnapshots;

    static boolean hasCookies(Context context) {
        return Files.isRegularFile(cookieFile(context));
    }

    static void importCookies(Context context, Uri source) throws IOException {
        String cookies;
        try (var input = context.getContentResolver().openInputStream(source)) {
            if (input == null) throw new IOException("Couldn't open the YouTube cookie file.");
            var contents = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (contents.size() + count > MAX_IMPORT_BYTES) throw new IOException("The cookie file is too large.");
                contents.write(buffer, 0, count);
            }
            cookies = filterCookies(contents.toString(StandardCharsets.UTF_8.name()));
        }
        replaceCookies(context, cookies);
    }

    static String filterCookies(String contents) throws IOException {
        String[] lines = contents.split("\\R", -1);
        String header = lines[0].replaceFirst("^\uFEFF", "").trim();
        if (!HEADER.equals(header) && !"# HTTP Cookie File".equals(header)) {
            throw new IOException("Choose a Netscape-format YouTube cookies.txt file.");
        }
        StringBuilder kept = new StringBuilder(HEADER).append('\n');
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            boolean httpOnly = line.startsWith(HTTP_ONLY);
            if (line.trim().isEmpty() || (line.startsWith("#") && !httpOnly)) continue;
            String[] fields = (httpOnly ? line.substring(HTTP_ONLY.length()) : line).split("\t", -1);
            String domain = fields[0].toLowerCase(Locale.ROOT);
            if (!domain.equals("youtube.com") && !domain.endsWith(".youtube.com")) continue;
            if (fields.length != 7 || !domain.matches("\\.?[a-z0-9-]+(?:\\.[a-z0-9-]+)*")
                    || !(domain.startsWith(".") ? "TRUE" : "FALSE").equals(fields[1])
                    || !fields[2].startsWith("/") || !("TRUE".equals(fields[3]) || "FALSE".equals(fields[3]))
                    || !fields[4].matches("[0-9]*") || fields[5].isEmpty()
                    || fields[6].chars().anyMatch(c -> c < 32 || c == 127)) {
                throw new IOException("The YouTube cookie file contains an invalid entry.");
            }
            fields[0] = domain;
            if (httpOnly) kept.append(HTTP_ONLY);
            kept.append(String.join("\t", fields)).append('\n');
        }
        if (kept.length() == HEADER.length() + 1) throw new IOException("The file contains no YouTube cookies.");
        return kept.toString();
    }

    private static synchronized void replaceCookies(Context context, String cookies) throws IOException {
        Path file = cookieFile(context);
        Path pending = Files.createTempFile(file.getParent(), "youtube-account-", ".tmp");
        try {
            Files.write(pending, cookies.getBytes(StandardCharsets.UTF_8));
            Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    static synchronized void remove(Context context) throws IOException {
        Files.deleteIfExists(cookieFile(context));
    }

    static synchronized Path snapshot(Context context) throws IOException {
        Path cache = context.getCacheDir().toPath();
        if (!clearedOldSnapshots) {
            try (DirectoryStream<Path> old = Files.newDirectoryStream(cache, "youtube-request-*.txt")) {
                for (Path file : old) Files.deleteIfExists(file);
            }
            clearedOldSnapshots = true;
        }
        Path saved = cookieFile(context);
        if (!Files.isRegularFile(saved)) return null;
        // yt-dlp may write to the cookie file; concurrent requests need independent copies.
        Path copy = Files.createTempFile(cache, "youtube-request-", ".txt");
        try {
            return Files.copy(saved, copy, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            Files.deleteIfExists(copy);
            throw error;
        }
    }

    private static Path cookieFile(Context context) {
        return context.getNoBackupFilesDir().toPath().resolve("youtube-account.txt");
    }

    private YouTubeAccount() {}
}
