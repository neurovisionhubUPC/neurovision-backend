package com.neurovision.rehab.clinical.application.ports.out.authorization;

import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionId;
import java.util.Optional;

public interface TelemetryAuthorizationSnapshotRepository {
    Optional<TelemetryAuthorizationFacts> findBySubjectAndSession(
            CognitoSubject patientSubject, TherapySessionId sessionId);
}
