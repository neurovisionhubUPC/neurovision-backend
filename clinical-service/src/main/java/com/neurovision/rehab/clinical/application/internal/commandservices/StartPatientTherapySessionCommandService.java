package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.application.ports.out.persistence.ClinicalPatientRepository;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.domain.model.valueobjects.ExerciseCode;
import com.neurovision.rehab.clinical.domain.model.valueobjects.IdempotencyKey;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapyPlanId;

/** Resolves the server-owned clinical patient identity before transactional admission. */
public final class StartPatientTherapySessionCommandService {
    private final ClinicalPatientRepository patients;
    private final StartTherapySessionCommandService starter;

    public StartPatientTherapySessionCommandService(ClinicalPatientRepository patients,
            StartTherapySessionCommandService starter) {
        this.patients = patients;
        this.starter = starter;
    }

    public StartTherapySessionResult handle(CognitoSubject subject, TherapyPlanId planId,
            ExerciseCode exerciseCode, IdempotencyKey key) {
        var patient = patients.findByPatientSubject(subject)
                .orElseThrow(() -> new SecurityException("Patient is not registered"));
        return starter.handle(new StartTherapySessionCommand(patient.id(), planId, exerciseCode, key));
    }
}
