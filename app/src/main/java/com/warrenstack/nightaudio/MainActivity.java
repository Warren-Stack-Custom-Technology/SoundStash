package com.warrenstack.nightaudio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int OPEN_FILES = 102;
    private static final int NOTIFICATIONS = 103;
    private static final int DOWNLOAD_FOLDER = 104;
    private static final String DONATION_URL = "https://www.buymeacoffee.com/WarrenStack";
    private final ExecutorService importWorker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean refreshPosted = new AtomicBoolean();
    private final Runnable refreshQueue = () -> {
        refreshPosted.set(false);
        refresh();
    };
    private final Runnable listener = () -> {
        if (refreshPosted.compareAndSet(false, true)) main.postDelayed(refreshQueue, 125);
    };
    private QueueStore queue;
    private QueueMetadata metadata;
    private SharedPreferences settings;
    private Palette colors;
    private TextView message, queueCount, start, formatButton;
    private FrameLayout scene;
    private StarfieldView starfield;
    private QueueAdapter rows;
    private EditText input;
    private String importMessage = "";
    private String pendingAction;
    private boolean importing;

    private static final String[] THEMES = {
            "Dusk", "Black", "Ocean", "Forest", "Ember", "Rosewood", "Glacier", "Paper", "Daylight", "System"
    };

    private record Palette(int bg, int surface, int raised, int text, int muted,
                           int accent, int onAccent, int line, int warning, boolean light) {
        static Palette get(String theme, boolean night) {
            if ("System".equals(theme)) theme = night ? "Dusk" : "Daylight";
            return switch (theme) {
                case "Black" -> new Palette(0xff000000, 0xff111115, 0xff1d1b24, 0xfff3effa,
                        0xffb7afc4, 0xffe2cbff, 0xff22102f, 0xff332c3d, 0xffffc7a8, false);
                case "Ocean" -> new Palette(0xff0b1726, 0xff15263c, 0xff1e324b, 0xffedf6ff,
                        0xffa9c3dc, 0xff9bddff, 0xff0a2d43, 0xff334d67, 0xffffc9a5, false);
                case "Forest" -> new Palette(0xff101b16, 0xff192820, 0xff22352a, 0xffeff7ed,
                        0xffb3c8ba, 0xffb6e8b7, 0xff153521, 0xff344c3d, 0xffffc9a5, false);
                case "Ember" -> new Palette(0xff211510, 0xff2f2018, 0xff3c2920, 0xfffff2e6,
                        0xffd1b8a7, 0xffffc28a, 0xff472510, 0xff543b2e, 0xffffb1a8, false);
                case "Rosewood" -> new Palette(0xff21141d, 0xff32222e, 0xff402b3a, 0xffffeff9,
                        0xffd5b6c9, 0xffffbbdb, 0xff4c1b37, 0xff594052, 0xffffc39e, false);
                case "Glacier" -> new Palette(0xffedf6fa, 0xfffbfdff, 0xffdfedf5, 0xff1a3244,
                        0xff4e677b, 0xff1d5476, 0xffffffff, 0xffc8ddea, 0xff923f24, true);
                case "Paper" -> new Palette(0xfff5eddb, 0xfffffbf1, 0xffece0c6, 0xff33281b,
                        0xff665540, 0xff6c4524, 0xffffffff, 0xffe0d2b6, 0xff8d3826, true);
                case "Daylight" -> new Palette(0xfff6f3f9, 0xffffffff, 0xfff1eaf8, 0xff261e30,
                        0xff62576e, 0xff603d80, 0xffffffff, 0xffe6deec, 0xff8d3f22, true);
                default -> new Palette(0xff141220, 0xff201c30, 0xff292337, 0xfff4effb,
                        0xffbcb2cf, 0xffe1ccff, 0xff291637, 0xff3a324a, 0xffffc59e, false);
            };
        }
    }

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        queue = QueueStore.get(this);
        metadata = QueueMetadata.get(this);
        settings = getSharedPreferences("night_audio_settings", MODE_PRIVATE);
        if (savedState != null) pendingAction = savedState.getString("pendingAction");
        build(savedState == null ? "" : savedState.getString("draft", ""));
        if (savedState == null) handleShare(getIntent());
    }

    @Override protected void onStart() {
        super.onStart();
        queue.listen(listener);
        queue.cleanTemporaryFiles();
        metadata.refresh();
        refresh();
    }

    @Override protected void onStop() {
        queue.unlisten(listener);
        main.removeCallbacks(refreshQueue);
        refreshPosted.set(false);
        super.onStop();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShare(intent);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("draft", input.getText().toString());
        state.putString("pendingAction", pendingAction);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        importWorker.shutdown();
        super.onDestroy();
    }

    private void build(String draft) {
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        colors = Palette.get(settings.getString("theme", "Dusk"), night);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().getDecorView().setSystemUiVisibility(colors.light ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        LinearLayout root = column();
        scene = new FrameLayout(this);
        scene.setBackgroundColor(colors.bg);
        starfield = null;
        scene.addView(root, new FrameLayout.LayoutParams(-1, -1));
        updateStarfield();
        root.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                var system = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                var ime = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(system.left, system.top, system.right, Math.max(system.bottom, ime.bottom));
                return insets;
            });
        }

        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(20), dp(4), dp(20), dp(8));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_sound_stash);
        logo.setBackground(shape(colors.surface, 13, colors.line));
        logo.setClipToOutline(true);
        top.addView(logo, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = text(getString(R.string.app_name), 23, colors.text, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
        titleParams.leftMargin = dp(12);
        top.addView(title, titleParams);
        top.addView(button("Settings", false, this::settings));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setItemsCanFocus(true);
        LinearLayout body = column();
        body.setPadding(dp(20), dp(5), dp(20), dp(4));
        list.addHeaderView(body, null, false);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout card = card();
        LinearLayout cardHeader = row();
        cardHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView addTitle = text("Add links", 20, colors.text, true);
        cardHeader.addView(addTitle, new LinearLayout.LayoutParams(0, -2, 1));
        formatButton = button("", false, this::formatOptions);
        cardHeader.addView(formatButton);
        card.addView(cardHeader, new LinearLayout.LayoutParams(-1, -2));

        input = new EditText(this);
        input.setTextColor(colors.text);
        input.setHintTextColor(colors.muted);
        input.setHighlightColor(colors.accent & 0x66ffffff);
        input.setTextSize(16);
        input.setText(draft);
        input.setHint("Paste videos, playlists, or the whole note");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(3);
        input.setMaxLines(5);
        input.setPadding(dp(12), dp(11), dp(12), dp(11));
        input.setBackground(shape(colors.bg, 14, colors.line));
        add(card, input, 10);

        LinearLayout importButtons = row();
        importButtons.addView(button("Paste", false, this::paste), weighted(6));
        importButtons.addView(button("Import files", false, this::pickFiles), weighted(6));
        importButtons.addView(button("Add", true, this::addInput), weighted(0));
        add(card, importButtons, 10);
        body.addView(card, new LinearLayout.LayoutParams(-1, -2));

        message = text("", 14, colors.muted, false);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(body, message, 10);

        LinearLayout queueHeader = row();
        queueHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView queueTitle = text("Queue", 23, colors.text, true);
        queueHeader.addView(queueTitle, new LinearLayout.LayoutParams(0, -2, 1));
        queueCount = text("", 14, colors.muted, true);
        queueHeader.addView(queueCount);
        add(body, queueHeader, 15);

        rows = new QueueAdapter();
        list.setAdapter(rows);

        LinearLayout footer = column();
        footer.setPadding(dp(20), dp(8), dp(20), dp(12));
        start = button("Download", true, this::startOrStop);
        footer.addView(start, new LinearLayout.LayoutParams(-1, -2));
        root.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        setContentView(scene);
        root.requestFocus();
        root.requestApplyInsets();
        refresh();
    }

    private void refresh() {
        if (isDestroyed() || rows == null) return;
        List<QueueStore.Item> items = queue.snapshot();
        long pending = 0;
        int videos = 0, playlists = 0;
        boolean resolving = false;
        for (QueueStore.Item item : items) {
            if (YouTubeLinks.isPlaylist(item.id())) {
                playlists++;
                if (item.status() == QueueStore.Status.QUEUED) resolving = true;
            } else {
                videos++;
                if (item.status() == QueueStore.Status.QUEUED) pending++;
            }
        }
        boolean active = queue.isActive();
        String count = videos == 0 ? "" : videos + (videos == 1 ? " video" : " videos");
        if (playlists > 0) count += (count.isEmpty() ? "" : " · ")
                + playlists + (playlists == 1 ? " playlist" : " playlists");
        queueCount.setText(count);
        String notice = queue.notice();
        String status = (active && !notice.trim().isEmpty()) || DownloadService.HEAT_PAUSE.equals(notice)
                ? notice : importing ? "Reading files…" : !importMessage.trim().isEmpty() ? importMessage : notice;
        message.setText(status);
        message.setVisibility(status.trim().isEmpty() ? View.GONE : View.VISIBLE);
        formatButton.setText(format().label);
        boolean updating = active && queue.notice().equals("Updating YouTube engine…");
        start.setText(updating ? "Updating engine…" : active ? "Stop queue"
                : resolving ? "Reading playlists…" : pending > 0
                ? "Download " + pending + (pending == 1 ? " video" : " videos") : "Download");
        start.setEnabled(!updating && (active || (!resolving && pending > 0)));
        start.setAlpha(start.isEnabled() ? 1f : 0.43f);
        rows.show(items);
    }

    private final class QueueAdapter extends BaseAdapter {
        private List<QueueStore.Item> items = java.util.Collections.emptyList();

        void show(List<QueueStore.Item> next) {
            if (next.equals(items)) return;
            items = next;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return Math.max(1, items.size()); }
        @Override public QueueStore.Item getItem(int position) { return items.isEmpty() ? null : items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean isEnabled(int position) { return false; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            QueueStore.Item item = getItem(position);
            if (recycled != null && java.util.Objects.equals(recycled.getTag(), item)) return recycled;
            FrameLayout wrapper = recycled == null ? new FrameLayout(MainActivity.this) : (FrameLayout) recycled;
            wrapper.setPadding(dp(20), dp(4), dp(20), dp(4));
            wrapper.removeAllViews();
            if (item == null) {
                LinearLayout empty = card();
                empty.addView(text("Your queue is empty", 17, colors.text, true));
                add(empty, text("Paste a list, import a file, or share links from YouTube or Samsung Notes.",
                        14, colors.muted, false), 5);
                wrapper.addView(empty);
            } else {
                wrapper.addView(queueRow(item));
            }
            wrapper.setTag(item);
            return wrapper;
        }
    }

    private LinearLayout queueRow(QueueStore.Item item) {
        LinearLayout card = card();
        String title = item.hasTitle() ? item.title() : "youtu.be/" + item.id();
        TextView name = text(title, 16, colors.text, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(name);

        String status = switch (item.status()) {
            case QUEUED -> item.detail().trim().isEmpty() ? "Ready to download" : item.detail();
            case WORKING -> item.detail() + (item.progress() >= 0 ? " · " + item.progress() + "%" : "");
            case FAILED -> item.detail();
            case DONE -> (item.chapters() == 0 ? "No chapters found"
                    : item.chapters() + (item.chapters() == 1 ? " chapter" : " chapters"))
                    + " · " + android.text.format.Formatter.formatShortFileSize(this, item.bytes());
        };
        add(card, text(status, 14, item.status() == QueueStore.Status.FAILED ? colors.warning : colors.muted, false), 4);
        if (item.status() == QueueStore.Status.WORKING) {
            ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setIndeterminate(item.progress() < 0);
            progress.setProgress(Math.max(0, item.progress()));
            ColorStateList tint = ColorStateList.valueOf(colors.accent);
            progress.setIndeterminateTintList(tint);
            progress.setProgressTintList(tint);
            add(card, progress, 7);
        } else {
            LinearLayout controls = row();
            if (item.status() == QueueStore.Status.DONE) {
                controls.addView(button("Play", false, () -> play(item)));
                controls.addView(button("Share", false, () -> share(item)));
            }
            if (item.status() == QueueStore.Status.FAILED) controls.addView(button("Retry", false, () -> {
                importMessage = "";
                queue.retry(item.id());
                metadata.refresh();
            }));
            controls.addView(button("Remove", false, () -> queue.remove(item.id())));
            add(card, controls, 6);
        }
        return card;
    }

    private void paste() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            report("The clipboard is empty.");
            return;
        }
        CharSequence content = clip.getItemAt(0).coerceToStyledText(this);
        if (content != null) addExtracted(Imports.text(content), QueueStore.get(this));
    }

    private void addInput() {
        if (addExtracted(Imports.text(input.getText()), queue)) input.setText("");
    }

    private boolean addExtracted(LinkedHashSet<String> ids, QueueStore store) {
        int added = store.add(ids);
        boolean includesPlaylist = ids.stream().anyMatch(YouTubeLinks::isPlaylist);
        if (ids.isEmpty()) report("No YouTube links found.");
        else if (added == 0) report("Those links are already in the queue.");
        else if (includesPlaylist) report("");
        else {
            int duplicate = ids.size() - added;
            report(added + (added == 1 ? " video added" : " videos added")
                    + (duplicate == 0 ? "." : " · " + duplicate + " already queued"));
        }
        metadata.refresh();
        return !ids.isEmpty();
    }

    private void report(String status) {
        importMessage = status;
        refresh();
    }

    private void handleShare(Intent intent) {
        if (intent == null || (!Intent.ACTION_SEND.equals(intent.getAction())
                && !Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction()))) return;
        LinkedHashSet<String> ids = Imports.text(intent.getCharSequenceExtra(Intent.EXTRA_TEXT));
        String html = intent.getStringExtra(Intent.EXTRA_HTML_TEXT);
        if (html != null) {
            ids.addAll(Imports.text(android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY)));
        }
        LinkedHashSet<Uri> files = new LinkedHashSet<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> values = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (values != null) files.addAll(values);
        } else {
            Uri one = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (one != null) files.add(one);
        }
        ClipData clip = intent.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            if (item.getUri() != null) files.add(item.getUri());
            ids.addAll(Imports.text(item.getText()));
        }
        if (files.isEmpty()) addExtracted(ids, queue);
        else importFiles(files, ids);
    }

    private void pickFiles() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/*", "application/pdf", "application/octet-stream",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation"});
        startActivityForResult(picker, OPEN_FILES);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;
        if (request == DOWNLOAD_FOLDER) {
            rememberDownloadFolder(data);
            return;
        }
        if (request != OPEN_FILES) return;
        LinkedHashSet<Uri> files = new LinkedHashSet<>();
        if (data.getData() != null) files.add(data.getData());
        ClipData clip = data.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) files.add(clip.getItemAt(i).getUri());
        if (!files.isEmpty()) importFiles(files, new LinkedHashSet<>());
    }

    private void rememberDownloadFolder(Intent result) {
        Uri tree = result.getData();
        if (tree == null || !DocumentsContract.isTreeUri(tree)
                || (result.getFlags() & DownloadFolder.ACCESS) != DownloadFolder.ACCESS) {
            report("Choose a folder that allows saving files.");
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(tree, DownloadFolder.ACCESS);
            DownloadFolder.remember(settings, tree, "Selected folder");
        } catch (SecurityException error) {
            report("Android couldn't save access to that folder.");
            return;
        }
        importWorker.execute(() -> {
            String label = DownloadFolder.displayName(getContentResolver(), tree);
            runOnUiThread(() -> {
                if (isDestroyed() || !tree.equals(DownloadFolder.selected(settings).tree())) return;
                DownloadFolder.remember(settings, tree, label);
                report("Download folder set to " + label + ".");
            });
        });
    }

    private void importFiles(LinkedHashSet<Uri> files, LinkedHashSet<String> ids) {
        importing = true;
        refresh();
        Context context = getApplicationContext();
        importWorker.execute(() -> {
            ArrayList<String> errors = new ArrayList<>();
            for (Uri uri : files) {
                if (uri == null || !"content".equals(uri.getScheme())) continue;
                try {
                    ids.addAll(Imports.file(context, uri));
                } catch (Exception error) {
                    errors.add(DownloadService.readableError(error));
                }
            }
            int added = queue.add(ids);
            boolean includesPlaylist = ids.stream().anyMatch(YouTubeLinks::isPlaylist);
            metadata.refresh();
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                importing = false;
                if (errors.isEmpty()) {
                    if (ids.isEmpty()) report("No YouTube links found. For a scanned PDF, paste or share its URLs as text.");
                    else if (added == 0) report("Those links are already in the queue.");
                    else if (includesPlaylist) report("");
                    else report(added + (added == 1 ? " video imported." : " videos imported."));
                } else {
                    report((added > 0 ? added + " imported. " : "") + errors.get(0));
                }
            });
        });
    }

    private void play(QueueStore.Item item) {
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.uri()), item.mime())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            try {
                startActivity(new Intent(intent).setPackage("org.videolan.vlc"));
            } catch (android.content.ActivityNotFoundException unavailable) {
                startActivity(Intent.createChooser(intent, "Play audio"));
            }
        } catch (android.content.ActivityNotFoundException unavailable) {
            report("Open the saved audio with a media player.");
        } catch (SecurityException error) {
            report("Android couldn't open this file. Open it from the folder where it was saved.");
        }
    }

    private void share(QueueStore.Item item) {
        Uri uri = Uri.parse(item.uri());
        Intent send = new Intent(Intent.ACTION_SEND).setType(item.mime()).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(item.title(), uri));
        try { startActivity(Intent.createChooser(send, "Share audio")); }
        catch (SecurityException error) { report("Share the audio from the folder where it was saved."); }
    }

    private void startOrStop() {
        if (queue.isActive()) {
            startService(new Intent(this, DownloadService.class).setAction(DownloadService.CANCEL));
            return;
        }
        if (queue.pending().isEmpty()) return;
        startJob(DownloadService.RUN);
    }

    private void startJob(String action) {
        if (queue.isActive()) return;
        importMessage = "";
        queue.note("");
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED && !settings.getBoolean("asked_notifications", false)) {
            pendingAction = action;
            settings.edit().putBoolean("asked_notifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
        } else {
            launchJob(action);
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] result) {
        super.onRequestPermissionsResult(request, permissions, result);
        if (request == NOTIFICATIONS && pendingAction != null) {
            String action = pendingAction;
            pendingAction = null;
            launchJob(action);
        }
    }

    private void launchJob(String action) {
        try {
            startForegroundService(new Intent(this, DownloadService.class).setAction(action));
        } catch (RuntimeException error) {
            report(DownloadService.readableError(error));
        }
    }

    private record Setting(String label, String value, Runnable action) {}

    private void settings() {
        LinearLayout panel = column();
        panel.setBackground(shape(colors.bg, 22, colors.line));
        panel.setPadding(dp(16), dp(10), dp(16), dp(12));
        AlertDialog menu = dialog().setView(panel).create();

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Settings", 23, colors.text, true);
        title.setAccessibilityHeading(true);
        header.addView(title, weighted(12));
        header.addView(button("Done", false, menu::dismiss));
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout sections = column();
        sections.setPadding(0, dp(4), 0, dp(4));
        settingsSection(sections, menu, "Appearance",
                new Setting("Theme", settings.getString("theme", "Dusk"), this::themeOptions),
                new Setting("Twinkling background", settings.getBoolean(StarfieldView.KEY, false) ? "On" : "Off",
                        this::twinkleOptions));
        settingsSection(sections, menu, "Audio and storage",
                new Setting("Audio format", format().label, this::formatOptions),
                new Setting("Chapter export", settings.getBoolean("split", false) ? "Separate tracks" : "One file",
                        this::chapterOptions),
                new Setting("Download folder", DownloadFolder.selected(settings).label(), this::downloadFolderOptions));
        settingsSection(sections, menu, "Queue and engine",
                new Setting("Workload", workload().percent + "%", this::workloadOptions),
                new Setting("Update YouTube engine", "", () -> {
                    if (queue.isActive()) report("Stop or finish the current batch before updating the engine.");
                    else startJob(DownloadService.UPDATE);
                }),
                new Setting("Clear finished from queue", "", queue::clearFinished));
        settingsSection(sections, menu, "Support",
                new Setting("Buy Me a Coffee", "Optional donation", this::openDonation));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(sections, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1));
        menu.show();
        menu.getWindow().setTitle("Settings");
        menu.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }

    private void settingsSection(LinearLayout sections, AlertDialog menu, String label, Setting... settings) {
        TextView heading = text(label, 13, colors.accent, true);
        heading.setAccessibilityHeading(true);
        heading.setPadding(dp(2), 0, 0, dp(6));
        add(sections, heading, 12);

        LinearLayout group = column();
        group.setBackground(shape(colors.surface, 14, colors.line));
        group.setClipToOutline(true);
        for (int i = 0; i < settings.length; i++) {
            if (i > 0) {
                View divider = new View(this);
                divider.setBackgroundColor(colors.line);
                LinearLayout.LayoutParams dividerSize = new LinearLayout.LayoutParams(-1, dp(1));
                dividerSize.setMargins(dp(12), 0, dp(12), 0);
                group.addView(divider, dividerSize);
            }
            group.addView(settingRow(menu, settings[i]), new LinearLayout.LayoutParams(-1, -2));
        }
        sections.addView(group, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout settingRow(AlertDialog menu, Setting setting) {
        LinearLayout item = row();
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setMinimumHeight(dp(48));
        item.setPadding(dp(12), dp(9), dp(12), dp(9));
        item.setBackground(new RippleDrawable(ColorStateList.valueOf(colors.accent & 0x22ffffff), null,
                new ColorDrawable(Color.WHITE)));
        item.setFocusable(true);
        item.setScreenReaderFocusable(true);
        item.setOnClickListener(view -> {
            menu.dismiss();
            setting.action().run();
        });

        LinearLayout labels = column();
        labels.addView(text(setting.label(), 15, colors.text, true));
        if (!setting.value().isEmpty()) labels.addView(text(setting.value(), 13, colors.muted, false));
        item.addView(labels, weighted(12));
        TextView arrow = text("›", 22, colors.accent, false);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(arrow);
        return item;
    }

    private void twinkleOptions() {
        dialog().setTitle("Twinkling background").setSingleChoiceItems(new String[]{"Off", "On"},
                settings.getBoolean(StarfieldView.KEY, false) ? 1 : 0, (dialog, choice) -> {
                    settings.edit().putBoolean(StarfieldView.KEY, choice == 1).apply();
                    updateStarfield();
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }

    private void updateStarfield() {
        boolean enabled = settings.getBoolean(StarfieldView.KEY, false);
        if (enabled && starfield == null) {
            int tint = colors.light ? colors.muted : colors.accent;
            starfield = new StarfieldView(this, colors.light, tint);
            scene.addView(starfield, 0, new FrameLayout.LayoutParams(-1, -1));
        } else if (!enabled && starfield != null) {
            scene.removeView(starfield);
            starfield = null;
        }
    }

    private void downloadFolderOptions() {
        dialog().setTitle("Download folder for the next batch")
                .setItems(new String[]{"Choose folder…", "Use " + DownloadFolder.DEFAULT_LABEL}, (dialog, choice) -> {
                    if (choice == 0) {
                        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                                DownloadFolder.ACCESS | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                        try { startActivityForResult(picker, DOWNLOAD_FOLDER); }
                        catch (android.content.ActivityNotFoundException error) {
                            report("The Android folder picker is unavailable.");
                        }
                    } else {
                        DownloadFolder.useDefault(settings);
                        report("Download folder set to " + DownloadFolder.DEFAULT_LABEL + ".");
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void openDonation() {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(DONATION_URL))); }
        catch (android.content.ActivityNotFoundException error) { report(DONATION_URL); }
    }

    private void themeOptions() {
        int current = java.util.Arrays.asList(THEMES).indexOf(settings.getString("theme", "Dusk"));
        dialog().setTitle("Appearance").setSingleChoiceItems(THEMES, current, (dialog, choice) -> {
            settings.edit().putString("theme", THEMES[choice]).apply();
            dialog.dismiss();
            build(input.getText().toString());
        }).setNegativeButton("Cancel", null).show();
    }

    private Workload workload() {
        return Workload.fromPercent(settings.getInt(Workload.KEY, Workload.DEFAULT_PERCENT));
    }

    private void workloadOptions() {
        Workload[] levels = Workload.values();
        String[] labels = new String[levels.length];
        for (int i = 0; i < levels.length; i++) {
            labels[i] = levels[i].percent + "% · " + levels[i].description;
        }
        dialog().setTitle("App workload for the next batch")
                .setSingleChoiceItems(labels, workload().ordinal(), (dialog, choice) -> {
                    settings.edit().putInt(Workload.KEY, levels[choice].percent).apply();
                    if (queue.isActive()) {
                        android.widget.Toast.makeText(this, "Stop and restart the queue to apply the new workload.",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }

    private void formatOptions() {
        String[] formats = {"Opus · 24 kbps", "M4B · AAC 32 kbps"};
        dialog().setTitle("Audio format").setSingleChoiceItems(formats,
                format() == AudioEngine.Format.OPUS ? 0 : 1, (dialog, choice) -> {
            settings.edit().putString("format", choice == 0 ? "OPUS" : "M4B").apply();
            dialog.dismiss();
            refresh();
        }).setNegativeButton("Cancel", null).show();
    }

    private void chapterOptions() {
        String[] options = {"One file with named chapter markers", "Separate numbered tracks"};
        dialog().setTitle("Chapters").setSingleChoiceItems(options,
                settings.getBoolean("split", false) ? 1 : 0, (dialog, choice) -> {
            settings.edit().putBoolean("split", choice == 1).apply();
            dialog.dismiss();
            refresh();
        }).setNegativeButton("Cancel", null).show();
    }

    private AudioEngine.Format format() {
        return "M4B".equals(settings.getString("format", "OPUS")) ? AudioEngine.Format.M4B : AudioEngine.Format.OPUS;
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, colors.light
                ? android.R.style.Theme_Material_Light_Dialog_Alert : android.R.style.Theme_Material_Dialog_Alert);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private LinearLayout card() {
        LinearLayout box = column();
        box.setBackground(shape(colors.surface, 19, colors.line));
        box.setPadding(dp(13), dp(11), dp(13), dp(11));
        return box;
    }

    private TextView text(String label, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private TextView button(String label, boolean primary, Runnable click) {
        TextView button = text(label, 14, primary ? colors.onAccent : colors.accent, true);
        button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Button.class.getName());
            }
        });
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setMinWidth(dp(48));
        button.setPadding(dp(11), dp(4), dp(11), dp(4));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x22000000 : 0x20ffffff),
                shape(primary ? colors.accent : colors.raised, 13, primary ? 0 : colors.line), null));
        button.setOnClickListener(v -> click.run());
        button.setFocusable(true);
        return button;
    }

    private GradientDrawable shape(int fill, int radius, int border) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        if (border != 0) shape.setStroke(dp(1), border);
        return shape;
    }

    private LinearLayout.LayoutParams weighted(int right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.rightMargin = dp(right);
        return params;
    }

    private void add(LinearLayout layout, View view, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        layout.addView(view, params);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
