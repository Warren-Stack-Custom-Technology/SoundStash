package com.warrenstack.nightaudio;

import android.content.Context;

import com.yausername.youtubedl_android.YoutubeDLRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class QueueMetadata {
    private static QueueMetadata instance;

    private final Context context;
    private final QueueStore queue;
    private final ExecutorService titles = Executors.newFixedThreadPool(2);
    private final ExecutorService playlists = Executors.newSingleThreadExecutor();
    private final Set<String> attemptedTitles = ConcurrentHashMap.newKeySet();
    private final Set<String> loadingPlaylists = ConcurrentHashMap.newKeySet();

    static synchronized QueueMetadata get(Context context) {
        if (instance == null) instance = new QueueMetadata(context.getApplicationContext());
        return instance;
    }

    private QueueMetadata(Context context) {
        this.context = context;
        queue = QueueStore.get(context);
    }

    void refresh() {
        for (QueueStore.Item item : queue.snapshot()) {
            if (item.status() != QueueStore.Status.QUEUED) continue;
            if (YouTubeLinks.isPlaylist(item.id())) {
                if (loadingPlaylists.add(item.id())) playlists.execute(() -> loadPlaylist(item.id()));
            } else if (!item.hasTitle() && attemptedTitles.add(item.id())) {
                titles.execute(() -> loadTitle(item.id()));
            }
        }
    }

    private void loadTitle(String id) {
        if (!queue.needsTitle(id)) {
            attemptedTitles.remove(id);
            return;
        }

        HttpURLConnection connection = null;
        try {
            String video = URLEncoder.encode(YouTubeLinks.url(id), "UTF-8");
            URL endpoint = new URL("https://www.youtube.com/oembed?format=json&url=" + video);
            connection = (HttpURLConnection) endpoint.openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return;
            try (var input = connection.getInputStream(); var output = new ByteArrayOutputStream()) {
                byte[] bytes = new byte[4096];
                int read;
                while ((read = input.read(bytes)) != -1) {
                    if (output.size() + read > 64 * 1024) return;
                    output.write(bytes, 0, read);
                }
                JSONObject metadata = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
                if (!metadata.isNull("title")) queue.setTitle(id, metadata.optString("title"));
            }
        } catch (Exception ignored) {
            // The full download still supplies a title from yt-dlp.
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void loadPlaylist(String id) {
        try {
            if (!queue.needsPlaylist(id)) return;
            YoutubeDLRequest request = new YoutubeDLRequest(YouTubeLinks.url(id));
            request.addOption("--flat-playlist");
            request.addOption("--skip-download");
            request.addOption("--dump-single-json");
            request.addOption("--ignore-errors");
            request.addOption("--no-warnings");
            request.addOption("--socket-timeout", 15);
            request.addOption("--retries", 2);
            String result = NativeRuntime.youtube(context, request, id, null).getOut();
            JSONObject list = new JSONObject(result.trim());
            LinkedHashMap<String, String> videos = playlistVideos(list);
            if (videos.isEmpty()) throw new IOException("No available videos found in this playlist.");
            String title = list.isNull("title") ? "YouTube playlist" : list.optString("title");
            queue.expandPlaylist(id, title, videos);
            refresh();
        } catch (Exception error) {
            queue.playlistFailed(id, "Playlist: " + DownloadService.readableError(error));
        } finally {
            loadingPlaylists.remove(id);
            if (queue.needsPlaylist(id)) refresh();
        }
    }

    static LinkedHashMap<String, String> playlistVideos(JSONObject playlist) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        JSONArray entries = playlist.optJSONArray("entries");
        if (entries == null) return result;
        for (int position = 0; position < entries.length(); position++) {
            JSONObject entry = entries.optJSONObject(position);
            if (entry == null) continue;
            String id = entry.optString("id");
            if (!YouTubeLinks.isVideo(id) || "private".equals(entry.optString("availability"))) continue;
            String title = entry.isNull("title") ? "" : entry.optString("title");
            if ("[Private video]".equals(title) || "[Deleted video]".equals(title)) continue;
            result.putIfAbsent(id, title);
        }
        return result;
    }
}
