package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapySessionRepository;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapySession;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities.TherapySessionEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.time.LocalDate;
import java.util.*;

@ApplicationScoped
public class JpaTherapySessionRepository implements TherapySessionRepository {
    private final TherapySessionPanacheRepository entities;
    private final EntityManager entityManager;
    public JpaTherapySessionRepository(TherapySessionPanacheRepository entities, EntityManager entityManager) {
        this.entities = Objects.requireNonNull(entities); this.entityManager = Objects.requireNonNull(entityManager);
    }
    @Override @Transactional(Transactional.TxType.MANDATORY)
    public void acquirePatientStartLock(ClinicalPatientId patientId) {
        int rows = entityManager.createNativeQuery("SELECT id FROM clinical_patients WHERE id = ?1 FOR UPDATE")
                .setParameter(1, patientId.value()).getResultList().size();
        if (rows != 1) throw new IllegalArgumentException("Clinical patient does not exist");
    }
    @Override public Optional<TherapySession> findByPatientAndIdempotencyKey(ClinicalPatientId patientId, IdempotencyKey key) {
        return Optional.ofNullable(entities.findByPatientAndIdempotencyKey(patientId.value(), key.value())).map(TherapySessionEntity::toDomain);
    }
    @Override public Optional<TherapySession> findActiveByPatient(ClinicalPatientId patientId) {
        return Optional.ofNullable(entities.findActiveByPatient(patientId.value())).map(TherapySessionEntity::toDomain);
    }
    @Override @Transactional(Transactional.TxType.MANDATORY)
    public Optional<TherapySession> findByIdForUpdate(TherapySessionId sessionId) {
        return Optional.ofNullable(entityManager.find(
                        TherapySessionEntity.class, sessionId.value(), LockModeType.PESSIMISTIC_WRITE))
                .map(TherapySessionEntity::toDomain);
    }
    @Override public long countConsumedSlots(TherapyPlanId planId, LocalDate quotaDate) { return entities.countConsumedSlots(planId.value(), quotaDate); }
    @Override public long countConsumedExerciseSlots(TherapyPlanId planId, LocalDate quotaDate, ExerciseCode exerciseCode) {
        return entities.countConsumedExerciseSlots(planId.value(), quotaDate, exerciseCode.value());
    }
    @Override public long sumExposureSeconds(TherapyPlanId planId, LocalDate quotaDate) { return entities.sumExposureSeconds(planId.value(), quotaDate); }
    @Override @Transactional(Transactional.TxType.MANDATORY)
    public void save(TherapySession session) {
        entities.findByIdOptional(session.id().value()).ifPresentOrElse(e -> e.updateFrom(session), () -> entities.persist(TherapySessionEntity.fromDomain(session)));
        entities.flush();
    }
}
