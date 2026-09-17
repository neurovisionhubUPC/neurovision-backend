package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities;

import com.neurovision.rehab.clinical.domain.model.aggregates.TherapySession;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.infrastructure.persistence.json.TherapeuticConfigurationJsonCodec;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "therapy_sessions")
public class TherapySessionEntity {
    @Id @Column(nullable = false, updatable = false) private UUID id;
    @Column(name = "therapy_plan_id", nullable = false, updatable = false) private UUID therapyPlanId;
    @Column(name = "clinical_patient_id", nullable = false, updatable = false) private UUID clinicalPatientId;
    @Column(name = "doctor_cognito_sub", nullable = false, length = 128, updatable = false) private String doctorSubject;
    @Column(name = "exercise_code", nullable = false, length = 100, updatable = false) private String exerciseCode;
    @Column(name = "therapeutic_configuration_id", nullable = false, updatable = false)
    private UUID therapeuticConfigurationId;
    @Column(name = "configuration_version", nullable = false, updatable = false) private long configurationVersion;
    @Column(name = "therapeutic_exercise", nullable = false, length = 32, updatable = false)
    private String therapeuticExercise;
    @Column(name = "parameter_schema_version", nullable = false, updatable = false)
    private int parameterSchemaVersion;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "configuration_parameters", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String configurationParameters;
    @Column(name = "quota_date", nullable = false, updatable = false) private LocalDate quotaDate;
    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false) private String idempotencyKey;
    @Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false) private String requestFingerprint;
    @Column(name = "minimum_valid_duration_minutes", nullable = false, updatable = false) private int minimumValidDurationMinutes;
    @Column(name = "maximum_session_duration_minutes", nullable = false, updatable = false) private int maximumSessionDurationMinutes;
    @Column(name = "minimum_valid_sample_count", nullable = false, updatable = false) private long minimumValidSampleCount;
    @Column(nullable = false, length = 32) private String status;
    @Column(name = "active_duration_seconds", nullable = false) private long activeDurationSeconds;
    @Column(name = "valid_sample_count", nullable = false) private long validSampleCount;
    @Column(name = "consumes_daily_quota", nullable = false) private boolean consumesDailyQuota;
    @Column(name = "started_at", nullable = false, updatable = false) private Instant startedAt;
    @Column(name = "lease_expires_at") private Instant leaseExpiresAt;
    @Column(name = "ended_at") private Instant endedAt;
    @Column(name = "termination_reason", length = 500) private String terminationReason;
    @Version @Column(nullable = false) private long version;

    protected TherapySessionEntity() {}

    public static TherapySessionEntity fromDomain(
            TherapySession session, TherapeuticConfigurationJsonCodec codec) {
        var entity = new TherapySessionEntity();
        entity.id = session.id().value();
        entity.therapyPlanId = session.therapyPlanId().value();
        entity.clinicalPatientId = session.clinicalPatientId().value();
        entity.doctorSubject = session.doctorSubject().value();
        entity.exerciseCode = session.exerciseCode().value();
        entity.therapeuticConfigurationId = session.therapeuticConfigurationId().value();
        entity.configurationVersion = session.configurationVersion();
        entity.therapeuticExercise = session.therapeuticExercise().name();
        entity.parameterSchemaVersion = session.parameterSchemaVersion();
        entity.configurationParameters = codec.encode(session.configurationParameters());
        entity.quotaDate = session.quotaDate();
        entity.idempotencyKey = session.idempotencyKey().value();
        entity.requestFingerprint = session.requestFingerprint();
        entity.minimumValidDurationMinutes = session.minimumValidDurationMinutes();
        entity.maximumSessionDurationMinutes = session.maximumSessionDurationMinutes();
        entity.minimumValidSampleCount = session.minimumValidSampleCount();
        entity.startedAt = session.startedAt();
        entity.updateFrom(session);
        return entity;
    }

    public void updateFrom(TherapySession session) {
        if (!id.equals(session.id().value())) throw new IllegalArgumentException("Cannot update a different therapy session");
        status = session.status().name();
        activeDurationSeconds = session.activeDurationSeconds();
        validSampleCount = session.validSampleCount();
        consumesDailyQuota = session.consumesDailyQuota();
        leaseExpiresAt = session.leaseExpiresAt().orElse(null);
        endedAt = session.endedAt().orElse(null);
        terminationReason = session.terminationReason().orElse(null);
    }

    public TherapySession toDomain(TherapeuticConfigurationJsonCodec codec) {
        var exercise = TherapeuticExercise.valueOf(therapeuticExercise);
        var configuration = new TherapeuticConfigurationSnapshot(
                new TherapeuticConfigurationId(therapeuticConfigurationId), configurationVersion,
                exercise, parameterSchemaVersion,
                codec.decode(exercise, parameterSchemaVersion, configurationParameters));
        return TherapySession.rehydrate(new TherapySessionId(id), new TherapyPlanId(therapyPlanId),
                new ClinicalPatientId(clinicalPatientId), new CognitoSubject(doctorSubject), new ExerciseCode(exerciseCode),
                configuration, quotaDate, new IdempotencyKey(idempotencyKey), requestFingerprint,
                minimumValidDurationMinutes, maximumSessionDurationMinutes, minimumValidSampleCount,
                TherapySessionStatus.valueOf(status), activeDurationSeconds, validSampleCount,
                consumesDailyQuota, startedAt, leaseExpiresAt, endedAt, terminationReason);
    }
}
