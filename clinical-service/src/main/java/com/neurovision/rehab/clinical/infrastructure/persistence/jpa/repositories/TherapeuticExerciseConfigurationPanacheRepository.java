package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities.TherapeuticExerciseConfigurationEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class TherapeuticExerciseConfigurationPanacheRepository
        implements PanacheRepositoryBase<TherapeuticExerciseConfigurationEntity, UUID> {}
