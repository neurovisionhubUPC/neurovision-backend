package com.neurovision.rehab.clinical.application.ports.out.authorization;

import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** One read snapshot of current ownership/assignment and the session's frozen configuration. */
public interface VisualSearchAuthorizationSnapshotRepository {
    Optional<Snapshot> findBySubjectAndSession(CognitoSubject subject, TherapySessionId sessionId);

    record Snapshot(TherapySessionId sessionId, TherapySessionStatus status, Instant leaseExpiresAt,
            TherapeuticConfigurationSnapshot configuration) {
        public Snapshot {
            Objects.requireNonNull(sessionId); Objects.requireNonNull(status); Objects.requireNonNull(configuration);
        }
        @Override public String toString() { return "VisualSearchAuthorizationSnapshot[redacted]"; }
    }
}
