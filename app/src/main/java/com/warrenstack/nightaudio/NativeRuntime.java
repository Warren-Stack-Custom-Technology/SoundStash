package com.warrenstack.nightaudio;

import android.content.Context;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLException;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.zip.ZipInputStream;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class NativeRuntime {
    private static boolean ready;
    private static final ReentrantReadWriteLock extractor = new ReentrantReadWriteLock(true);

    static synchronized void start(Context context) throws Exception {
        if (ready) return;
        YoutubeDL.getInstance().init(context);
        FFmpeg.getInstance().init(context);
        replaceUnalignedLibraries(context);
        ready = true;
    }

    static YoutubeDLResponse youtube(Context context, YoutubeDLRequest request, String processId,
                                     Function3<Float, Long, String, Unit> progress) throws Exception {
        var reading = extractor.readLock();
        reading.lockInterruptibly();
        try {
            YoutubeDL.getInstance().init(context);
            request.addOption("--ignore-config");
            request.addOption("--sleep-requests", 1);
            request.addOption("--retry-sleep", "http:exp=5:30");
            Path cookies = YouTubeAccount.snapshot(context);
            try {
                if (cookies != null) request.addOption("--cookies", cookies.toString());
                return YoutubeDL.getInstance().execute(request, processId, progress);
            } catch (YoutubeDLException error) {
                YouTubeIssue issue = YouTubeIssue.from(error.getMessage(), cookies != null);
                if (issue != null) throw issue;
                if (cookies == null) throw error;
                throw new IOException("The request failed with the imported YouTube session. Try again or replace the cookies in Settings.");
            } finally {
                if (cookies != null) Files.deleteIfExists(cookies);
            }
        } finally {
            reading.unlock();
        }
    }

    static void updateYoutube(Context context) throws Exception {
        var writing = extractor.writeLock();
        writing.lockInterruptibly();
        try {
            YoutubeDL.getInstance().init(context);
            YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE);
        } finally {
            writing.unlock();
        }
    }

    static File libraryDirectory(Context context, String name) {
        return new File(context.getNoBackupFilesDir(), "youtubedl-android/packages/" + name + "/usr/lib");
    }

    private static void replaceUnalignedLibraries(Context context) throws IOException {
        var remaining = new HashSet<>(Arrays.asList("libsharpyuv.so", "libwebp.so",
                "libwebpdecoder.so", "libwebpdemux.so", "libwebpmux.so"));
        Path directory = libraryDirectory(context, "ffmpeg").toPath();
        try (var archive = new ZipInputStream(context.getAssets().open("ffmpeg-webp.zip"))) {
            java.util.zip.ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                String name = entry.getName();
                if (!remaining.remove(name)) throw new IOException("Unexpected FFmpeg library in the installer.");
                Path temp = Files.createTempFile(directory, "webp-", ".tmp");
                try {
                    Files.copy(archive, temp, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(temp, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temp);
                }
            }
        }
        if (!remaining.isEmpty()) throw new IOException("Some FFmpeg libraries are missing from the installer.");
    }

    private NativeRuntime() {}
}
