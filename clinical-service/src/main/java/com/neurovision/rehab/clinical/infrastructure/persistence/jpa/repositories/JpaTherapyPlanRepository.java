package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.application.ports.out.persistence.TherapyPlanRepository;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapyPlan;
import com.neurovision.rehab.clinical.domain.model.valueobjects.TherapyPlanId;
import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities.TherapyPlanEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.*;

@ApplicationScoped
public class JpaTherapyPlanRepository implements TherapyPlanRepository {
    private final TherapyPlanPanacheRepository entities;
    public JpaTherapyPlanRepository(TherapyPlanPanacheRepository entities) { this.entities = Objects.requireNonNull(entities); }
    @Override @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<TherapyPlan> findById(TherapyPlanId id) { return entities.findByIdOptional(id.value()).map(TherapyPlanEntity::toDomain); }
    @Override @Transactional(Transactional.TxType.REQUIRED)
    public void save(TherapyPlan plan) {
        entities.findByIdOptional(plan.id().value()).ifPresentOrElse(e -> e.updateFrom(plan), () -> entities.persist(TherapyPlanEntity.fromDomain(plan)));
        entities.flush();
    }
}
