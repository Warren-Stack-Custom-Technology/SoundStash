package com.warrenstack.nightaudio;

import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class YouTubeLinks {
    private static final String VIDEO = "[A-Za-z0-9_-]{11}";
    private static final String PLAYLIST = "[A-Za-z0-9_-]{2,128}";
    private static final String PREFIX = "playlist:";
    private static final Pattern VIDEO_ID = Pattern.compile(VIDEO);
    private static final Pattern PLAYLIST_ID = Pattern.compile(PLAYLIST);
    private static final String START = "(?<![A-Za-z0-9_.@/-])(?:https?://)?";
    private static final String YOUTUBE = "(?:www\\.|m\\.|music\\.)?youtube\\.com/";
    private static final String QUERY = "(?:[^\\s<>,\"'\\[\\]{}()/?#&]*&)*";
    private static final Pattern LINK = Pattern.compile(
            START + "(?:(?:www\\.)?youtu\\.be/|" + YOUTUBE
            + "(?:shorts/|live/|embed/|v/)|(?:www\\.)?youtube-nocookie\\.com/embed/)"
            + "(" + VIDEO + ")(?![A-Za-z0-9_-])"
            + "|" + START + YOUTUBE + "watch\\?" + QUERY + "v=(" + VIDEO + ")(?![A-Za-z0-9_-])"
            + "|" + START + YOUTUBE + "(?:playlist|watch)\\?" + QUERY
            + "list=(" + PLAYLIST + ")(?![A-Za-z0-9_-])",
            Pattern.CASE_INSENSITIVE);

    static LinkedHashSet<String> extract(CharSequence text) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (text == null) return ids;
        Matcher matcher = LINK.matcher(text.toString().replace("&amp;", "&"));
        while (matcher.find()) {
            if (matcher.group(1) != null) ids.add(matcher.group(1));
            else if (matcher.group(2) != null) ids.add(matcher.group(2));
            else ids.add(PREFIX + matcher.group(3));
        }
        return ids;
    }

    static boolean isVideo(String id) {
        return VIDEO_ID.matcher(id).matches();
    }

    static boolean isPlaylist(String id) {
        return id.startsWith(PREFIX) && PLAYLIST_ID.matcher(id.substring(PREFIX.length())).matches();
    }

    static String url(String id) {
        return isPlaylist(id) ? "https://www.youtube.com/playlist?list=" + id.substring(PREFIX.length())
                : "https://www.youtube.com/watch?v=" + id;
    }

    private YouTubeLinks() {}
}
