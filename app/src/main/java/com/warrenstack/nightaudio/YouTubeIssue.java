package com.warrenstack.nightaudio;

import java.io.IOException;
import java.util.Locale;

final class YouTubeIssue extends IOException {
    final boolean pauseQueue;

    private YouTubeIssue(String message, boolean pauseQueue) {
        super(message);
        this.pauseQueue = pauseQueue;
    }

    static YouTubeIssue from(String message, boolean usingAccount) {
        if (message == null) return null;
        String text = message.toLowerCase(Locale.ROOT).replace('’', '\'');
        if (text.contains("confirm you're not a bot")) {
            return new YouTubeIssue("YouTube asked for bot verification. Open the video in YouTube, follow its prompt, then retry.", true);
        }
        if (text.contains("http error 429") || text.contains("too many requests")
                || text.contains("this content isn't available, try again later")) {
            return new YouTubeIssue("YouTube temporarily limited requests. Wait a while before tapping Download again.", true);
        }
        if (usingAccount && text.contains("account cookies are no longer valid")) {
            return new YouTubeIssue("Your YouTube session expired. Import fresh cookies in Settings → YouTube account.", true);
        }
        if (text.contains("confirm your age") || text.contains("verify your age") || text.contains("age-restricted")) {
            return new YouTubeIssue("YouTube requires age verification for this video. Verify your age on YouTube, "
                    + (usingAccount ? "then replace your cookies" : "then import your cookies") + " in Settings → YouTube account.", false);
        }
        return null;
    }
}
