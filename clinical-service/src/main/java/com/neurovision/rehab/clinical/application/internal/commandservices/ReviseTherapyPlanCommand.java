package com.neurovision.rehab.clinical.application.internal.commandservices;

import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ReviseTherapyPlanCommand(
        UUID commandId,
        TherapyPlanId therapyPlanId,
        ClinicalPatientId clinicalPatientId,
        CognitoSubject doctorSubject,
        Integer dailySessionLimit,
        Integer dailyTargetDurationMinutes,
        Integer dailyHardSafetyLimitMinutes,
        Map<ExerciseCode, Integer> dailyExercisePrescriptions,
        TherapyPlanStatus status,
        String clinicalReason) {
    public ReviseTherapyPlanCommand {
        Objects.requireNonNull(commandId);
        Objects.requireNonNull(therapyPlanId);
        Objects.requireNonNull(clinicalPatientId);
        Objects.requireNonNull(doctorSubject);
        if (dailyExercisePrescriptions != null) dailyExercisePrescriptions = Map.copyOf(dailyExercisePrescriptions);
        if (status == TherapyPlanStatus.ACTIVE) throw new IllegalArgumentException("Phase 2B does not define plan resumption");
        if (dailySessionLimit == null && dailyTargetDurationMinutes == null
                && dailyHardSafetyLimitMinutes == null && dailyExercisePrescriptions == null
                && status == null) throw new IllegalArgumentException("Plan revision command must contain a change");
    }
}
