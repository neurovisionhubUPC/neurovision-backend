package com.neurovision.rehab.clinical.domain.model.aggregates;

import com.neurovision.rehab.clinical.domain.model.valueobjects.ClinicalPatientId;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.clinical.domain.model.valueobjects.ExerciseCode;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapyPlanId;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapyPlanStatus;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.Collections;

public final class TherapyPlan {
    private final TherapyPlanId id;
    private final ClinicalPatientId clinicalPatientId;
    private final CognitoSubject doctorSubject;
    private final TherapyPlanStatus status;
    private final long configurationVersion;
    private final int dailySessionLimit;
    private final int dailyTargetDurationMinutes;
    private final Integer dailyHardSafetyLimitMinutes;
    private final int minimumValidDurationMinutes;
    private final int maximumSessionDurationMinutes;
    private final long minimumValidSampleCount;
    private final Map<ExerciseCode, Integer> dailyExercisePrescriptions;

    private TherapyPlan(
            TherapyPlanId id,
            ClinicalPatientId clinicalPatientId,
            CognitoSubject doctorSubject,
            TherapyPlanStatus status,
            long configurationVersion,
            int dailySessionLimit,
            int dailyTargetDurationMinutes,
            Integer dailyHardSafetyLimitMinutes,
            int minimumValidDurationMinutes,
            int maximumSessionDurationMinutes,
            long minimumValidSampleCount,
            Map<ExerciseCode, Integer> dailyExercisePrescriptions) {
        this.id = Objects.requireNonNull(id, "Therapy plan ID must not be null");
        this.clinicalPatientId = Objects.requireNonNull(clinicalPatientId, "Clinical patient ID must not be null");
        this.doctorSubject = Objects.requireNonNull(doctorSubject, "Doctor subject must not be null");
        this.status = Objects.requireNonNull(status, "Therapy plan status must not be null");
        if (configurationVersion < 1) throw new IllegalArgumentException("Configuration version must be positive");
        if (dailySessionLimit < 1) throw new IllegalArgumentException("Daily session limit must be positive");
        if (dailyTargetDurationMinutes < 1) throw new IllegalArgumentException("Daily target duration must be positive");
        if (minimumValidDurationMinutes < 1) throw new IllegalArgumentException("Minimum valid duration must be positive");
        if (maximumSessionDurationMinutes < minimumValidDurationMinutes) {
            throw new IllegalArgumentException("Maximum session duration must not be lower than minimum valid duration");
        }
        if (dailyHardSafetyLimitMinutes != null && dailyHardSafetyLimitMinutes < maximumSessionDurationMinutes) {
            throw new IllegalArgumentException("Hard safety limit must accommodate one maximum-duration session");
        }
        if (minimumValidSampleCount < 1) throw new IllegalArgumentException("Minimum valid sample count must be positive");
        Objects.requireNonNull(dailyExercisePrescriptions, "Daily exercise prescriptions must not be null");
        if (dailyExercisePrescriptions.isEmpty()) throw new IllegalArgumentException("At least one exercise must be prescribed");
        var normalizedPrescriptions = new TreeMap<ExerciseCode, Integer>();
        dailyExercisePrescriptions.forEach((exercise, quantity) -> {
            Objects.requireNonNull(exercise, "Prescribed exercise code must not be null");
            if (quantity == null || quantity < 1) throw new IllegalArgumentException("Prescribed exercise quantity must be positive");
            normalizedPrescriptions.put(exercise, quantity);
        });
        long prescribedTotal = normalizedPrescriptions.values().stream().mapToLong(Integer::longValue).sum();
        if (prescribedTotal > dailySessionLimit) {
            throw new IllegalArgumentException("Daily exercise prescriptions must not exceed the daily session limit");
        }
        this.configurationVersion = configurationVersion;
        this.dailySessionLimit = dailySessionLimit;
        this.dailyTargetDurationMinutes = dailyTargetDurationMinutes;
        this.dailyHardSafetyLimitMinutes = dailyHardSafetyLimitMinutes;
        this.minimumValidDurationMinutes = minimumValidDurationMinutes;
        this.maximumSessionDurationMinutes = maximumSessionDurationMinutes;
        this.minimumValidSampleCount = minimumValidSampleCount;
        this.dailyExercisePrescriptions = Collections.unmodifiableMap(new TreeMap<>(normalizedPrescriptions));
    }

    public static TherapyPlan rehydrate(
            TherapyPlanId id, ClinicalPatientId clinicalPatientId, CognitoSubject doctorSubject,
            TherapyPlanStatus status, long configurationVersion, int dailySessionLimit,
            int dailyTargetDurationMinutes, Integer dailyHardSafetyLimitMinutes,
            int minimumValidDurationMinutes, int maximumSessionDurationMinutes,
            long minimumValidSampleCount,
            Map<ExerciseCode, Integer> dailyExercisePrescriptions) {
        return new TherapyPlan(id, clinicalPatientId, doctorSubject, status, configurationVersion,
                dailySessionLimit, dailyTargetDurationMinutes, dailyHardSafetyLimitMinutes,
                minimumValidDurationMinutes, maximumSessionDurationMinutes, minimumValidSampleCount,
                dailyExercisePrescriptions);
    }

    public void validateAdmission(
            ExerciseCode exerciseCode,
            long consumedSessionSlots,
            long consumedExerciseSlots,
            long exposureSeconds) {
        if (status != TherapyPlanStatus.ACTIVE) throw new IllegalStateException("Therapy plan is not active");
        if (consumedSessionSlots >= dailySessionLimit) throw new IllegalStateException("Daily session quota is exhausted");
        int prescribedQuantity = dailyExercisePrescriptions.getOrDefault(exerciseCode, 0);
        if (consumedExerciseSlots >= prescribedQuantity) {
            throw new IllegalStateException("Daily prescribed quota for exercise is exhausted");
        }
        long remainingTargetSeconds = dailyTargetDurationMinutes * 60L - exposureSeconds;
        if (remainingTargetSeconds < minimumValidDurationMinutes * 60L) {
            throw new IllegalStateException("Remaining daily target cannot accommodate a valid session");
        }
        if (dailyHardSafetyLimitMinutes != null) {
            long remainingSafetySeconds = dailyHardSafetyLimitMinutes * 60L - exposureSeconds;
            if (remainingSafetySeconds < maximumSessionDurationMinutes * 60L) {
                throw new IllegalStateException("Remaining hard safety allowance cannot accommodate the session");
            }
        }
    }

    public TherapyPlanId id() { return id; }
    public ClinicalPatientId clinicalPatientId() { return clinicalPatientId; }
    public CognitoSubject doctorSubject() { return doctorSubject; }
    public TherapyPlanStatus status() { return status; }
    public long configurationVersion() { return configurationVersion; }
    public int dailySessionLimit() { return dailySessionLimit; }
    public int dailyTargetDurationMinutes() { return dailyTargetDurationMinutes; }
    public OptionalInt dailyHardSafetyLimitMinutes() { return dailyHardSafetyLimitMinutes == null ? OptionalInt.empty() : OptionalInt.of(dailyHardSafetyLimitMinutes); }
    public int minimumValidDurationMinutes() { return minimumValidDurationMinutes; }
    public int maximumSessionDurationMinutes() { return maximumSessionDurationMinutes; }
    public long minimumValidSampleCount() { return minimumValidSampleCount; }
    public Map<ExerciseCode, Integer> dailyExercisePrescriptions() { return dailyExercisePrescriptions; }
}
