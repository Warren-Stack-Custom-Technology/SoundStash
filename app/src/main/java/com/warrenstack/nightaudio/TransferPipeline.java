package com.warrenstack.nightaudio;

import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

final class TransferPipeline {
    interface Stage<T> {
        T run() throws Exception;
    }

    private final int window;
    private final Semaphore downloading;
    private final Semaphore encoding;
    private final AtomicBoolean stopped = new AtomicBoolean();

    TransferPipeline(int downloads, int encoders, boolean overlap) {
        window = overlap ? downloads + encoders : 1;
        downloading = new Semaphore(downloads, true);
        encoding = new Semaphore(encoders, true);
    }

    boolean isStopped() {
        return stopped.get();
    }

    boolean stop() {
        return stopped.compareAndSet(false, true);
    }

    void run(int count, IntConsumer job) throws Exception {
        int workers = Math.min(count, window);
        if (workers == 0) return;
        var pool = Executors.newFixedThreadPool(workers);
        var next = new AtomicInteger();
        var pending = new ArrayList<Future<?>>();
        try {
            for (int i = 0; i < workers; i++) {
                pending.add(pool.submit(() -> {
                    while (!isStopped()) {
                        int index = next.getAndIncrement();
                        if (index >= count) return;
                        job.accept(index);
                    }
                }));
            }
            ExecutionException failure = null;
            for (Future<?> worker : pending) {
                try {
                    worker.get();
                } catch (ExecutionException error) {
                    stop();
                    if (failure == null) failure = error;
                }
            }
            if (failure != null) throw failure;
        } finally {
            pool.shutdown();
        }
    }

    <T> T download(Stage<T> stage) throws Exception {
        return runStage(downloading, stage);
    }

    <T> T encode(Stage<T> stage) throws Exception {
        return runStage(encoding, stage);
    }

    private <T> T runStage(Semaphore permits, Stage<T> stage) throws Exception {
        if (isStopped()) throw new CancellationException("Stopped");
        permits.acquire();
        try {
            if (isStopped()) throw new CancellationException("Stopped");
            return stage.run();
        } finally {
            permits.release();
        }
    }
}
