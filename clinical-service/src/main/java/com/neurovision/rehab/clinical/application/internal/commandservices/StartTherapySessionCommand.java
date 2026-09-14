package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.util.Objects;

public record StartTherapySessionCommand(
        ClinicalPatientId clinicalPatientId,
        TherapyPlanId therapyPlanId,
        ExerciseCode exerciseCode,
        long requestedConfigurationVersion,
        IdempotencyKey idempotencyKey) {
    public StartTherapySessionCommand {
        Objects.requireNonNull(clinicalPatientId);
        Objects.requireNonNull(therapyPlanId);
        Objects.requireNonNull(exerciseCode);
        if (requestedConfigurationVersion < 1) throw new IllegalArgumentException("Requested configuration version must be positive");
        Objects.requireNonNull(idempotencyKey);
    }
}
