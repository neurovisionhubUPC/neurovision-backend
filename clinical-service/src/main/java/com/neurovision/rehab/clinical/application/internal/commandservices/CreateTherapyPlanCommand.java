package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.util.Map;
import java.util.Objects;

public record CreateTherapyPlanCommand(
        ClinicalPatientId clinicalPatientId,
        CognitoSubject doctorSubject,
        int dailySessionLimit,
        int dailyTargetDurationMinutes,
        Integer dailyHardSafetyLimitMinutes,
        int minimumValidDurationMinutes,
        int maximumSessionDurationMinutes,
        long minimumValidSampleCount,
        Map<ExerciseCode, Integer> dailyExercisePrescriptions) {
    public CreateTherapyPlanCommand {
        Objects.requireNonNull(clinicalPatientId);
        Objects.requireNonNull(doctorSubject);
        dailyExercisePrescriptions = Map.copyOf(Objects.requireNonNull(dailyExercisePrescriptions));
    }
}
