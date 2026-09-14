package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.time.Instant;
import java.util.Objects;

public record AppendTherapeuticConfigurationCommand(
        TherapyPlanId therapyPlanId,
        ClinicalPatientId clinicalPatientId,
        CognitoSubject doctorSubject,
        TherapeuticExercise exercise,
        int parameterSchemaVersion,
        Instant effectiveFrom,
        TherapeuticExerciseParameters parameters) {
    public AppendTherapeuticConfigurationCommand {
        Objects.requireNonNull(therapyPlanId, "Therapy plan ID must not be null");
        Objects.requireNonNull(clinicalPatientId, "Clinical patient ID must not be null");
        Objects.requireNonNull(doctorSubject, "Doctor subject must not be null");
        Objects.requireNonNull(exercise, "Exercise must not be null");
        Objects.requireNonNull(effectiveFrom, "Effective time must not be null");
        Objects.requireNonNull(parameters, "Parameters must not be null");
    }
}
