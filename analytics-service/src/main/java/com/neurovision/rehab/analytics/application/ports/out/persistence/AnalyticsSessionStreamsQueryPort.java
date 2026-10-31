package com.neurovision.rehab.analytics.application.ports.out.persistence;

import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Read-only discovery of observed, valid baseline projections; never Telemetry admission inventory. */
public interface AnalyticsSessionStreamsQueryPort {
    Uni<List<Item>> load(UUID therapySessionId, Page page);

    record Page(int page, int size) {
        public Page {
            if (page < 0 || size < 1 || size > 50 || (long) page * size > 1000)
                throw new IllegalArgumentException("Invalid stream discovery page");
        }
        public int offset() { return page * size; }
        public int fetchLimit() { return size + 1; }
    }

    record Item(UUID streamId, String calculationVersion, long revision, long gaze, long gameplay) {
        public Item {
            Objects.requireNonNull(streamId);
            if (!"baseline-v1".equals(calculationVersion) || revision < 0 || gaze < 0 || gameplay < 0)
                throw new IllegalArgumentException("Invalid observed projection metadata");
            Math.addExact(gaze, gameplay);
        }
        public long total() { return Math.addExact(gaze, gameplay); }
    }
}
