package com.warrenstack.nightaudio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class QueueStore {
    enum Status { QUEUED, WORKING, DONE, FAILED }

    record Job(String id, String workKey) {}

    record Item(String id, String title, Status status, String detail, int progress,
                String uri, String mime, int chapters, long bytes, String workKey) {
        static Item queued(String id) {
            boolean playlist = YouTubeLinks.isPlaylist(id);
            return new Item(id, playlist ? "YouTube playlist" : "", Status.QUEUED,
                    playlist ? "Reading playlist…" : "", -1, "", "", 0, 0, WorkFiles.newKey(id));
        }

        boolean hasTitle() {
            return !title.isEmpty();
        }

        Item withTitle(String label) {
            return new Item(id, label, status, detail, progress, uri, mime, chapters, bytes, workKey);
        }
    }

    private static QueueStore instance;
    private final SharedPreferences preferences;
    private final WorkFiles workFiles;
    private final HashSet<String> uncommitted = new HashSet<>();
    private final boolean queueReadSuccessfully;
    private final LinkedHashMap<String, Item> items = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private boolean active;
    private String notice = "";

    static synchronized QueueStore get(Context context) {
        if (instance == null) instance = new QueueStore(context.getApplicationContext());
        return instance;
    }

    private QueueStore(Context context) {
        preferences = context.getSharedPreferences("night_audio_queue", Context.MODE_PRIVATE);
        workFiles = new WorkFiles(context);
        boolean readSuccessfully = false;
        try {
            JSONArray saved = new JSONArray(preferences.getString("items", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject json = saved.getJSONObject(i);
                String id = json.getString("id");
                if (!YouTubeLinks.isVideo(id) && !YouTubeLinks.isPlaylist(id)) continue;
                Status status = Status.valueOf(json.getString("status"));
                String detail = json.optString("detail");
                if (status == Status.WORKING) {
                    status = Status.QUEUED;
                    detail = "Ready to resume";
                }
                String title = json.optString("title");
                if ("YouTube video".equals(title) && status != Status.DONE) title = "";
                String workKey = json.optString("workKey", id);
                if (!WorkFiles.videoId(workKey).equals(id)) workKey = YouTubeLinks.isVideo(id) ? id : "";
                items.put(id, new Item(id, title, status, detail, -1,
                        json.optString("uri"), json.optString("mime"), json.optInt("chapters"),
                        json.optLong("bytes"), workKey));
            }
            readSuccessfully = true;
        } catch (JSONException | IllegalArgumentException ignored) {}
        queueReadSuccessfully = readSuccessfully;
    }

    synchronized List<Item> snapshot() {
        return new ArrayList<>(items.values());
    }

    synchronized List<Job> pending() {
        List<Job> jobs = new ArrayList<>();
        for (Item item : items.values()) {
            if (item.status == Status.QUEUED && YouTubeLinks.isVideo(item.id)) {
                jobs.add(new Job(item.id, item.workKey));
            }
        }
        return jobs;
    }

    Path workDirectory(String workKey) {
        return workFiles.directory(workKey);
    }

    void cleanTemporaryFiles() {
        if (queueReadSuccessfully) workFiles.sweep(this::keepWorkingFiles);
    }

    private synchronized boolean keepWorkingFiles(String key) {
        if (uncommitted.contains(key) && !save(true)) return true;
        Item item = items.get(WorkFiles.videoId(key));
        return item != null && item.workKey.equals(key) && item.status != Status.DONE;
    }

    synchronized int add(LinkedHashSet<String> ids) {
        int count = 0;
        for (String id : ids) {
            if (items.containsKey(id)) continue;
            items.put(id, Item.queued(id));
            count++;
        }
        if (count > 0) changed(true);
        return count;
    }

    synchronized void remove(String id) {
        Item item = items.get(id);
        if (item != null && item.status != Status.WORKING) {
            items.remove(id);
            uncommitted.remove(item.workKey);
            changed(true);
            workFiles.discard(item.workKey);
        }
    }

    synchronized void clearFinished() {
        if (items.values().removeIf(item -> item.status == Status.DONE)) {
            changed(true);
            cleanTemporaryFiles();
        }
    }

    synchronized void retry(String id) {
        Item item = items.get(id);
        if (item != null && item.status == Status.FAILED) {
            String detail = YouTubeLinks.isPlaylist(id) ? "Reading playlist…" : "";
            items.put(id, new Item(id, item.title, Status.QUEUED, detail, -1, "", "", 0, 0, item.workKey));
            changed(true);
        }
    }

    synchronized void setTitle(String id, String title) {
        Item item = items.get(id);
        if (item == null || item.hasTitle() || title == null || title.trim().isEmpty()) return;
        items.put(id, item.withTitle(title));
        changed(true);
    }

    synchronized boolean needsTitle(String id) {
        Item item = items.get(id);
        return item != null && !item.hasTitle() && item.status == Status.QUEUED;
    }

    synchronized boolean needsPlaylist(String id) {
        Item item = items.get(id);
        return item != null && item.status == Status.QUEUED && YouTubeLinks.isPlaylist(id);
    }

    synchronized void expandPlaylist(String id, String title, LinkedHashMap<String, String> videos) {
        if (!needsPlaylist(id)) return;
        int added = 0;
        LinkedHashMap<String, Item> result = new LinkedHashMap<>();
        for (Item item : items.values()) {
            if (item.id.equals(id)) {
                for (var entry : videos.entrySet()) {
                    if (!items.containsKey(entry.getKey())) {
                        result.put(entry.getKey(), Item.queued(entry.getKey()).withTitle(entry.getValue()));
                        added++;
                    }
                }
            } else {
                String importedTitle = videos.get(item.id);
                result.put(item.id, !item.hasTitle() && importedTitle != null
                        ? item.withTitle(importedTitle) : item);
            }
        }
        items.clear();
        items.putAll(result);
        notice = title + " · " + added + (added == 1 ? " video added" : " videos added");
        changed(true);
    }

    synchronized void playlistFailed(String id, String detail) {
        if (!needsPlaylist(id)) return;
        Item item = items.get(id);
        items.put(id, new Item(id, item.title, Status.FAILED, detail, -1, "", "", 0, 0, item.workKey));
        changed(true);
    }

    synchronized boolean setActive(boolean value) {
        if (active == value) return false;
        active = value;
        changed(false);
        return true;
    }

    synchronized void note(String message) {
        notice = message;
        changed(false);
    }

    synchronized String notice() { return notice; }

    synchronized boolean isActive() {
        return active;
    }

    synchronized boolean begin(Job job) {
        Item item = items.get(job.id);
        if (item == null || item.status != Status.QUEUED || !item.workKey.equals(job.workKey)) return false;
        items.put(job.id, new Item(job.id, item.title, Status.WORKING, "Getting audio",
                -1, "", "", 0, 0, item.workKey));
        changed(true);
        return true;
    }

    synchronized void progress(String id, String title, String stage, int percent) {
        Item item = items.get(id);
        if (item == null || item.status != Status.WORKING) return;
        String label = title == null || title.trim().isEmpty() ? item.title : title;
        if (stage.equals(item.detail) && percent == item.progress && label.equals(item.title)) return;
        items.put(id, new Item(id, label, Status.WORKING, stage, percent, "", "", 0, 0, item.workKey));
        changed(!label.equals(item.title));
    }

    synchronized void finished(String id, String title, String uri, String mime, int chapters, long size) {
        Item item = items.get(id);
        if (item == null || item.status != Status.WORKING) return;
        items.put(id, new Item(id, title, Status.DONE, "", 100, uri, mime, chapters, size, item.workKey));
        if (save(true)) workFiles.discard(item.workKey);
        else uncommitted.add(item.workKey);
        changed(false);
    }

    synchronized void failed(String id, String detail, boolean stopped) {
        Item item = items.get(id);
        if (item == null || item.status != Status.WORKING) return;
        items.put(id, new Item(id, item.title, stopped ? Status.QUEUED : Status.FAILED,
                detail, -1, "", "", 0, 0, item.workKey));
        changed(true);
    }

    void listen(Runnable listener) { listeners.add(listener); }
    void unlisten(Runnable listener) { listeners.remove(listener); }

    private void changed(boolean persist) {
        if (persist) save(false);
        for (Runnable listener : listeners) listener.run();
    }

    private boolean save(boolean durable) {
        JSONArray array = new JSONArray();
        for (Item item : items.values()) {
            try {
                array.put(new JSONObject().put("id", item.id).put("title", item.title)
                        .put("status", item.status.name()).put("detail", item.detail)
                        .put("uri", item.uri).put("mime", item.mime).put("chapters", item.chapters)
                        .put("bytes", item.bytes).put("workKey", item.workKey));
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        SharedPreferences.Editor editor = preferences.edit().putString("items", array.toString());
        if (!durable) {
            editor.apply();
            return true;
        }
        boolean saved = editor.commit();
        if (saved) uncommitted.clear();
        return saved;
    }
}
