package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.transaction;

import com.neurovision.rehab.clinical.application.internal.commandservices.*;
import com.neurovision.rehab.clinical.application.ports.in.PatientTherapySessionStartUseCase;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.Objects;

@ApplicationScoped
public class TransactionalTherapySessionStarter implements PatientTherapySessionStartUseCase {
    private final StartTherapySessionCommandService delegate;
    private final StartPatientTherapySessionCommandService patientDelegate;

    public TransactionalTherapySessionStarter(StartTherapySessionCommandService delegate,
            StartPatientTherapySessionCommandService patientDelegate) {
        this.delegate = Objects.requireNonNull(delegate);
        this.patientDelegate = Objects.requireNonNull(patientDelegate);
    }

    @Transactional
    public StartTherapySessionResult handle(StartTherapySessionCommand command) {
        return delegate.handle(command);
    }

    @Override
    @Transactional
    public StartTherapySessionResult handle(CognitoSubject patientSubject, TherapyPlanId planId,
            ExerciseCode exerciseCode, IdempotencyKey key) {
        return patientDelegate.handle(patientSubject, planId, exerciseCode, key);
    }
}
