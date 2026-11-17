package com.warrenstack.nightaudio;

enum Workload {
    MINIMUM(25, "One task at a time · lowest speed", 1, 1, false, 1, "256K", 5),
    COOL(50, "25% download + 25% encode", 2, 2, true, 1, "256K", 6),
    BALANCED(75, "Two downloads + two encodes · paced", 2, 2, true, 1, "384K", 9),
    FULL(100, "Two downloads + one encode · full speed", 2, 1, true, 3, "", 0);

    static final String KEY = "workload";
    static final int DEFAULT_PERCENT = 50;

    final int percent;
    final String description;
    final int downloads;
    final int encoders;
    final boolean overlap;
    final int fragments;
    final String downloadRate;
    final int encodeRate;

    Workload(int percent, String description, int downloads, int encoders, boolean overlap, int fragments,
             String downloadRate, int encodeRate) {
        this.percent = percent;
        this.description = description;
        this.downloads = downloads;
        this.encoders = encoders;
        this.overlap = overlap;
        this.fragments = fragments;
        this.downloadRate = downloadRate;
        this.encodeRate = encodeRate;
    }

    static Workload fromPercent(int percent) {
        for (Workload workload : values()) {
            if (workload.percent == percent) return workload;
        }
        return COOL;
    }

    Workload forDevice(boolean constrained) {
        return constrained ? MINIMUM : this;
    }
}
