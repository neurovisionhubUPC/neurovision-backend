package com.neurovision.rehab.clinical.application.ports.out.authorization;

import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapySessionId;

public interface HistoricalTelemetryReadAuthorizationPort {
    boolean permits(CognitoSubject subject, TherapySessionId sessionId, ReaderRole role);

    enum ReaderRole { PATIENT, DOCTOR }
}
