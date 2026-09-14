package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities.TherapyPlanRevisionEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class TherapyPlanRevisionPanacheRepository
        implements PanacheRepositoryBase<TherapyPlanRevisionEntity, UUID> {}
