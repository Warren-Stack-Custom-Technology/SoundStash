package com.warrenstack.nightaudio;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class DownloadService extends Service {
    static final String RUN = "com.warrenstack.nightaudio.RUN";
    static final String CANCEL = "com.warrenstack.nightaudio.CANCEL";
    static final String UPDATE = "com.warrenstack.nightaudio.UPDATE";

    private static final String CHANNEL = "audio_downloads";
    private static final int NOTICE_ID = 41;
    private static final String BACKGROUND = "Downloads and encoding continue when you leave the app or lock the screen.";
    static final String HEAT_PAUSE = "Queue paused because the phone got hot. Let it cool, then tap Download.";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private QueueStore store;
    private NotificationManager notifications;
    private PowerManager power;
    private PowerManager.WakeLock wakeLock;
    private boolean listeningForHeat;
    private final PowerManager.OnThermalStatusChangedListener thermalListener = this::onThermalStatus;
    private volatile Batch batch;
    private volatile boolean destroyed;
    private boolean running;

    @Override public void onCreate() {
        super.onCreate();
        store = QueueStore.get(this);
        notifications = getSystemService(NotificationManager.class);
        power = getSystemService(PowerManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Audio downloads",
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        notifications.createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (CANCEL.equals(action)) {
            Batch active = batch;
            if (active != null) {
                if (active.cancel()) store.note("Stopping after safe cleanup…");
            } else if (!running) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        boolean update = UPDATE.equals(action);
        startForeground(NOTICE_ID, notification(update ? "Updating download engine" : "Preparing downloads",
                "", -1, !update));
        List<QueueStore.Job> jobs = store.pending();
        if (!update && RUN.equals(action) && !jobs.isEmpty()
                && power.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE) {
            store.note(HEAT_PAUSE);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        var options = getSharedPreferences("night_audio_settings", MODE_PRIVATE);
        DownloadFolder folder = DownloadFolder.selected(options);
        if (!update && RUN.equals(action) && !jobs.isEmpty() && !folder.canWrite(this)) {
            store.note(DownloadFolder.RESELECT);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if ((!update && (!RUN.equals(action) || jobs.isEmpty())) || !store.setActive(true)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        running = true;
        if (update) store.note("Updating YouTube engine…");
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "NightAudio:downloads");
        wakeLock.acquire(6 * 60 * 60 * 1000L);

        AudioEngine.Format format = "M4B".equals(options.getString("format", "OPUS"))
                ? AudioEngine.Format.M4B : AudioEngine.Format.OPUS;
        boolean split = options.getBoolean("split", false);
        Workload requested = Workload.fromPercent(options.getInt(Workload.KEY, Workload.DEFAULT_PERCENT));
        boolean batterySaver = power.isPowerSaveMode();
        boolean limitedDevice = Runtime.getRuntime().availableProcessors() < 4
                || getSystemService(ActivityManager.class).isLowRamDevice();
        Workload workload = requested.forDevice(batterySaver || limitedDevice);
        String reason = workload == requested ? "your setting" : batterySaver ? "Battery Saver" : "device resource limit";
        String workloadDisplay = "Workload " + workload.percent + "% · " + reason;
        Batch current = update ? null : new Batch(jobs, workload, workloadDisplay, folder);
        batch = current;
        if (current != null) {
            store.note(workloadDisplay + "\n" + BACKGROUND + " " + pauseInstructions());
            power.addThermalStatusListener(getMainExecutor(), thermalListener);
            listeningForHeat = true;
            onThermalStatus(power.getCurrentThermalStatus());
        }
        worker.execute(() -> {
            String message;
            try {
                if (current == null) {
                    NativeRuntime.updateYoutube(this);
                    message = "YouTube engine is up to date.";
                } else {
                    message = current.process(format, split);
                }
            } catch (Exception error) {
                message = readableError(error);
            }
            String finalMessage = message;
            main.post(() -> finishRun(finalMessage));
        });
        return START_NOT_STICKY;
    }

    private void onThermalStatus(int status) {
        Batch active = batch;
        if (status >= PowerManager.THERMAL_STATUS_SEVERE && active != null && !active.pipeline.isStopped()) {
            if (active.cancel(HEAT_PAUSE)) store.note(HEAT_PAUSE);
        }
    }

    private void stopThermalListener() {
        if (listeningForHeat) {
            power.removeThermalStatusListener(thermalListener);
            listeningForHeat = false;
        }
    }

    private final class Batch {
        private final List<QueueStore.Job> jobs;
        private final Workload workload;
        private final String workloadDisplay;
        private final DownloadFolder folder;
        private final TransferPipeline pipeline;
        private final ConcurrentHashMap<String, AudioEngine> engines = new ConcurrentHashMap<>();
        private final AtomicInteger saved = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private final AtomicInteger downloading = new AtomicInteger();
        private final AtomicInteger encoding = new AtomicInteger();
        private final AtomicLong notifiedAt = new AtomicLong();

        private volatile String stopMessage = "Queue stopped. Pending items can be resumed.";

        Batch(List<QueueStore.Job> jobs, Workload workload, String workloadDisplay, DownloadFolder folder) {
            this.jobs = jobs;
            this.workload = workload;
            this.workloadDisplay = workloadDisplay;
            this.folder = folder;
            pipeline = new TransferPipeline(workload.downloads, workload.encoders, workload.overlap);
        }

        boolean cancel() {
            return cancel("Queue stopped. Pending items can be resumed.");
        }

        boolean cancel(String message) {
            if (!pipeline.stop()) return false;
            stopMessage = message;
            engines.values().forEach(AudioEngine::cancel);
            return true;
        }

        String process(AudioEngine.Format format, boolean split) throws Exception {
            pipeline.run(jobs.size(), i -> processItem(jobs.get(i), format, split));
            if (pipeline.isStopped()) return stopMessage;
            if (failed.get() > 0) return saved.get() + " saved · " + failed.get() + " need attention";
            return saved.get() + (saved.get() == 1 ? " video saved to " : " videos saved to ") + folder.label() + ".";
        }

        private void processItem(QueueStore.Job job, AudioEngine.Format format, boolean split) {
            String id = job.id();
            AudioEngine engine = new AudioEngine(DownloadService.this);
            engines.put(id, engine);
            ItemProgress progress = new ItemProgress(id);
            try {
                AudioEngine.Prepared ready = pipeline.download(() -> {
                    if (!store.begin(job)) return null;
                    downloading.incrementAndGet();
                    try {
                        showSummary();
                        return engine.prepare(id, store.workDirectory(job.workKey()).toFile(), workload, progress);
                    } finally {
                        downloading.decrementAndGet();
                    }
                });
                if (ready == null) return;
                AudioEngine.Result result = pipeline.encode(() -> {
                    encoding.incrementAndGet();
                    try {
                        showSummary();
                        return engine.export(ready, format, split, workload, folder, progress);
                    } finally {
                        encoding.decrementAndGet();
                    }
                });
                store.finished(id, result.title(), result.uri().toString(), result.mime(), result.chapters(), result.bytes());
                saved.incrementAndGet();
            } catch (Exception error) {
                boolean pausedForYouTube = error instanceof YouTubeIssue issue && issue.pauseQueue
                        && cancel(issue.getMessage());
                boolean stopped = pipeline.isStopped();
                store.failed(id, stopped && !pausedForYouTube ? "Ready to resume" : readableError(error), stopped);
                if (!stopped) failed.incrementAndGet();
            } finally {
                engines.remove(id);
                showSummary();
            }
        }

        private void showSummary() {
            long now = SystemClock.elapsedRealtime();
            long last = notifiedAt.get();
            if (destroyed || pipeline.isStopped() || now - last < 600 || !notifiedAt.compareAndSet(last, now)) return;
            int ready = saved.get() + failed.get();
            int network = downloading.get();
            int cpu = encoding.get();
            String title = network > 0 && cpu > 0 ? "Downloading and encoding"
                    : network > 0 ? "Downloading audio" : cpu > 0 ? "Encoding audio" : "Processing queue";
            String detail = ready + " of " + jobs.size() + " processed";
            if (network > 0) detail += " · " + network + " downloading";
            if (cpu > 0) detail += " · " + cpu + " encoding";
            notifications.notify(NOTICE_ID, notification(title, workloadDisplay + "\n" + detail,
                    ready * 100 / jobs.size(), true));
        }

        private final class ItemProgress implements AudioEngine.Progress {
            private final String id;
            private String lastStage = "";
            private long lastUpdate;

            ItemProgress(String id) { this.id = id; }

            @Override public synchronized void update(String title, String stage, int percent) {
                if (pipeline.isStopped()) return;
                long now = SystemClock.elapsedRealtime();
                if (percent >= 0 && stage.equals(lastStage) && now - lastUpdate < 400) return;
                lastStage = stage;
                lastUpdate = now;
                store.progress(id, title, stage, percent);
                showSummary();
            }
        }
    }

    private Notification notification(String title, String body, int percent, boolean canStop) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(title)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (!body.trim().isEmpty()) builder.setContentText(body.split("\n", 2)[0]);
        if (canStop) {
            builder.setProgress(100, Math.max(0, percent), percent < 0);
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, DownloadService.class)
                            .setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, "Stop", stop).build());
            builder.setDeleteIntent(stop);
            String explanation = (body.isEmpty() ? "" : body + "\n") + BACKGROUND + " " + pauseInstructions();
            builder.setStyle(new Notification.BigTextStyle().bigText(explanation));
        }
        return builder.build();
    }

    private static String pauseInstructions() {
        return Build.VERSION.SDK_INT >= 34 ? "Tap Stop on the notification or dismiss it to pause."
                : "Tap Stop on the notification to pause.";
    }

    private void finishRun(String message) {
        stopThermalListener();
        batch = null;
        running = false;
        releaseWakeLock();
        store.note(message);
        store.setActive(false);
        store.cleanTemporaryFiles();
        if (!destroyed) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    @Override public void onTimeout(int startId, int foregroundServiceType) {
        Batch active = batch;
        if (active != null) active.cancel();
        stopSelf();
    }

    @Override public void onDestroy() {
        destroyed = true;
        stopThermalListener();
        Batch active = batch;
        if (active != null) active.cancel();
        worker.shutdown();
        releaseWakeLock();
        super.onDestroy();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    static String readableError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "The operation could not finish. Try again.";
        for (String line : message.split("\\R")) {
            if (line.startsWith("ERROR: ")) {
                message = line.substring(7);
                break;
            }
        }
        message = message.trim().replaceAll("\\s+", " ");
        return message.length() > 210 ? message.substring(0, 207) + "…" : message;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
