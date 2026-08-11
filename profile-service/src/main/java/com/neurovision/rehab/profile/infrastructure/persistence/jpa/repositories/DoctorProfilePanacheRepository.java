package com.neurovision.rehab.profile.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.profile.infrastructure.persistence.jpa.entities.DoctorProfileEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class DoctorProfilePanacheRepository
        implements PanacheRepositoryBase<DoctorProfileEntity, UUID> {

    public DoctorProfileEntity findByProfileId(UUID profileId) {
        return find("profileId", profileId).firstResult();
    }
}
