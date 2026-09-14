package com.neurovision.rehab.clinical.domain.model.aggregates;

import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.time.Instant;
import java.util.*;

/** One immutable, effective-dated change to a therapy plan. */
public final class TherapyPlanRevision {
    public static final int FINGERPRINT_LENGTH = 64;
    public static final int REASON_MAX_LENGTH = 500;

    private final TherapyPlanRevisionId id;
    private final TherapyPlanId therapyPlanId;
    private final long sequence;
    private final UUID commandId;
    private final String commandFingerprint;
    private final TherapyPlanRevisionKind kind;
    private final Instant effectiveFrom;
    private final Instant createdAt;
    private final CognitoSubject createdByDoctorSubject;
    private final TherapyPlanStatus status;
    private final Integer dailySessionLimit;
    private final Integer dailyTargetDurationMinutes;
    private final Integer dailyHardSafetyLimitMinutes;
    private final Map<ExerciseCode, Integer> dailyExercisePrescriptions;
    private final String clinicalReason;

    private TherapyPlanRevision(
            TherapyPlanRevisionId id, TherapyPlanId therapyPlanId, long sequence, UUID commandId,
            String commandFingerprint, TherapyPlanRevisionKind kind, Instant effectiveFrom,
            Instant createdAt, CognitoSubject createdByDoctorSubject, TherapyPlanStatus status,
            Integer dailySessionLimit, Integer dailyTargetDurationMinutes,
            Integer dailyHardSafetyLimitMinutes,
            Map<ExerciseCode, Integer> dailyExercisePrescriptions, String clinicalReason) {
        this.id = Objects.requireNonNull(id);
        this.therapyPlanId = Objects.requireNonNull(therapyPlanId);
        if (sequence < 1) throw new IllegalArgumentException("Revision sequence must be positive");
        this.sequence = sequence;
        this.commandId = Objects.requireNonNull(commandId, "Command ID must not be null");
        this.commandFingerprint = validateFingerprint(commandFingerprint);
        this.kind = Objects.requireNonNull(kind);
        this.effectiveFrom = Objects.requireNonNull(effectiveFrom);
        this.createdAt = Objects.requireNonNull(createdAt);
        if (effectiveFrom.isBefore(createdAt)) throw new IllegalArgumentException("Effective time must not precede creation time");
        this.createdByDoctorSubject = Objects.requireNonNull(createdByDoctorSubject);
        if (status == TherapyPlanStatus.ACTIVE) throw new IllegalArgumentException("Phase 2B does not define plan resumption");
        this.status = status;
        positive(dailySessionLimit, "Daily session limit");
        positive(dailyTargetDurationMinutes, "Daily target duration");
        positive(dailyHardSafetyLimitMinutes, "Daily hard safety limit");
        this.dailySessionLimit = dailySessionLimit;
        this.dailyTargetDurationMinutes = dailyTargetDurationMinutes;
        this.dailyHardSafetyLimitMinutes = dailyHardSafetyLimitMinutes;
        this.dailyExercisePrescriptions = immutablePrescriptions(dailyExercisePrescriptions);
        this.clinicalReason = normalizeReason(clinicalReason);
        if (status != null && this.clinicalReason == null) {
            throw new IllegalArgumentException("Suspension or cancellation requires a clinical reason");
        }
        if (status == null && dailySessionLimit == null && dailyTargetDurationMinutes == null
                && dailyHardSafetyLimitMinutes == null && this.dailyExercisePrescriptions == null) {
            throw new IllegalArgumentException("Revision must change at least one plan value");
        }
    }

    public static TherapyPlanRevision create(
            TherapyPlanRevisionId id, TherapyPlanId planId, long sequence, UUID commandId,
            String fingerprint, TherapyPlanRevisionKind kind, Instant effectiveFrom, Instant createdAt,
            CognitoSubject doctor, TherapyPlanStatus status, Integer dailySessionLimit,
            Integer dailyTargetDurationMinutes, Integer dailyHardSafetyLimitMinutes,
            Map<ExerciseCode, Integer> prescriptions, String reason) {
        return new TherapyPlanRevision(id, planId, sequence, commandId, fingerprint, kind,
                effectiveFrom, createdAt, doctor, status, dailySessionLimit,
                dailyTargetDurationMinutes, dailyHardSafetyLimitMinutes, prescriptions, reason);
    }

    public static TherapyPlanRevision rehydrate(
            TherapyPlanRevisionId id, TherapyPlanId planId, long sequence, UUID commandId,
            String fingerprint, TherapyPlanRevisionKind kind, Instant effectiveFrom, Instant createdAt,
            CognitoSubject doctor, TherapyPlanStatus status, Integer dailySessionLimit,
            Integer dailyTargetDurationMinutes, Integer dailyHardSafetyLimitMinutes,
            Map<ExerciseCode, Integer> prescriptions, String reason) {
        return create(id, planId, sequence, commandId, fingerprint, kind, effectiveFrom, createdAt,
                doctor, status, dailySessionLimit, dailyTargetDurationMinutes,
                dailyHardSafetyLimitMinutes, prescriptions, reason);
    }

    public TherapyPlan applyTo(TherapyPlan plan) {
        if (!plan.id().equals(therapyPlanId)) throw new IllegalArgumentException("Revision belongs to another plan");
        return TherapyPlan.rehydrate(plan.id(), plan.clinicalPatientId(), plan.doctorSubject(),
                status == null ? plan.status() : status, plan.configurationVersion(),
                dailySessionLimit == null ? plan.dailySessionLimit() : dailySessionLimit,
                dailyTargetDurationMinutes == null ? plan.dailyTargetDurationMinutes() : dailyTargetDurationMinutes,
                dailyHardSafetyLimitMinutes == null
                        ? (plan.dailyHardSafetyLimitMinutes().isPresent() ? plan.dailyHardSafetyLimitMinutes().getAsInt() : null)
                        : dailyHardSafetyLimitMinutes,
                plan.minimumValidDurationMinutes(), plan.maximumSessionDurationMinutes(),
                plan.minimumValidSampleCount(),
                dailyExercisePrescriptions == null ? plan.dailyExercisePrescriptions() : dailyExercisePrescriptions);
    }

    public static TherapyPlan applyEligible(
            TherapyPlan base, Collection<TherapyPlanRevision> revisions, Instant effectiveAt) {
        TherapyPlan current = base;
        var ordered = revisions.stream()
                .filter(revision -> !revision.effectiveFrom.isAfter(effectiveAt))
                .sorted(Comparator.comparingLong(TherapyPlanRevision::sequence))
                .toList();
        for (var revision : ordered) current = revision.applyTo(current);
        return current;
    }

    private static Map<ExerciseCode, Integer> immutablePrescriptions(Map<ExerciseCode, Integer> value) {
        if (value == null) return null;
        if (value.isEmpty()) throw new IllegalArgumentException("Exercise distribution must not be empty");
        var copy = new TreeMap<ExerciseCode, Integer>();
        value.forEach((code, quantity) -> {
            Objects.requireNonNull(code);
            if (quantity == null || quantity < 1) throw new IllegalArgumentException("Exercise quantity must be positive");
            copy.put(code, quantity);
        });
        return Collections.unmodifiableMap(copy);
    }

    private static void positive(Integer value, String field) {
        if (value != null && value < 1) throw new IllegalArgumentException(field + " must be positive");
    }

    private static String validateFingerprint(String value) {
        Objects.requireNonNull(value);
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Command fingerprint must be lowercase SHA-256 hex");
        return value;
    }

    private static String normalizeReason(String value) {
        if (value == null) return null;
        value = value.trim();
        if (value.isEmpty() || value.length() > REASON_MAX_LENGTH) {
            throw new IllegalArgumentException("Clinical reason must contain 1 to 500 characters");
        }
        return value;
    }

    public TherapyPlanRevisionId id() { return id; }
    public TherapyPlanId therapyPlanId() { return therapyPlanId; }
    public long sequence() { return sequence; }
    public UUID commandId() { return commandId; }
    public String commandFingerprint() { return commandFingerprint; }
    public TherapyPlanRevisionKind kind() { return kind; }
    public Instant effectiveFrom() { return effectiveFrom; }
    public Instant createdAt() { return createdAt; }
    public CognitoSubject createdByDoctorSubject() { return createdByDoctorSubject; }
    public Optional<TherapyPlanStatus> status() { return Optional.ofNullable(status); }
    public OptionalInt dailySessionLimit() { return dailySessionLimit == null ? OptionalInt.empty() : OptionalInt.of(dailySessionLimit); }
    public OptionalInt dailyTargetDurationMinutes() { return dailyTargetDurationMinutes == null ? OptionalInt.empty() : OptionalInt.of(dailyTargetDurationMinutes); }
    public OptionalInt dailyHardSafetyLimitMinutes() { return dailyHardSafetyLimitMinutes == null ? OptionalInt.empty() : OptionalInt.of(dailyHardSafetyLimitMinutes); }
    public Optional<Map<ExerciseCode, Integer>> dailyExercisePrescriptions() { return Optional.ofNullable(dailyExercisePrescriptions); }
    public Optional<String> clinicalReason() { return Optional.ofNullable(clinicalReason); }
}
