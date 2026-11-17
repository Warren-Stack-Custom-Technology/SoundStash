package com.warrenstack.nightaudio;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.regex.Pattern;

final class WorkFiles {
    private static final String TAG = "NightAudio";
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{11}(?:\\.[a-f0-9-]{36})?");
    private final Path root;
    private final ExecutorService cleanup = Executors.newSingleThreadExecutor();

    WorkFiles(Context context) {
        root = context.getFilesDir().toPath().resolve("audio-work");
    }

    static String newKey(String id) {
        return YouTubeLinks.isVideo(id) ? id + "." + UUID.randomUUID() : "";
    }

    static String videoId(String key) {
        return KEY.matcher(key).matches() ? key.substring(0, 11) : "";
    }

    Path directory(String key) {
        if (videoId(key).isEmpty()) throw new IllegalArgumentException("Invalid audio work directory");
        return root.resolve(key);
    }

    void discard(String key) {
        if (!videoId(key).isEmpty()) cleanup.execute(() -> delete(directory(key)));
    }

    void sweep(Predicate<String> keep) {
        cleanup.execute(() -> {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
            try (var folders = Files.newDirectoryStream(root)) {
                for (Path folder : folders) {
                    String key = folder.getFileName().toString();
                    if (!videoId(key).isEmpty() && !keep.test(key)) delete(folder);
                }
            } catch (IOException error) {
                Log.w(TAG, "Could not inspect audio working files", error);
            }
        });
    }

    private static void delete(Path folder) {
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                    if (error instanceof NoSuchFileException) return FileVisitResult.CONTINUE;
                    throw error;
                }

                @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                    if (error != null) throw error;
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException error) {
            Log.w(TAG, "Could not clear audio working files", error);
        }
    }
}
