package com.warrenstack.nightaudio;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

final class AppUpdates {
    private static final String REPOSITORY = "Warren-Stack-Custom-Technology/SoundStash";
    private static final String LATEST = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    private static final String APK_NAME = "SoundStash.apk";
    private static final Pattern VERSION = Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final int MAX_RELEASE_BYTES = 2 * 1024 * 1024;

    record Release(String tag, String version, long size) {}

    private record Version(int major, int minor, int patch) implements Comparable<Version> {
        static Version parse(String value) {
            Matcher match = VERSION.matcher(value);
            if (!match.matches()) return null;
            try {
                return new Version(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)),
                        Integer.parseInt(match.group(3)));
            } catch (NumberFormatException error) {
                return null;
            }
        }

        @Override public int compareTo(Version other) {
            if (major != other.major) return Integer.compare(major, other.major);
            if (minor != other.minor) return Integer.compare(minor, other.minor);
            return Integer.compare(patch, other.patch);
        }
    }

    static String installedVersion(Context context) {
        return installedInfo(context, 0).versionName;
    }

    static Release latest(Context context) throws IOException {
        HttpsURLConnection connection = open(LATEST, "application/vnd.github+json");
        try {
            int status = connection.getResponseCode();
            if (status == 404) return null;
            if (status != 200) throw new IOException("GitHub returned HTTP " + status + ".");
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                copy(input, body, MAX_RELEASE_BYTES);
                return readRelease(new JSONObject(body.toString(StandardCharsets.UTF_8.name())),
                        installedVersion(context));
            } catch (JSONException error) {
                throw new IOException("GitHub returned unreadable release details.", error);
            }
        } finally {
            connection.disconnect();
        }
    }

    static Release readRelease(JSONObject details, String installed) throws IOException, JSONException {
        if (details.optBoolean("draft") || details.optBoolean("prerelease")) return null;
        String tag = details.getString("tag_name");
        Version latest = Version.parse(tag);
        Version current = Version.parse(installed);
        if (latest == null || current == null) throw new IOException("Couldn't compare app versions.");
        if (latest.compareTo(current) <= 0) return null;

        JSONArray assets = details.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (!APK_NAME.equals(asset.optString("name")) || !"uploaded".equals(asset.optString("state"))) continue;
            long size = asset.getLong("size");
            if (size > 0) return new Release(tag, tag.startsWith("v") ? tag.substring(1) : tag, size);
        }
        throw new IOException("The GitHub release for " + tag + " is missing " + APK_NAME + ".");
    }

    static File apkFile(Context context) {
        return new File(new File(context.getCacheDir(), "app-update"), APK_NAME);
    }

    static void download(Context context, Release release) throws IOException {
        File apk = apkFile(context);
        File directory = apk.getParentFile();
        Files.createDirectories(directory.toPath());
        File temporary = File.createTempFile("download-", ".apk", directory);
        try {
            String url = "https://github.com/" + REPOSITORY + "/releases/download/" + release.tag() + "/" + APK_NAME;
            HttpsURLConnection connection = open(url, "application/octet-stream");
            try {
                int status = connection.getResponseCode();
                if (status != 200) throw new IOException("GitHub returned HTTP " + status + ".");
                try (InputStream input = connection.getInputStream();
                     OutputStream output = Files.newOutputStream(temporary.toPath())) {
                    if (copy(input, output, release.size()) != release.size()) {
                        throw new IOException("The APK download was incomplete.");
                    }
                }
            } finally {
                connection.disconnect();
            }
            verify(context, temporary, release.version());
            Files.move(temporary.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private static void verify(Context context, File file, String version) throws IOException {
        PackageInfo installed = installedInfo(context, PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(),
                PackageManager.GET_SIGNING_CERTIFICATES);
        if (archive == null || !installed.packageName.equals(archive.packageName)
                || !version.equals(archive.versionName) || archive.getLongVersionCode() <= installed.getLongVersionCode()) {
            throw new IOException("The release APK is not a newer copy of Sound Stash with version " + version + ".");
        }
        Signature[] signers = installed.signingInfo == null ? null : installed.signingInfo.getApkContentsSigners();
        if (signers == null || signers.length == 0 || archive.signingInfo == null
                || !Arrays.equals(signers, archive.signingInfo.getApkContentsSigners())) {
            throw new IOException("The release APK uses a different signing key than this installation.");
        }
    }

    private static PackageInfo installedInfo(Context context, int flags) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), flags);
        } catch (PackageManager.NameNotFoundException error) {
            throw new IllegalStateException("Couldn't read the installed app version.", error);
        }
    }

    private static HttpsURLConnection open(String url, String accept) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("User-Agent", "SoundStash-Android");
        return connection;
    }

    private static long copy(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("The update was interrupted.");
            total += count;
            if (total > limit) throw new IOException("GitHub returned more data than expected.");
            output.write(buffer, 0, count);
        }
        return total;
    }
}
