package com.warrenstack.nightaudio;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

record DownloadFolder(Uri tree, String label) {
    private static final String URI_KEY = "download_tree";
    private static final String LABEL_KEY = "download_tree_name";
    static final String DEFAULT_NAME = "Sound Stash";
    static final String DEFAULT_LABEL = "Downloads / " + DEFAULT_NAME;
    static final String RESELECT = "Choose the download folder again in Settings.";
    static final int ACCESS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    static DownloadFolder selected(SharedPreferences preferences) {
        String value = preferences.getString(URI_KEY, "");
        return value.isEmpty() ? new DownloadFolder(null, DEFAULT_LABEL)
                : new DownloadFolder(Uri.parse(value), preferences.getString(LABEL_KEY, "Selected folder"));
    }

    static void useDefault(SharedPreferences preferences) {
        preferences.edit().remove(URI_KEY).remove(LABEL_KEY).apply();
    }

    static void remember(SharedPreferences preferences, Uri tree, String label) {
        preferences.edit().putString(URI_KEY, tree.toString()).putString(LABEL_KEY, label).apply();
    }

    boolean canWrite(Context context) {
        if (tree == null) return true;
        return context.getContentResolver().getPersistedUriPermissions().stream().anyMatch(
                grant -> tree.equals(grant.getUri()) && grant.isReadPermission() && grant.isWritePermission());
    }

    static String displayName(ContentResolver resolver, Uri tree) {
        try (var cursor = resolver.query(document(tree),
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) return name;
            }
        } catch (RuntimeException ignored) {}
        return "Selected folder";
    }

    private static Uri document(Uri tree) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
    }

    Uri publish(Context context, List<File> outputs, List<String> names, String mime, String subfolder,
                Runnable checkStop) throws IOException {
        if (!canWrite(context)) throw new IOException(RESELECT);
        ContentResolver resolver = context.getContentResolver();
        List<Uri> created = new ArrayList<>();
        try {
            Uri parent = document(tree);
            if (!subfolder.isEmpty()) {
                parent = create(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, subfolder);
                created.add(parent);
            }
            Uri first = null;
            byte[] buffer = new byte[64 * 1024];
            for (int i = 0; i < outputs.size(); i++) {
                checkStop.run();
                Uri entry = create(resolver, parent, mime, names.get(i));
                created.add(entry);
                if (first == null) first = entry;
                try (var out = resolver.openOutputStream(entry, "w");
                     var in = Files.newInputStream(outputs.get(i).toPath())) {
                    if (out == null) throw new IOException("The selected folder did not open the audio file.");
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        checkStop.run();
                        out.write(buffer, 0, count);
                    }
                }
            }
            checkStop.run();
            return first;
        } catch (IOException | RuntimeException error) {
            for (int i = created.size() - 1; i >= 0; i--) {
                try { DocumentsContract.deleteDocument(resolver, created.get(i)); }
                catch (IOException | RuntimeException ignored) {}
            }
            if (error instanceof SecurityException) throw new IOException(RESELECT, error);
            throw error;
        }
    }

    private static Uri create(ContentResolver resolver, Uri parent, String mime, String name) throws IOException {
        Uri created = DocumentsContract.createDocument(resolver, parent, mime, name);
        if (created == null) throw new IOException("Couldn't create a file in the selected download folder.");
        return created;
    }
}
