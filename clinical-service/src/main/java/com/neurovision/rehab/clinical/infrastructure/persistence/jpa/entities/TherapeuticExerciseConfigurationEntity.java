package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities;

import com.neurovision.rehab.clinical.domain.model.aggregates.TherapeuticExerciseConfiguration;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.infrastructure.persistence.json.TherapeuticConfigurationJsonCodec;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "therapeutic_exercise_configurations")
public class TherapeuticExerciseConfigurationEntity {
    @Id @Column(nullable = false, updatable = false) private UUID id;
    @Column(name = "therapy_plan_id", nullable = false, updatable = false) private UUID therapyPlanId;
    @Column(name = "exercise_code", nullable = false, length = 32, updatable = false) private String exercise;
    @Column(nullable = false, updatable = false) private long version;
    @Column(name = "parameter_schema_version", nullable = false, updatable = false) private int parameterSchemaVersion;
    @Column(name = "effective_from", nullable = false, updatable = false) private Instant effectiveFrom;
    @Column(name = "created_by_doctor_subject", nullable = false, length = CognitoSubject.MAX_LENGTH, updatable = false)
    private String createdByDoctorSubject;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false, length = 16, updatable = false) private String status;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String parameters;

    protected TherapeuticExerciseConfigurationEntity() {}

    public static TherapeuticExerciseConfigurationEntity fromDomain(
            TherapeuticExerciseConfiguration source, TherapeuticConfigurationJsonCodec codec) {
        var entity = new TherapeuticExerciseConfigurationEntity();
        entity.id = source.id().value();
        entity.therapyPlanId = source.therapyPlanId().value();
        entity.exercise = source.exercise().name();
        entity.version = source.version();
        entity.parameterSchemaVersion = source.parameterSchemaVersion();
        entity.effectiveFrom = source.effectiveFrom();
        entity.createdByDoctorSubject = source.createdByDoctorSubject().value();
        entity.createdAt = source.createdAt();
        entity.status = source.status().name();
        entity.parameters = codec.encode(source.parameters());
        return entity;
    }

    public TherapeuticExerciseConfiguration toDomain(TherapeuticConfigurationJsonCodec codec) {
        var exerciseType = TherapeuticExercise.valueOf(exercise);
        return TherapeuticExerciseConfiguration.rehydrate(
                new TherapeuticConfigurationId(id), new TherapyPlanId(therapyPlanId), exerciseType,
                version, parameterSchemaVersion, effectiveFrom,
                new CognitoSubject(createdByDoctorSubject), createdAt,
                TherapeuticConfigurationStatus.valueOf(status),
                codec.decode(exerciseType, parameterSchemaVersion, parameters));
    }
}
