package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.domain.model.valueobjects.ExerciseCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Informational persisted-state observation, never a session reservation or admission guarantee. */
public record PatientDailyAvailability(LocalDate quotaDate, Instant evaluatedAt,
        Long remainingPlanSessionSlots, List<ExerciseAvailability> exercises) {
    public PatientDailyAvailability {
        Objects.requireNonNull(quotaDate); Objects.requireNonNull(evaluatedAt);
        exercises = List.copyOf(exercises);
    }
    public enum Status { AVAILABLE, BLOCKED, CHECK_AT_START }
    public enum Reason {
        ACTIVE_SESSION, CONFIGURATION_UNAVAILABLE, PLAN_QUOTA_EXHAUSTED, EXERCISE_QUOTA_EXHAUSTED,
        DAILY_TARGET_TIME_INSUFFICIENT, DAILY_SAFETY_TIME_INSUFFICIENT
    }
    public record ExerciseAvailability(ExerciseCode exerciseCode, Long remainingPrescribedSlots,
            Status status, Reason reason) {}
}
