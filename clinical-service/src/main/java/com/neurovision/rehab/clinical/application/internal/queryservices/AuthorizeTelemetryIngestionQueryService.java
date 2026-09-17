package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.authorization.TelemetryAuthorizationSnapshotRepository;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionId;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionStatus;
import java.time.Clock;
import java.util.Objects;

public final class AuthorizeTelemetryIngestionQueryService {
    private final TelemetryAuthorizationSnapshotRepository snapshots;
    private final Clock clock;

    public AuthorizeTelemetryIngestionQueryService(TelemetryAuthorizationSnapshotRepository snapshots,
            Clock clock) {
        this.snapshots = Objects.requireNonNull(snapshots);
        this.clock = Objects.requireNonNull(clock);
    }

    public TherapySessionId authorize(String authenticatedSubject, TherapySessionId sessionId) {
        if (sessionId == null) throw new TelemetryIngestionDeniedException();
        final CognitoSubject subject;
        try {
            subject = new CognitoSubject(authenticatedSubject);
        } catch (IllegalArgumentException invalidSubject) {
            throw new TelemetryIngestionDeniedException();
        }
        if (!subject.value().equals(authenticatedSubject)) {
            throw new TelemetryIngestionDeniedException();
        }
        var facts = snapshots.findBySubjectAndSession(subject, sessionId)
                .orElseThrow(TelemetryIngestionDeniedException::new);
        if (facts.status() != TherapySessionStatus.ACTIVE
                || facts.leaseExpiresAt() == null
                || !clock.instant().isBefore(facts.leaseExpiresAt())) {
            throw new TelemetryIngestionDeniedException();
        }
        return sessionId;
    }
}
