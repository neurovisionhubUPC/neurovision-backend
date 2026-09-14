package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.application.internal.commandservices.TherapyPlanRevisionConflictException;
import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapyPlanRevisionRepository;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapyPlanRevision;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapyPlanId;
import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities.TherapyPlanRevisionEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.*;

@ApplicationScoped
public class JpaTherapyPlanRevisionRepository implements TherapyPlanRevisionRepository {
    private final TherapyPlanRevisionPanacheRepository entities;

    public JpaTherapyPlanRevisionRepository(TherapyPlanRevisionPanacheRepository entities) {
        this.entities = Objects.requireNonNull(entities);
    }

    @Override @Transactional(Transactional.TxType.MANDATORY)
    public List<TherapyPlanRevision> findEffective(TherapyPlanId planId, Instant effectiveAt) {
        return entities.find("therapyPlanId = ?1 and effectiveFrom <= ?2 order by sequence",
                        planId.value(), effectiveAt).list().stream()
                .map(TherapyPlanRevisionEntity::toDomain).toList();
    }

    @Override @Transactional(Transactional.TxType.MANDATORY)
    public List<TherapyPlanRevision> findByCommandId(TherapyPlanId planId, UUID commandId) {
        return entities.find("therapyPlanId = ?1 and commandId = ?2 order by sequence",
                        planId.value(), commandId).list().stream()
                .map(TherapyPlanRevisionEntity::toDomain).toList();
    }

    @Override @Transactional(Transactional.TxType.MANDATORY)
    public long findLatestSequence(TherapyPlanId planId) {
        Number value = (Number) entities.getEntityManager().createNativeQuery(
                        "SELECT max(sequence) FROM therapy_plan_revisions WHERE therapy_plan_id = :planId")
                .setParameter("planId", planId.value()).getSingleResult();
        return value == null ? 0L : value.longValue();
    }

    @Override @Transactional(Transactional.TxType.MANDATORY)
    public void appendAll(List<TherapyPlanRevision> revisions) {
        try {
            revisions.stream().map(TherapyPlanRevisionEntity::fromDomain).forEach(entities::persist);
            entities.flush();
        } catch (RuntimeException exception) {
            if (hasConstraint(exception, "uq_therapy_plan_revision_sequence")
                    || hasConstraint(exception, "uq_therapy_plan_revision_command_kind")) {
                throw new TherapyPlanRevisionConflictException("Therapy plan revision conflict", exception);
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
