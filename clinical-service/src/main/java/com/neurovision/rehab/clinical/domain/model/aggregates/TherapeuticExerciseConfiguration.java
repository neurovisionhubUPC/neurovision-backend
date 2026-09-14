package com.neurovision.rehab.clinical.domain.model.aggregates;

import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.time.Instant;
import java.util.Objects;

/** Immutable, append-only version of one prescribed therapeutic exercise configuration. */
public final class TherapeuticExerciseConfiguration {
    private final TherapeuticConfigurationId id;
    private final TherapyPlanId therapyPlanId;
    private final TherapeuticExercise exercise;
    private final long version;
    private final int parameterSchemaVersion;
    private final Instant effectiveFrom;
    private final CognitoSubject createdByDoctorSubject;
    private final Instant createdAt;
    private final TherapeuticConfigurationStatus status;
    private final TherapeuticExerciseParameters parameters;

    private TherapeuticExerciseConfiguration(
            TherapeuticConfigurationId id, TherapyPlanId therapyPlanId,
            TherapeuticExercise exercise, long version, int parameterSchemaVersion,
            Instant effectiveFrom, CognitoSubject createdByDoctorSubject, Instant createdAt,
            TherapeuticConfigurationStatus status, TherapeuticExerciseParameters parameters) {
        this.id = Objects.requireNonNull(id, "Configuration ID must not be null");
        this.therapyPlanId = Objects.requireNonNull(therapyPlanId, "Therapy plan ID must not be null");
        this.exercise = Objects.requireNonNull(exercise, "Exercise must not be null");
        if (version < 1) throw new IllegalArgumentException("Configuration version must be positive");
        if (parameterSchemaVersion != TherapeuticExerciseParameters.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported parameter schema version");
        }
        this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "Effective time must not be null");
        this.createdByDoctorSubject = Objects.requireNonNull(createdByDoctorSubject, "Author must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "Creation time must not be null");
        if (effectiveFrom.isBefore(createdAt)) {
            throw new IllegalArgumentException("Effective time must not precede creation time");
        }
        this.status = Objects.requireNonNull(status, "Configuration status must not be null");
        this.parameters = Objects.requireNonNull(parameters, "Configuration parameters must not be null");
        if (parameters.exercise() != exercise) throw new IllegalArgumentException("Parameters do not match exercise");
        this.version = version;
        this.parameterSchemaVersion = parameterSchemaVersion;
    }

    public static TherapeuticExerciseConfiguration create(
            TherapeuticConfigurationId id, TherapyPlanId planId, TherapeuticExercise exercise,
            long version, int schemaVersion, Instant effectiveFrom, CognitoSubject doctor,
            Instant createdAt, TherapeuticExerciseParameters parameters) {
        return new TherapeuticExerciseConfiguration(id, planId, exercise, version, schemaVersion,
                effectiveFrom, doctor, createdAt, TherapeuticConfigurationStatus.ACTIVE, parameters);
    }

    public static TherapeuticExerciseConfiguration rehydrate(
            TherapeuticConfigurationId id, TherapyPlanId planId, TherapeuticExercise exercise,
            long version, int schemaVersion, Instant effectiveFrom, CognitoSubject doctor,
            Instant createdAt, TherapeuticConfigurationStatus status,
            TherapeuticExerciseParameters parameters) {
        return new TherapeuticExerciseConfiguration(id, planId, exercise, version, schemaVersion,
                effectiveFrom, doctor, createdAt, status, parameters);
    }

    public TherapeuticConfigurationId id() { return id; }
    public TherapyPlanId therapyPlanId() { return therapyPlanId; }
    public TherapeuticExercise exercise() { return exercise; }
    public long version() { return version; }
    public int parameterSchemaVersion() { return parameterSchemaVersion; }
    public Instant effectiveFrom() { return effectiveFrom; }
    public CognitoSubject createdByDoctorSubject() { return createdByDoctorSubject; }
    public Instant createdAt() { return createdAt; }
    public TherapeuticConfigurationStatus status() { return status; }
    public TherapeuticExerciseParameters parameters() { return parameters; }
}
