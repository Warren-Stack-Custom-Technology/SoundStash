package com.warrenstack.nightaudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class Chapters {
    record Chapter(long start, long end, String title) {}

    static List<Chapter> fromYtDlp(JSONObject info) throws IOException {
        JSONArray entries = info.optJSONArray("chapters");
        List<Chapter> result = new ArrayList<>();
        if (entries == null) return result;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) throw new IOException("YouTube returned unreadable chapter data.");
            double start = entry.optDouble("start_time", -1);
            double end = entry.optDouble("end_time", -1);
            if (!Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end <= start) {
                throw new IOException("YouTube returned a chapter with invalid timestamps.");
            }
            long startMs = Math.round(start * 1000);
            long endMs = Math.round(end * 1000);
            if (!result.isEmpty() && startMs <= result.get(result.size() - 1).start()) {
                throw new IOException("YouTube returned chapters out of order.");
            }
            String title = entry.optString("title", "");
            if (title.trim().isEmpty() || "null".equals(title)) title = "Chapter " + (i + 1);
            result.add(new Chapter(startMs, endMs, title.replace('\u0000', ' ')));
        }
        return result;
    }

    static String write(List<Chapter> chapters) {
        StringBuilder text = new StringBuilder(";FFMETADATA1\n");
        for (Chapter chapter : chapters) {
            text.append("[CHAPTER]\nTIMEBASE=1/1000\nSTART=").append(chapter.start())
                    .append("\nEND=").append(chapter.end()).append("\ntitle=")
                    .append(escape(chapter.title())).append('\n');
        }
        return text.toString();
    }

    static void verify(List<Chapter> expected, JSONArray actual) throws IOException {
        if (expected.size() != actual.length()) throw new IOException("The exported chapter count did not match YouTube.");
        for (int i = 0; i < expected.size(); i++) {
            Chapter wanted = expected.get(i);
            JSONObject read = actual.optJSONObject(i);
            JSONObject tags = read == null ? null : read.optJSONObject("tags");
            String title = tags == null ? "" : tags.optString("title");
            double start = read == null ? Double.NaN : read.optDouble("start_time", Double.NaN);
            if (!wanted.title().equals(title) || !Double.isFinite(start)
                    || Math.abs(wanted.start() - Math.round(start * 1000)) > 1) {
                throw new IOException("The export did not preserve chapter " + (i + 1) + ".");
            }
        }
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("=", "\\=").replace("#", "\\#")
                .replace(";", "\\;").replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\\\n");
    }

    private Chapters() {}
}
