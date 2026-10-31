package com.neurovision.rehab.clinical.application.ports.out.persistence;

import java.time.Instant;
import java.util.*;

/** Clinical-only observations include current session authorship and ACTIVE assignment.
 * A denied observation must never contain a snapshot. Writes revalidate under a short lock.
 */
public interface SessionReportSnapshotRepository {
    Observation observeByKey(String doctor, UUID patient, UUID session, String key);
    Observation observeById(String doctor, UUID patient, UUID session, UUID id);

    /** Returns the persisted scoped-key winner, not necessarily the supplied candidate.
     * A thrown failure may represent an uncertain commit; callers must not retry automatically.
     */
    WriteResult insertAuthorized(String doctor, UUID patient, UUID session, String key, Snapshot candidate);

    record Snapshot(UUID id, UUID clinicalPatientId, UUID therapySessionId, Instant createdAt, String reportJson) {
        public Snapshot {
            Objects.requireNonNull(id); Objects.requireNonNull(clinicalPatientId); Objects.requireNonNull(therapySessionId);
            Objects.requireNonNull(createdAt); Objects.requireNonNull(reportJson);
            if (reportJson.isBlank()) throw new IllegalArgumentException("Empty session report snapshot");
        }
        @Override public String toString() { return "SessionReportSnapshot[redacted]"; }
    }
    record Observation(boolean authorized, Optional<Snapshot> snapshot) {
        public Observation {
            Objects.requireNonNull(snapshot);
            if (!authorized && snapshot.isPresent()) throw new IllegalArgumentException("Invalid denied snapshot observation");
        }
        @Override public String toString() { return "SessionReportSnapshotObservation[redacted]"; }
    }
    record WriteResult(boolean created, Snapshot snapshot) {
        public WriteResult { Objects.requireNonNull(snapshot); }
        @Override public String toString() { return "SessionReportSnapshotWrite[redacted]"; }
    }
}
