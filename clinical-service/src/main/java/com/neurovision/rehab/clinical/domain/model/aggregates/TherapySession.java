package com.neurovision.rehab.clinical.domain.model.aggregates;

import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

public final class TherapySession {
    public static final int REQUEST_FINGERPRINT_LENGTH = 64;
    public static final int TERMINATION_REASON_MAX_LENGTH = 500;
    private final TherapySessionId id;
    private final TherapyPlanId therapyPlanId;
    private final ClinicalPatientId clinicalPatientId;
    private final CognitoSubject doctorSubject;
    private final ExerciseCode exerciseCode;
    private final TherapeuticConfigurationSnapshot therapeuticConfiguration;
    private final LocalDate quotaDate;
    private final IdempotencyKey idempotencyKey;
    private final String requestFingerprint;
    private final int minimumValidDurationMinutes;
    private final int maximumSessionDurationMinutes;
    private final long minimumValidSampleCount;
    private final Instant startedAt;
    private TherapySessionStatus status;
    private long activeDurationSeconds;
    private long validSampleCount;
    private boolean consumesDailyQuota;
    private Instant leaseExpiresAt;
    private Instant endedAt;
    private String terminationReason;

    private TherapySession(
            TherapySessionId id, TherapyPlanId therapyPlanId, ClinicalPatientId clinicalPatientId,
            CognitoSubject doctorSubject, ExerciseCode exerciseCode,
            TherapeuticConfigurationSnapshot therapeuticConfiguration,
            LocalDate quotaDate, IdempotencyKey idempotencyKey, String requestFingerprint,
            int minimumValidDurationMinutes, int maximumSessionDurationMinutes,
            long minimumValidSampleCount, TherapySessionStatus status, long activeDurationSeconds,
            long validSampleCount, boolean consumesDailyQuota, Instant startedAt,
            Instant leaseExpiresAt, Instant endedAt, String terminationReason) {
        this.id = Objects.requireNonNull(id);
        this.therapyPlanId = Objects.requireNonNull(therapyPlanId);
        this.clinicalPatientId = Objects.requireNonNull(clinicalPatientId);
        this.doctorSubject = Objects.requireNonNull(doctorSubject);
        this.exerciseCode = Objects.requireNonNull(exerciseCode);
        this.therapeuticConfiguration = Objects.requireNonNull(therapeuticConfiguration);
        if (!therapeuticConfiguration.exercise().code().equals(exerciseCode)) {
            throw new IllegalArgumentException("Frozen configuration does not match session exercise");
        }
        this.quotaDate = Objects.requireNonNull(quotaDate);
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.requestFingerprint = validateFingerprint(requestFingerprint);
        this.minimumValidDurationMinutes = minimumValidDurationMinutes;
        this.maximumSessionDurationMinutes = maximumSessionDurationMinutes;
        this.minimumValidSampleCount = minimumValidSampleCount;
        this.status = Objects.requireNonNull(status);
        this.activeDurationSeconds = activeDurationSeconds;
        this.validSampleCount = validSampleCount;
        this.consumesDailyQuota = consumesDailyQuota;
        this.startedAt = Objects.requireNonNull(startedAt);
        this.leaseExpiresAt = leaseExpiresAt;
        this.endedAt = endedAt;
        this.terminationReason = terminationReason;
        validateState();
    }

    public static TherapySession start(
            TherapySessionId id, TherapyPlan plan, ExerciseCode exerciseCode, LocalDate quotaDate,
            TherapeuticConfigurationSnapshot therapeuticConfiguration,
            IdempotencyKey idempotencyKey, String requestFingerprint, Instant startedAt,
            Instant leaseExpiresAt) {
        if (!leaseExpiresAt.isAfter(startedAt)) throw new IllegalArgumentException("Lease expiry must follow start time");
        return new TherapySession(id, plan.id(), plan.clinicalPatientId(), plan.doctorSubject(), exerciseCode,
                therapeuticConfiguration, quotaDate, idempotencyKey, requestFingerprint,
                plan.minimumValidDurationMinutes(), plan.maximumSessionDurationMinutes(),
                plan.minimumValidSampleCount(), TherapySessionStatus.ACTIVE, 0, 0, true,
                startedAt, leaseExpiresAt, null, null);
    }

    public static TherapySession rehydrate(
            TherapySessionId id, TherapyPlanId planId, ClinicalPatientId patientId, CognitoSubject doctorSubject,
            ExerciseCode exerciseCode, TherapeuticConfigurationSnapshot therapeuticConfiguration,
            LocalDate quotaDate, IdempotencyKey key,
            String fingerprint, int minimumDuration, int maximumDuration, long minimumSamples,
            TherapySessionStatus status, long activeSeconds, long validSamples, boolean consumesQuota,
            Instant startedAt, Instant leaseExpiresAt, Instant endedAt, String reason) {
        return new TherapySession(id, planId, patientId, doctorSubject, exerciseCode, therapeuticConfiguration,
                quotaDate, key, fingerprint, minimumDuration, maximumDuration, minimumSamples, status,
                activeSeconds, validSamples, consumesQuota, startedAt, leaseExpiresAt, endedAt, reason);
    }

    public void recordAcceptedActivity(Duration activeDuration, long acceptedValidSamples, Instant acceptedAt, Instant renewedLeaseExpiresAt) {
        requireActive();
        Objects.requireNonNull(activeDuration);
        if (activeDuration.isNegative() || acceptedValidSamples < 0) throw new IllegalArgumentException("Activity evidence must not be negative");
        if (acceptedAt.isBefore(startedAt) || !renewedLeaseExpiresAt.isAfter(acceptedAt)) throw new IllegalArgumentException("Activity timestamps are invalid");
        if (!acceptedAt.isBefore(leaseExpiresAt) || !renewedLeaseExpiresAt.isAfter(leaseExpiresAt)) {
            throw new IllegalStateException("Accepted activity must arrive before and extend the current lease");
        }
        long nextActiveDurationSeconds = Math.addExact(activeDurationSeconds, activeDuration.toSeconds());
        if (nextActiveDurationSeconds > maximumSessionDurationMinutes * 60L) {
            throw new IllegalStateException("Accepted activity exceeds the frozen maximum session duration");
        }
        long nextValidSampleCount = Math.addExact(validSampleCount, acceptedValidSamples);
        activeDurationSeconds = nextActiveDurationSeconds;
        validSampleCount = nextValidSampleCount;
        leaseExpiresAt = renewedLeaseExpiresAt;
    }

    public void complete(Instant endedAt) { transition(TherapySessionStatus.COMPLETED, endedAt, null, true); }
    public void cancel(String clinicalReason, Instant endedAt) { transition(TherapySessionStatus.CANCELLED, endedAt, requireText(clinicalReason, "Cancellation reason"), meaningfulActivityReached()); }
    public void abandon(String reason, Instant endedAt) { transition(TherapySessionStatus.ABANDONED, endedAt, requireText(reason, "Abandonment reason"), meaningfulActivityReached()); }
    public void failTechnical(String reason, Instant endedAt) { transition(TherapySessionStatus.FAILED_TECHNICAL, endedAt, requireText(reason, "Technical failure reason"), meaningfulActivityReached()); }

    public void expireLease(Instant observedAt) {
        requireActive();
        if (observedAt.isBefore(leaseExpiresAt)) throw new IllegalStateException("Session lease has not expired");
        failTechnical("SESSION_LEASE_EXPIRED", observedAt);
    }

    private void transition(TherapySessionStatus target, Instant at, String reason, boolean consumesQuota) {
        requireActive();
        Objects.requireNonNull(at);
        if (at.isBefore(startedAt)) throw new IllegalArgumentException("Session end time must not precede start time");
        status = target;
        endedAt = at;
        terminationReason = reason;
        consumesDailyQuota = consumesQuota;
        leaseExpiresAt = null;
    }

    public boolean meaningfulActivityReached() {
        return activeDurationSeconds >= minimumValidDurationMinutes * 60L
                && validSampleCount >= minimumValidSampleCount;
    }

    private void requireActive() {
        if (status != TherapySessionStatus.ACTIVE) throw new IllegalStateException("Terminal therapy sessions are immutable");
    }

    private void validateState() {
        if (minimumValidDurationMinutes < 1
                || maximumSessionDurationMinutes < minimumValidDurationMinutes || minimumValidSampleCount < 1
                || activeDurationSeconds < 0 || activeDurationSeconds > maximumSessionDurationMinutes * 60L
                || validSampleCount < 0) throw new IllegalArgumentException("Invalid frozen session configuration or evidence");
        if (status == TherapySessionStatus.ACTIVE && (leaseExpiresAt == null || endedAt != null || terminationReason != null)) throw new IllegalArgumentException("Invalid active session state");
        if (status.isTerminal() && (leaseExpiresAt != null || endedAt == null)) throw new IllegalArgumentException("Invalid terminal session state");
        if ((status == TherapySessionStatus.ACTIVE || status == TherapySessionStatus.COMPLETED) && !consumesDailyQuota) throw new IllegalArgumentException("Active and completed sessions must consume a daily quota slot");
        if ((status == TherapySessionStatus.CANCELLED || status == TherapySessionStatus.ABANDONED || status == TherapySessionStatus.FAILED_TECHNICAL)
                && (terminationReason == null || terminationReason.isBlank())) throw new IllegalArgumentException("Terminal reason is required");
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label + " must not be null");
        value = value.trim();
        if (value.isEmpty()) throw new IllegalArgumentException(label + " must not be blank");
        if (value.length() > TERMINATION_REASON_MAX_LENGTH) {
            throw new IllegalArgumentException(label + " must not exceed 500 characters");
        }
        return value;
    }

    private static String validateFingerprint(String value) {
        Objects.requireNonNull(value, "Request fingerprint must not be null");
        if (value.length() != REQUEST_FINGERPRINT_LENGTH || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Request fingerprint must be a lowercase SHA-256 hex value");
        }
        return value;
    }

    public TherapySessionId id() { return id; }
    public TherapyPlanId therapyPlanId() { return therapyPlanId; }
    public ClinicalPatientId clinicalPatientId() { return clinicalPatientId; }
    public CognitoSubject doctorSubject() { return doctorSubject; }
    public ExerciseCode exerciseCode() { return exerciseCode; }
    public TherapeuticConfigurationId therapeuticConfigurationId() { return therapeuticConfiguration.configurationId(); }
    public long configurationVersion() { return therapeuticConfiguration.configurationVersion(); }
    public TherapeuticExercise therapeuticExercise() { return therapeuticConfiguration.exercise(); }
    public int parameterSchemaVersion() { return therapeuticConfiguration.parameterSchemaVersion(); }
    public TherapeuticExerciseParameters configurationParameters() { return therapeuticConfiguration.parameters(); }
    public TherapeuticConfigurationSnapshot therapeuticConfiguration() { return therapeuticConfiguration; }
    public LocalDate quotaDate() { return quotaDate; }
    public IdempotencyKey idempotencyKey() { return idempotencyKey; }
    public String requestFingerprint() { return requestFingerprint; }
    public int minimumValidDurationMinutes() { return minimumValidDurationMinutes; }
    public int maximumSessionDurationMinutes() { return maximumSessionDurationMinutes; }
    public long minimumValidSampleCount() { return minimumValidSampleCount; }
    public TherapySessionStatus status() { return status; }
    public long activeDurationSeconds() { return activeDurationSeconds; }
    public long validSampleCount() { return validSampleCount; }
    public boolean consumesDailyQuota() { return consumesDailyQuota; }
    public Instant startedAt() { return startedAt; }
    public Optional<Instant> leaseExpiresAt() { return Optional.ofNullable(leaseExpiresAt); }
    public Optional<Instant> endedAt() { return Optional.ofNullable(endedAt); }
    public Optional<String> terminationReason() { return Optional.ofNullable(terminationReason); }
}
