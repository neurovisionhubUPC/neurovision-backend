package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.util.Objects;

public record StartTherapySessionCommand(
        ClinicalPatientId clinicalPatientId,
        TherapyPlanId therapyPlanId,
        ExerciseCode exerciseCode,
        IdempotencyKey idempotencyKey) {
    public StartTherapySessionCommand {
        Objects.requireNonNull(clinicalPatientId);
        Objects.requireNonNull(therapyPlanId);
        Objects.requireNonNull(exerciseCode);
        Objects.requireNonNull(idempotencyKey);
    }
}
