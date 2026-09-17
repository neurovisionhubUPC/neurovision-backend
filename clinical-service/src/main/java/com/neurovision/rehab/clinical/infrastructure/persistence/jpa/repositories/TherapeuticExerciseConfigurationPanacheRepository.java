package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities.TherapeuticExerciseConfigurationEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;
import java.time.Instant;

@ApplicationScoped
public class TherapeuticExerciseConfigurationPanacheRepository
        implements PanacheRepositoryBase<TherapeuticExerciseConfigurationEntity, UUID> {

    public TherapeuticExerciseConfigurationEntity findEffective(
            UUID therapyPlanId, String exercise, Instant effectiveAt) {
        return find("therapyPlanId = ?1 and exercise = ?2 and status = 'ACTIVE' "
                        + "and effectiveFrom <= ?3 order by effectiveFrom desc, version desc",
                therapyPlanId, exercise, effectiveAt).firstResult();
    }
}
