package com.neurovision.rehab.clinical.application.ports.out.persistence;

import java.time.Instant;
import java.util.*;

/** Metadata only; authorization, total and page must share one database observation. */
public interface SessionReportSnapshotListRepository {
    ListObservation observeList(String doctor, UUID patient, UUID session, int offset, int limit);

    record Summary(UUID reportSnapshotId, Instant createdAt) {
        public Summary { Objects.requireNonNull(reportSnapshotId); Objects.requireNonNull(createdAt); }
        @Override public String toString() { return "ReportSnapshotSummary[redacted]"; }
    }
    record ListObservation(boolean authorized, List<Summary> snapshots, long total) {
        public ListObservation {
            snapshots = List.copyOf(snapshots);
            if (total < 0 || snapshots.size() > total || (!authorized && (total != 0 || !snapshots.isEmpty())))
                throw new IllegalArgumentException("Invalid snapshot list observation");
        }
        @Override public String toString() { return "ReportSnapshotListObservation[redacted]"; }
    }
}
