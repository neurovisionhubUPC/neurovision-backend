package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapeuticExerciseConfigurationRepository;
import com.neurovision.rehab.clinical.application.internal.commandservices.TherapeuticConfigurationVersionConflictException;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapeuticExerciseConfiguration;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapeuticExercise;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapyPlanId;
import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities.TherapeuticExerciseConfigurationEntity;
import com.neurovision.rehab.clinical.infrastructure.persistence.json.TherapeuticConfigurationJsonCodec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.Objects;
import java.util.OptionalLong;
import java.time.Instant;
import java.util.Optional;

@ApplicationScoped
public class JpaTherapeuticExerciseConfigurationRepository
        implements TherapeuticExerciseConfigurationRepository {
    private final TherapeuticExerciseConfigurationPanacheRepository entities;
    private final TherapeuticConfigurationJsonCodec codec;

    public JpaTherapeuticExerciseConfigurationRepository(
            TherapeuticExerciseConfigurationPanacheRepository entities,
            TherapeuticConfigurationJsonCodec codec) {
        this.entities = Objects.requireNonNull(entities);
        this.codec = Objects.requireNonNull(codec);
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public OptionalLong findLatestVersion(TherapyPlanId planId, TherapeuticExercise exercise) {
        Number value = (Number) entities.getEntityManager().createNativeQuery(
                        "SELECT max(version) FROM therapeutic_exercise_configurations "
                                + "WHERE therapy_plan_id = :planId AND exercise_code = :exercise")
                .setParameter("planId", planId.value())
                .setParameter("exercise", exercise.name())
                .getSingleResult();
        return value == null ? OptionalLong.empty() : OptionalLong.of(value.longValue());
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public Optional<TherapeuticExerciseConfiguration> findEffective(
            TherapyPlanId planId, TherapeuticExercise exercise, Instant effectiveAt) {
        Objects.requireNonNull(planId, "Therapy plan ID must not be null");
        Objects.requireNonNull(exercise, "Therapeutic exercise must not be null");
        Objects.requireNonNull(effectiveAt, "Effective time must not be null");
        return Optional.ofNullable(entities.findEffective(planId.value(), exercise.name(), effectiveAt))
                .map(entity -> entity.toDomain(codec));
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void append(TherapeuticExerciseConfiguration configuration) {
        try {
            entities.persist(TherapeuticExerciseConfigurationEntity.fromDomain(configuration, codec));
            entities.flush();
        } catch (RuntimeException exception) {
            if (hasConstraint(exception, "uq_therapeutic_configuration_version")) {
                throw new TherapeuticConfigurationVersionConflictException(
                        "Therapeutic configuration version already exists", exception);
            }
            throw exception;
        }
    }

    private static boolean hasConstraint(Throwable failure, String constraint) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException violation
                    && constraint.equals(violation.getConstraintName())) return true;
            if (current.getMessage() != null && current.getMessage().contains(constraint)) return true;
        }
        return false;
    }
}
