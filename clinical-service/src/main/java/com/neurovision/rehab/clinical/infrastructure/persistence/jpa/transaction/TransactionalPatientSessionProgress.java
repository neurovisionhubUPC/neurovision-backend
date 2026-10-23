package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.transaction;

import com.neurovision.rehab.clinical.application.internal.commandservices.PatientSessionProgressService;
import com.neurovision.rehab.clinical.application.ports.in.PatientSessionProgressUseCase;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class TransactionalPatientSessionProgress implements PatientSessionProgressUseCase {
    private final PatientSessionProgressService service;
    public TransactionalPatientSessionProgress(PatientSessionProgressService service) { this.service = service; }
    @Override @Transactional public View activity(CognitoSubject patient, TherapySessionId session, SessionActivityEvent event) {
        return service.activity(patient, session, event);
    }
    @Override @Transactional public View progress(CognitoSubject patient, TherapySessionId session) { return service.progress(patient, session); }
    @Override @Transactional public View complete(CognitoSubject patient, TherapySessionId session, IdempotencyKey key, Action action) {
        return service.complete(patient, session, key, action);
    }
}
