package com.neurovision.rehab.clinical.application.ports.out.authorization;

import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.util.Objects;
import java.util.Optional;

/** One observation of DOCTOR session authorship, current active assignment and frozen session policy. */
public interface DoctorVisualSearchSummarySnapshotRepository {
    Optional<Snapshot> findByDoctorAndSession(CognitoSubject doctor, TherapySessionId sessionId);

    record Snapshot(TherapySessionId sessionId, TherapeuticConfigurationSnapshot configuration) {
        public Snapshot { Objects.requireNonNull(sessionId); Objects.requireNonNull(configuration); }
        @Override public String toString() { return "DoctorVisualSearchSummarySnapshot[redacted]"; }
    }
}
