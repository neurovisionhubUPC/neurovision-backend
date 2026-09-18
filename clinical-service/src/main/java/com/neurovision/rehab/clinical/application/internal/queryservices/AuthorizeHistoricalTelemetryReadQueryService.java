package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.authorization.HistoricalTelemetryReadAuthorizationPort;
import com.neurovision.rehab.clinical.application.ports.out.authorization.HistoricalTelemetryReadAuthorizationPort.ReaderRole;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionId;
import java.util.Objects;

public final class AuthorizeHistoricalTelemetryReadQueryService {
    private final HistoricalTelemetryReadAuthorizationPort authorization;

    public AuthorizeHistoricalTelemetryReadQueryService(HistoricalTelemetryReadAuthorizationPort authorization) {
        this.authorization = Objects.requireNonNull(authorization);
    }

    public TherapySessionId authorize(String authenticatedSubject, TherapySessionId sessionId, ReaderRole role) {
        if (sessionId == null || role == null) throw new HistoricalTelemetryReadDeniedException();
        final CognitoSubject subject;
        try {
            subject = new CognitoSubject(authenticatedSubject);
        } catch (IllegalArgumentException invalid) {
            throw new HistoricalTelemetryReadDeniedException();
        }
        if (!subject.value().equals(authenticatedSubject)
                || !authorization.permits(subject, sessionId, role)) {
            throw new HistoricalTelemetryReadDeniedException();
        }
        return sessionId;
    }
}
