package com.warrenstack.nightaudio;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import com.yausername.youtubedl_android.YoutubeDLRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class AudioEngine {
    enum Format {
        OPUS("Opus · 24 kbps", ".opus", "audio/ogg", "libopus"),
        M4B("M4B · AAC 32 kbps", ".m4b", "audio/mp4", "aac");

        final String label, extension, mime, codec;
        Format(String label, String extension, String mime, String codec) {
            this.label = label;
            this.extension = extension;
            this.mime = mime;
            this.codec = codec;
        }
    }

    interface Progress { void update(String title, String stage, int percent); }
    record Result(String title, Uri uri, String mime, int chapters, long bytes) {}
    record Prepared(String id, File directory, File source, String title, String artist,
                    List<Chapters.Chapter> chapters, long durationMs) {}

    private final Context context;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile Process process;
    private Thread downloadThread;

    AudioEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    synchronized void cancel() {
        stopped.set(true);
        if (downloadThread != null) downloadThread.interrupt();
        Process running = process;
        if (running != null) running.destroy();
    }

    Prepared prepare(String id, File dir, Workload workload, Progress progress) throws Exception {
        checkStop();
        String processId = "night-audio-" + id;
        Files.createDirectories(dir.toPath());
        File infoFile = new File(dir, "source.info.json");
        File marker = new File(dir, "downloaded-path");
        File source;
        NativeRuntime.start(context);
        if (marker.isFile() && infoFile.isFile()) {
            source = localFile(dir, new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8));
            if (!source.isFile()) {
                Files.delete(marker.toPath());
                source = download(id, processId, dir, marker, workload, progress);
            }
        } else {
            source = download(id, processId, dir, marker, workload, progress);
        }
        checkStop();
        JSONObject info = new JSONObject(new String(Files.readAllBytes(infoFile.toPath()), StandardCharsets.UTF_8));
        String title = info.optString("title", "YouTube " + id);
        String artist = info.optString("channel", info.optString("uploader", ""));
        List<Chapters.Chapter> chapters = Chapters.fromYtDlp(info);
        long durationMs = Math.round(info.optDouble("duration", 0) * 1000);
        progress.update(title, "Ready to encode", -1);
        return new Prepared(id, dir, source, title, artist, chapters, durationMs);
    }

    Result export(Prepared ready, Format format, boolean split, Workload workload, DownloadFolder folder, Progress progress) throws Exception {
        checkStop();
        File dir = ready.directory();
        File source = ready.source();
        String title = ready.title();
        String artist = ready.artist();
        List<Chapters.Chapter> chapters = ready.chapters();
        long durationMs = ready.durationMs();
        boolean tracks = split && !chapters.isEmpty();
        List<File> outputs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        String base = fileName(title, 115) + " [" + ready.id() + "]";
        if (tracks) {
            int digits = Math.max(2, Integer.toString(chapters.size()).length());
            for (int i = 0; i < chapters.size(); i++) {
                Chapters.Chapter chapter = chapters.get(i);
                String number = String.format(java.util.Locale.ROOT, "%0" + digits + "d", i + 1);
                File output = new File(dir, "track-" + number + format.extension);
                progress.update(title, "Encoding chapter " + (i + 1) + " of " + chapters.size(), -1);
                encode(source, output, null, format, chapter, chapter.title(), title, artist, workload.encodeRate, null);
                verify(output, format, java.util.Collections.emptyList());
                outputs.add(output);
                names.add(number + " - " + fileName(chapter.title(), 145) + format.extension);
            }
        } else {
            File chapterFile = new File(dir, "chapters.ffmetadata");
            Files.write(chapterFile.toPath(), Chapters.write(chapters).getBytes(StandardCharsets.UTF_8));
            File output = new File(dir, "encoded" + format.extension);
            progress.update(title, "Encoding audio", 0);
            encode(source, output, chapterFile, format, null, title, "", artist, workload.encodeRate, line -> {
                if (line.startsWith("out_time_us=") && durationMs > 0) {
                    try {
                        long micros = Long.parseLong(line.substring(12));
                        int percent = (int) Math.max(0, Math.min(99, micros / (10 * durationMs)));
                        progress.update(title, "Encoding audio", percent);
                    } catch (NumberFormatException ignored) {}
                }
            });
            progress.update(title, "Checking chapters", -1);
            verify(output, format, chapters);
            outputs.add(output);
            names.add(base + format.extension);
        }

        checkStop();
        progress.update(title, "Saving to " + folder.label(), -1);
        long size = 0;
        for (File output : outputs) size += output.length();
        String subfolder = tracks ? base : "";
        Uri saved = folder.tree() == null ? publishToDownloads(outputs, names, format, subfolder)
                : folder.publish(context, outputs, names, format.mime, subfolder, this::checkStop);
        return new Result(title, saved, format.mime, chapters.size(), size);
    }

    private File download(String id, String processId, File dir, File marker, Workload workload,
                          Progress progress) throws Exception {
        checkStop();
        YoutubeDLRequest request = new YoutubeDLRequest(YouTubeLinks.url(id));
        request.addOption("--no-playlist");
        request.addOption("--no-simulate");
        request.addOption("--no-overwrites");
        request.addOption("--no-color");
        request.addOption("--newline");
        request.addOption("--progress");
        request.addOption("--write-info-json");
        request.addOption("--socket-timeout", 25);
        request.addOption("--retries", 5);
        request.addOption("--fragment-retries", 5);
        request.addOption("--concurrent-fragments", workload.fragments);
        if (!workload.downloadRate.isEmpty()) request.addOption("--limit-rate", workload.downloadRate);
        request.addOption("--remote-components", "ejs:github");
        request.addOption("--format", "bestaudio");
        request.addOption("--output", new File(dir, "source.%(ext)s").getAbsolutePath());
        request.addOption("--print", "after_move:filepath");
        synchronized (this) {
            checkStop();
            downloadThread = Thread.currentThread();
        }
        try {
            var response = NativeRuntime.youtube(context, request, processId, (percent, eta, line) -> {
                if (percent >= 0 && !stopped.get()) {
                    progress.update(null, "Downloading audio", Math.min(100, percent.intValue()));
                }
                return kotlin.Unit.INSTANCE;
            });
            checkStop();
            File source = null;
            for (String line : response.getOut().split("\\R")) {
                if (!line.startsWith(dir.getAbsolutePath() + "/source.")) continue;
                source = localFile(dir, line);
            }
            if (source == null || !source.isFile() || source.length() == 0) {
                throw new IOException("The download did not return a complete audio file.");
            }
            Files.write(marker.toPath(), source.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
            return source;
        } finally {
            synchronized (this) {
                downloadThread = null;
                if (stopped.get()) Thread.interrupted();
            }
        }
    }

    private File localFile(File dir, String path) throws IOException {
        File file = new File(path.trim()).getCanonicalFile();
        if (!dir.getCanonicalFile().equals(file.getParentFile())
                || !file.getName().matches("source\\.[A-Za-z0-9]{1,12}")) {
            throw new IOException("The downloader returned an unexpected file path.");
        }
        return file;
    }

    private void encode(File source, File output, File metadata, Format format, Chapters.Chapter slice,
                        String title, String album, String artist, int readRate, Consumer<String> progress) throws Exception {
        List<String> args = new ArrayList<>(java.util.Arrays.asList("-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-filter_threads", "1"));
        if (slice != null) args.addAll(java.util.Arrays.asList("-ss", seconds(slice.start())));
        if (readRate > 0) args.addAll(java.util.Arrays.asList("-readrate", Integer.toString(readRate)));
        args.addAll(java.util.Arrays.asList("-threads", "1", "-i", source.getAbsolutePath()));
        if (metadata != null) args.addAll(java.util.Arrays.asList("-f", "ffmetadata", "-i", metadata.getAbsolutePath()));
        String chapterSource = metadata == null ? "-1" : "1";
        args.addAll(java.util.Arrays.asList("-map", "0:a:0", "-map_metadata", chapterSource,
                "-map_chapters", chapterSource, "-threads", "1", "-c:a", format.codec));
        if (slice != null) args.addAll(java.util.Arrays.asList("-t", seconds(slice.end() - slice.start())));
        if (format == Format.OPUS) {
            args.addAll(java.util.Arrays.asList("-b:a", "24k", "-vbr", "on", "-compression_level", "8", "-application", "audio"));
        } else {
            args.addAll(java.util.Arrays.asList("-b:a", "32k", "-movflags", "+faststart", "-f", "mp4"));
        }
        args.addAll(java.util.Arrays.asList("-metadata", "title=" + title));
        if (!album.trim().isEmpty()) args.addAll(java.util.Arrays.asList("-metadata", "album=" + album));
        if (!artist.trim().isEmpty()) args.addAll(java.util.Arrays.asList("-metadata", "artist=" + artist));
        args.addAll(java.util.Arrays.asList("-progress", "pipe:1", output.getAbsolutePath()));
        run("ffmpeg", args, progress);
    }

    private void verify(File output, Format format, List<Chapters.Chapter> chapters) throws Exception {
        if (!output.isFile() || output.length() == 0) throw new IOException("The audio export was empty.");
        String json = run("ffprobe", java.util.Arrays.asList("-v", "error", "-select_streams", "a:0", "-show_entries",
                "stream=codec_name:chapter=start_time:chapter_tags=title", "-of", "json", output.getAbsolutePath()), null);
        JSONObject data = new JSONObject(json);
        JSONArray streams = data.optJSONArray("streams");
        String expected = format == Format.OPUS ? "opus" : "aac";
        if (streams == null || streams.length() != 1 || !expected.equals(streams.getJSONObject(0).optString("codec_name"))) {
            throw new IOException("The exported file did not contain the requested audio.");
        }
        JSONArray found = data.optJSONArray("chapters");
        Chapters.verify(chapters, found == null ? new JSONArray() : found);
    }

    private String run(String tool, List<String> args, Consumer<String> progress) throws Exception {
        checkStop();
        File bin = new File(context.getApplicationInfo().nativeLibraryDir, "lib" + tool + ".so");
        File libs = NativeRuntime.libraryDirectory(context, "ffmpeg");
        File pythonLibs = NativeRuntime.libraryDirectory(context, "python");
        List<String> command = new ArrayList<>();
        command.add(bin.getAbsolutePath());
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("LD_LIBRARY_PATH", libs + ":" + pythonLibs + ":" + bin.getParentFile());
        StringBuilder output = new StringBuilder();
        Process running = builder.start();
        process = running;
        try {
            if (stopped.get()) running.destroy();
            try (var reader = new BufferedReader(new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (progress != null) progress.accept(line);
                    if (output.length() < 4 * 1024 * 1024) output.append(line).append('\n');
                }
            }
            int exit = running.waitFor();
            checkStop();
            if (exit != 0) throw new IOException(tool + " failed: " + output.toString().trim());
            return output.toString();
        } finally {
            process = null;
            if (running.isAlive()) running.destroy();
        }
    }

    private Uri publishToDownloads(List<File> outputs, List<String> names, Format format, String subfolder) throws Exception {
        var resolver = context.getContentResolver();
        Uri downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        List<Uri> saved = new ArrayList<>();
        try {
            for (int i = 0; i < outputs.size(); i++) {
                checkStop();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, names.get(i));
                values.put(MediaStore.Downloads.MIME_TYPE, format.mime);
                values.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + DownloadFolder.DEFAULT_NAME + "/"
                                + (subfolder.isEmpty() ? "" : subfolder + "/"));
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri entry = resolver.insert(downloads, values);
                if (entry == null) throw new IOException("Android could not create the download.");
                saved.add(entry);
                try (OutputStream out = resolver.openOutputStream(entry, "w")) {
                    if (out == null) throw new IOException("The download folder could not be opened.");
                    Files.copy(outputs.get(i).toPath(), out);
                }
            }
            checkStop();
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Downloads.IS_PENDING, 0);
            for (Uri uri : saved) {
                if (resolver.update(uri, ready, null, null) != 1) throw new IOException("The file could not be published.");
            }
            return saved.get(0);
        } catch (Exception error) {
            for (Uri uri : saved) {
                try { resolver.delete(uri, null, null); } catch (RuntimeException ignored) {}
            }
            throw error;
        }
    }

    private static String fileName(String title, int max) {
        String clean = title.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", " ").replaceAll("\\s+", " ").trim();
        if (clean.length() > max) clean = clean.substring(0, max);
        clean = clean.replaceAll("[. ]+$", "");
        return clean.isEmpty() ? "Audio" : clean;
    }

    private static String seconds(long milliseconds) {
        return java.math.BigDecimal.valueOf(milliseconds, 3).toPlainString();
    }

    private void checkStop() {
        if (stopped.get()) throw new CancellationException("Stopped");
    }
}
